package com.fixcraft.ito.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/** The back camera, or the first one, and the frame size the scanner uses. */
internal class QrCameraChoice(val id: String, val size: Size) {
    companion object {
        // Enough pixels for a dense invitation code at arm's length, little enough
        // to decode every few frames.
        private const val TARGET_PIXELS = 1280 * 720
        private const val MAX_PIXELS = 1920 * 1080

        fun select(manager: CameraManager): QrCameraChoice? {
            val ids = try { manager.cameraIdList } catch (_: Exception) { return null }
            val ordered = ids.sortedBy { id ->
                val facing = try { manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) }
                catch (_: Exception) { null }
                if (facing == CameraCharacteristics.LENS_FACING_BACK) 0 else 1
            }
            for (id in ordered) {
                val map = try {
                    manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                } catch (_: Exception) { null } ?: continue
                val size = map.getOutputSizes(ImageFormat.YUV_420_888)
                    ?.filter { it.width * it.height <= MAX_PIXELS }
                    ?.minByOrNull { abs(it.width * it.height - TARGET_PIXELS) } ?: continue
                return QrCameraChoice(id, size)
            }
            return null
        }
    }
}

/**
 * One scanning session on the framework Camera2 API. Every camera object
 * lives on its own handler thread. Frames go to [onText] on that thread until
 * it returns true, and [close] releases the camera and wipes the frame copy,
 * which may show an invitation. CAMERA must be granted before [open].
 */
internal class QrCamera(
    private val context: Context,
    private val choice: QrCameraChoice,
    private val onFrame: () -> Unit,
    private val onText: (String) -> Boolean,
    private val onError: () -> Unit,
) {
    private val thread = HandlerThread("ito-qr").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var luma = ByteArray(choice.size.width * choice.size.height)
    // Read and written on the handler thread only.
    private var closed = false
    private var done = false
    // An openCamera whose callback has not arrived. The thread outlives it,
    // so a late onOpened can still close the device it hands over.
    private var opening = false

    fun open(texture: SurfaceTexture) = handler.post { start(texture) }

    /** Releases everything once. Safe from any thread and after a failure. */
    fun close() {
        handler.post {
            if (!closed) {
                closed = true
                release()
                luma.fill(0)
                luma = ByteArray(0)
            }
            if (!opening) thread.quitSafely()
        }
    }

    @SuppressLint("MissingPermission")
    private fun start(texture: SurfaceTexture) {
        if (closed) return
        val manager = context.getSystemService(CameraManager::class.java)
        opening = true
        try {
            manager.openCamera(choice.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (settled()) { camera.close(); return }
                    device = camera
                    configure(camera, texture)
                }
                override fun onDisconnected(camera: CameraDevice) = fail(camera)
                override fun onError(camera: CameraDevice, error: Int) = fail(camera)
            })
        } catch (_: Exception) {
            opening = false
            onError()
        }
    }

    /** The open resolved. True when the scanner closed meanwhile, after which the thread ends. */
    private fun settled(): Boolean {
        if (!opening) return closed
        opening = false
        if (closed) thread.quitSafely()
        return closed
    }

    private fun fail(camera: CameraDevice) {
        val wasClosed = settled()
        if (device == null || device === camera) camera.close()
        if (!wasClosed) { release(); onError() }
    }

    private fun configure(camera: CameraDevice, texture: SurfaceTexture) {
        val size = choice.size
        val surface: Surface
        val frames: ImageReader
        try {
            // The view may have released the texture since the open began.
            texture.setDefaultBufferSize(size.width, size.height)
            surface = Surface(texture).also { preview = it }
            frames = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).also { reader = it }
        } catch (_: RuntimeException) {
            release()
            if (!closed) onError()
            return
        }
        frames.setOnImageAvailableListener({ decode(it) }, handler)
        val outputs = listOf(OutputConfiguration(surface), OutputConfiguration(frames.surface))
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (closed) { configured.close(); return }
                session = configured
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        addTarget(frames.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    }.build()
                    configured.setRepeatingRequest(request, null, handler)
                } catch (_: Exception) {
                    release()
                    onError()
                }
            }
            override fun onConfigureFailed(failed: CameraCaptureSession) {
                release()
                onError()
            }
        }
        try {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback))
        } catch (_: Exception) {
            release()
            onError()
        }
    }

    private fun decode(frames: ImageReader) {
        val image = try { frames.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        val width = choice.size.width
        val height = choice.size.height
        try {
            if (closed || done || image.width != width || image.height != height) return
            // Plane 0 of YUV_420_888 is luminance with a pixel stride of one.
            val plane = image.planes[0]
            val buffer = plane.buffer
            for (row in 0 until height) {
                buffer.position(row * plane.rowStride)
                buffer.get(luma, row * width, width)
            }
        } catch (_: RuntimeException) {
            return
        } finally {
            image.close()
        }
        onFrame()
        val text = QrCodec.decode(luma, width, height) ?: return
        if (onText(text)) {
            done = true
            release()
        }
    }

    private fun release() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        device?.close()
        device = null
        reader?.close()
        reader = null
        preview?.release()
        preview = null
    }
}
