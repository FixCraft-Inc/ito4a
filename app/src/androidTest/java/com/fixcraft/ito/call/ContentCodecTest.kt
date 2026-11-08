package com.fixcraft.ito.call

import android.media.MediaCodec
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/** Platform codec/JNI execution, without microphone or physical audio claims. */
@RunWith(AndroidJUnit4::class)
class ContentCodecTest {
    @Test fun nativeCodecRefusesMalformedDataAndRoundTripsText() {
        val text = ByteArray(16) { it.toByte() } + "hello 糸".toByteArray(Charsets.UTF_8)
        val encoded = CallNative.contentEncode(ContentSession.CHAT, 3, text)
        val decoded = CallNative.contentDecode(encoded)
        try {
            assertEquals(ContentSession.CHAT, decoded.kind)
            assertEquals(3L, decoded.sequence)
            assertArrayEquals(text, decoded.payload)
            encoded[6] = 1
            assertThrows(CallFailure::class.java) { CallNative.contentDecode(encoded) }
        } finally { text.fill(0); encoded.fill(0); decoded.payload.fill(0) }
    }

    @Test fun platformOpusProducesFixed20msContentPackets() {
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48000, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 24000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1920)
        }
        val info = MediaCodec.BufferInfo()
        var packets = 0
        var sequence = 1L
        val packetsToDecode = ArrayList<ByteArray>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            repeat(25) { frame ->
                val pcm = ByteBuffer.allocate(1920).order(ByteOrder.LITTLE_ENDIAN)
                repeat(960) { index -> pcm.putShort((sin((frame * 960 + index) * 2 * Math.PI * 440 / 48000) * 12000).toInt().toShort()) }
                val input = codec.dequeueInputBuffer(100_000)
                assertTrue(input >= 0)
                codec.getInputBuffer(input)!!.apply { clear(); put(pcm.array()) }
                codec.queueInputBuffer(input, 0, 1920, frame * 20_000L, 0)
                pcm.array().fill(0)
                while (true) {
                    val output = codec.dequeueOutputBuffer(info, 20_000)
                    if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (output < 0) continue
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val body = ByteBuffer.allocate(8 + info.size).putLong(packets * 960L)
                            val data = codec.getOutputBuffer(output)!!.apply { position(info.offset); limit(info.offset + info.size) }
                            body.put(data)
                            val encoded = CallNative.contentEncode(ContentSession.AUDIO, sequence++, body.array())
                            val decoded = CallNative.contentDecode(encoded)
                            try {
                                assertEquals(ContentSession.AUDIO, decoded.kind)
                                packetsToDecode += decoded.payload.copyOfRange(8, decoded.payload.size)
                                packets++
                            }
                            finally { encoded.fill(0); decoded.payload.fill(0); body.array().fill(0) }
                        }
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
            assertTrue("Expected real platform Opus packets", packets >= 20)
            decodePackets(packetsToDecode)
        } finally { codec.release(); packetsToDecode.forEach { it.fill(0) } }
    }

    private fun decodePackets(packets: List<ByteArray>) {
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        val info = MediaCodec.BufferInfo()
        var samples = 0
        var nonzero = 0
        try {
            codec.configure(OpusVoice.decoderFormat(), null, null, 0)
            codec.start()
            packets.forEachIndexed { frame, packet ->
                val input = codec.dequeueInputBuffer(100_000)
                assertTrue(input >= 0)
                codec.getInputBuffer(input)!!.apply { clear(); put(packet) }
                codec.queueInputBuffer(input, 0, packet.size, frame * 20_000L, 0)
                while (true) {
                    val output = codec.dequeueOutputBuffer(info, 20_000)
                    if (output == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        assertEquals(48000, codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                        assertEquals(1, codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                        continue
                    }
                    if (output < 0) continue
                    try {
                        assertTrue(info.size in 0..1920)
                        val data = codec.getOutputBuffer(output)!!.order(ByteOrder.LITTLE_ENDIAN)
                            .apply { position(info.offset); limit(info.offset + info.size) }
                        while (data.remaining() >= 2) {
                            if (data.short.toInt() != 0) nonzero++
                            samples++
                        }
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
            assertTrue("Expected decoded mono PCM", samples >= 960 * 15)
            assertTrue("The test tone must survive encoding", nonzero >= 960 * 10)
        } finally { codec.release() }
    }
}
