package com.fixcraft.ito.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Android owns devices and the maintained platform Opus implementation.
 * No sockets or crypto live here. One capture and one playout worker, joined
 * before codecs/devices are released. Unsupported codecs fail the call.
 * [routesChanged] runs on the main thread when devices come or go or the
 * route changes, while audio runs.
 */
class OpusVoice(
    context: Context,
    private val send: (Long, ByteArray) -> Boolean,
    private val failed: () -> Unit,
    private val routesChanged: () -> Unit = {},
) : AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(AudioManager::class.java)
    private val lifecycle = VoiceLifecycle()
    private val muted = AtomicBoolean(false)
    private data class Packet(val at: Long, val bytes: ByteArray)
    private val packets = ArrayBlockingQueue<Packet>(6)
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var encoder: MediaCodec? = null
    private var decoder: MediaCodec? = null
    private var capture: Thread? = null
    private var playout: Thread? = null
    private var priorMode = AudioManager.MODE_NORMAL
    private var priorSpeaker = false
    private var focusHeld = false
    // Before Android 12 Bluetooth needs its own SCO link, started only on request.
    @Volatile private var legacyBluetooth = false
    private val main = Handler(Looper.getMainLooper())
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = routesChanged()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = routesChanged()
    }
    private var communicationListener: Any? = null
    private val playoutClock = PlayoutClock()
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes).setOnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) stopWithFailure()
        }.build()

    @Synchronized fun start() {
        check(!lifecycle.running && recorder == null)
        check(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        try {
            lifecycle.beginStart()
            priorMode = manager.mode
            @Suppress("DEPRECATION")
            priorSpeaker = manager.isSpeakerphoneOn
            check(manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            focusHeld = true
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 24000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, PCM_BYTES)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            requireNotNull(encoder).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            val decodeFormat = decoderFormat()
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
            requireNotNull(decoder).apply {
                configure(decodeFormat, null, null, 0)
                start()
            }
            val recordBytes = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(recordBytes > 0)
            recorder = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(recordBytes, PCM_BYTES * 4)).build()
            check(recorder?.state == AudioRecord.STATE_INITIALIZED)
            val playBytes = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(playBytes > 0)
            player = AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(playBytes, PCM_BYTES * 4)).build()
            check(player?.state == AudioTrack.STATE_INITIALIZED)
            lifecycle.startCapture {
                recorder!!.startRecording()
                check(recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                player!!.play()
            }
            capture = worker("ito-capture", ::captureLoop)
            playout = worker("ito-playout", ::playoutLoop)
            manager.registerAudioDeviceCallback(devices, main)
            if (Build.VERSION.SDK_INT >= 31) communicationListener = listenForRoute()
        } catch (error: Throwable) { close(); throw error }
    }

    @RequiresApi(31)
    private fun listenForRoute(): AudioManager.OnCommunicationDeviceChangedListener {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { routesChanged() }
        manager.addOnCommunicationDeviceChangedListener(app.mainExecutor, listener)
        return listener
    }

    fun mute(value: Boolean) { muted.set(value) }

    /** The devices this call can use now, phone first. Empty unless audio runs. */
    fun routes(): List<AudioRoute> {
        if (!lifecycle.running) return emptyList()
        if (Build.VERSION.SDK_INT >= 31) return AudioRoutes.distinct(manager.availableCommunicationDevices.mapNotNull(::route))
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return AudioRoutes.legacy(outputs.map { it.type },
            outputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }.map { it.productName.toString() })
    }

    /** The route in use, as one of [routes]. */
    fun currentRoute(): AudioRoute? {
        val routes = routes()
        if (Build.VERSION.SDK_INT >= 31) {
            val current = manager.communicationDevice?.let(::route) ?: return null
            return routes.firstOrNull { it.kind == current.kind && it.name == current.name } ?: current
        }
        @Suppress("DEPRECATION")
        val kind = when { manager.isSpeakerphoneOn -> AudioRouteKind.Speaker; legacyBluetooth -> AudioRouteKind.Bluetooth; else -> null }
        return routes.firstOrNull { it.kind == kind }
            ?: routes.firstOrNull { it.kind != AudioRouteKind.Speaker && it.kind != AudioRouteKind.Bluetooth }
    }

    /**
     * Moves call audio to one of [routes]. A headset carries the microphone
     * too. Returns false when Android refuses or the device has gone, leaving
     * the route unchanged. A refused route does not end the call.
     */
    @Synchronized fun selectRoute(id: Int): Boolean {
        if (!lifecycle.running) return false
        val chosen = routes().firstOrNull { it.id == id } ?: return false
        if (Build.VERSION.SDK_INT >= 31) {
            val device = manager.availableCommunicationDevices.firstOrNull { candidate ->
                route(candidate)?.let { it.kind == chosen.kind && it.name == chosen.name } == true
            } ?: return false
            return manager.setCommunicationDevice(device)
        }
        @Suppress("DEPRECATION")
        when (chosen.kind) {
            AudioRouteKind.Speaker -> { stopLegacyBluetooth(); manager.isSpeakerphoneOn = true }
            AudioRouteKind.Bluetooth -> {
                manager.isSpeakerphoneOn = false
                manager.startBluetoothSco()
                manager.isBluetoothScoOn = true
                legacyBluetooth = true
            }
            else -> { stopLegacyBluetooth(); manager.isSpeakerphoneOn = false }
        }
        main.post { routesChanged() }
        return true
    }

    private fun route(device: AudioDeviceInfo): AudioRoute? = AudioRoutes.kindOf(device.type)?.let { kind ->
        val external = kind != AudioRouteKind.Earpiece && kind != AudioRouteKind.Speaker
        AudioRoute(device.id, kind, if (external) device.productName.toString() else "")
    }

    @Suppress("DEPRECATION")
    private fun stopLegacyBluetooth() {
        if (!legacyBluetooth) return
        legacyBluetooth = false
        manager.isBluetoothScoOn = false
        manager.stopBluetoothSco()
    }

    /** Takes ownership. Old speech is dropped and wiped, including on shutdown. */
    fun receive(sample: Long, packet: ByteArray) {
        if (!lifecycle.running) { packet.fill(0); return }
        val at = playoutClock.deadlineBase(sample, SystemClock.elapsedRealtime())
        if (at == null) { packet.fill(0); return }
        val entry = Packet(at, packet)
        if (!packets.offer(entry)) {
            packets.poll()?.bytes?.fill(0)
            if (!packets.offer(entry)) packet.fill(0)
        }
        if (!lifecycle.running) clearPackets()
    }

    private fun worker(name: String, action: () -> Unit) = Thread({
        try { action() } catch (_: Exception) { stopWithFailure() }
    }, name).apply { start() }

    private fun stopWithFailure() {
        if (lifecycle.fail()) failed()
    }

    private fun captureLoop() {
        val pcm = ByteArray(PCM_BYTES)
        val codec = requireNotNull(encoder)
        val device = requireNotNull(recorder)
        val info = MediaCodec.BufferInfo()
        var sample = 0L
        var encodedSample = 0L
        var blockedAt = 0L
        try {
            while (lifecycle.running) {
                var count = 0
                while (count < pcm.size && lifecycle.running) {
                    val read = device.read(pcm, count, pcm.size - count, AudioRecord.READ_BLOCKING)
                    check(read > 0)
                    count += read
                }
                if (!lifecycle.running) break
                if (muted.get()) pcm.fill(0)
                val slot = codec.dequeueInputBuffer(20_000)
                check(slot >= 0)
                requireNotNull(codec.getInputBuffer(slot)).apply { clear(); put(pcm) }
                codec.queueInputBuffer(slot, 0, pcm.size, sample * 1_000_000 / RATE, 0)
                sample += SAMPLES
                pcm.fill(0)
                while (lifecycle.running) {
                    val output = codec.dequeueOutputBuffer(info, 0)
                    if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (output < 0) continue
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(info.size <= 1275)
                            val packet = ByteArray(info.size)
                            try {
                                requireNotNull(codec.getOutputBuffer(output)).apply { position(info.offset); get(packet) }
                                val accepted = send(encodedSample, packet)
                                encodedSample += SAMPLES
                                val now = SystemClock.elapsedRealtime()
                                if (accepted) blockedAt = 0 else if (blockedAt == 0L) blockedAt = now
                                check(blockedAt == 0L || now - blockedAt <= 500)
                            } finally { packet.fill(0) }
                        }
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
        } finally { pcm.fill(0) }
    }

    private fun playoutLoop() {
        val codec = requireNotNull(decoder)
        val device = requireNotNull(player)
        val info = MediaCodec.BufferInfo()
        val pcm = ByteArray(PCM_BYTES)
        var timestamp = 0L
        try {
            while (lifecycle.running) {
                val entry = packets.poll(20, TimeUnit.MILLISECONDS)
                if (entry != null) try {
                    if (SystemClock.elapsedRealtime() - entry.at <= 120) {
                        val slot = codec.dequeueInputBuffer(20_000)
                        check(slot >= 0)
                        requireNotNull(codec.getInputBuffer(slot)).apply { clear(); put(entry.bytes) }
                        codec.queueInputBuffer(slot, 0, entry.bytes.size, timestamp, 0)
                        timestamp += 20_000
                    }
                } finally { entry.bytes.fill(0) }
                while (lifecycle.running) {
                    val output = codec.dequeueOutputBuffer(info, 0)
                    if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == RATE &&
                            codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                        continue
                    }
                    if (output < 0) continue
                    try {
                        check(info.size in 0..PCM_BYTES)
                        if (info.size > 0) {
                            requireNotNull(codec.getOutputBuffer(output)).apply { position(info.offset); get(pcm, 0, info.size) }
                            var offset = 0
                            val deadline = SystemClock.elapsedRealtime() + 120
                            while (offset < info.size && lifecycle.running) {
                                val written = device.write(pcm, offset, info.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                                check(written >= 0)
                                offset += written
                                check(SystemClock.elapsedRealtime() <= deadline)
                                if (written == 0) Thread.sleep(2)
                            }
                            pcm.fill(0)
                        }
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
        } finally { pcm.fill(0) }
    }

    @Synchronized override fun close() {
        lifecycle.close()
        var cleanupFailed = false
        fun cleanup(action: () -> Unit) { try { action() } catch (_: Exception) { cleanupFailed = true } }
        cleanup { manager.unregisterAudioDeviceCallback(devices) }
        if (Build.VERSION.SDK_INT >= 31) communicationListener?.let { listener ->
            cleanup { manager.removeOnCommunicationDeviceChangedListener(listener as AudioManager.OnCommunicationDeviceChangedListener) }
        }
        communicationListener = null
        cleanup { recorder?.stop() }
        var interrupted = false
        fun join(worker: Thread?) {
            if (worker == null) return
            check(worker !== Thread.currentThread())
            while (worker.isAlive) try { worker.join() }
            catch (_: InterruptedException) { interrupted = true }
        }
        // Never free a device/codec while a worker still borrows it.
        join(capture)
        join(playout)
        capture = null; playout = null
        clearPackets()
        cleanup { recorder?.release() }; recorder = null
        cleanup { player?.release() }; player = null
        cleanup { encoder?.release() }; encoder = null
        cleanup { decoder?.release() }; decoder = null
        if (focusHeld) {
            if (Build.VERSION.SDK_INT >= 31) cleanup { manager.clearCommunicationDevice() }
            else {
                cleanup(::stopLegacyBluetooth)
                cleanup {
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = priorSpeaker
                }
            }
            cleanup { manager.mode = priorMode }
            cleanup { manager.abandonAudioFocusRequest(focus) }
            focusHeld = false
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (cleanupFailed) failed()
    }

    private fun clearPackets() { while (true) (packets.poll() ?: return).bytes.fill(0) }

    companion object {
        const val RATE = 48000
        const val SAMPLES = 960
        const val PCM_BYTES = SAMPLES * 2

        internal fun decoderFormat(): MediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            // OpusHead v1, mono, pre-skip zero, 48 kHz, gain zero, mapping zero.
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1).put(1)
                .putShort(0).putInt(RATE).putShort(0).put(0).array()
            setByteBuffer("csd-0", ByteBuffer.wrap(head))
            setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(0); flip() })
            setByteBuffer("csd-2", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).apply { putLong(0); flip() })
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1275)
        }
    }
}
