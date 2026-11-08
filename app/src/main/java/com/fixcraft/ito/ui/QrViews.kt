package com.fixcraft.ito.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fixcraft.ito.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the window out of screenshots, screen recording and the recents
 * thumbnail while composed. Used by every screen that shows or scans an
 * invitation.
 */
@Composable
internal fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val wasSecure = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!wasSecure) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/**
 * [text] as a QR code: dark modules on white with a four-module quiet zone
 * in both themes, as scanners expect. The module matrix is cleared when the
 * code leaves the screen.
 */
@Composable
internal fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { QrCodec.encode(text) }
    DisposableEffect(matrix) { onDispose { matrix.clear() } }
    Canvas(modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f).semantics { contentDescription = description }) {
        val modules = matrix.width + 2 * QUIET_ZONE
        val cell = size.minDimension / modules
        drawRect(Color.White)
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix.get(x, y)) {
                drawRect(Color.Black, Offset((x + QUIET_ZONE) * cell, (y + QUIET_ZONE) * cell), Size(cell, cell))
            }
        }
    }
}

private const val QUIET_ZONE = 4

/**
 * Scans one QR code. The camera permission is requested here, when the
 * person starts a scan, never earlier. [accept] sees each decoded text on
 * the camera thread and returns true for the one to keep, so codes of the
 * wrong kind are ignored instead of acted on. The result is delivered on
 * the main thread through [onResult].
 */
@Composable
internal fun QrScanner(
    rationale: Int,
    accept: (String) -> Boolean,
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        asked = true
    }
    LaunchedEffect(Unit) { if (!granted && !asked) request.launch(Manifest.permission.CAMERA) }
    // Leaving the app ends the scan, so the camera never runs in the
    // background. The next scan checks the permission again, which also
    // covers one granted in Settings meanwhile.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentCancel by rememberUpdatedState(onCancel)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) currentCancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column {
        SupportingText(rationale)
        Spacer(Modifier.height(16.dp))
        when {
            granted -> CameraPreview(accept, onResult)
            asked -> {
                Text(stringResource(R.string.camera_denied), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { request.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.camera_allow)) }
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.camera_settings)) }
            }
            else -> Text(stringResource(R.string.camera_requesting))
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun CameraPreview(accept: (String) -> Boolean, onResult: (String) -> Unit) {
    val context = LocalContext.current
    val choice = remember { QrCameraChoice.select(context.getSystemService(CameraManager::class.java)) }
    if (choice == null) {
        Text(stringResource(R.string.camera_unavailable))
        return
    }
    val currentAccept by rememberUpdatedState(accept)
    val currentResult by rememberUpdatedState(onResult)
    var failed by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    val session = remember { PreviewSession() }
    // The camera and its thread exist only between this effect and its
    // disposal. A result decoded after disposal is dropped on the main thread.
    DisposableEffect(choice) {
        val main = ContextCompat.getMainExecutor(context)
        val delivered = AtomicBoolean(false)
        val camera = QrCamera(context, choice,
            onFrame = { main.execute { if (!session.disposed) active = true } },
            onText = { text ->
                currentAccept(text) && delivered.compareAndSet(false, true).also { kept ->
                    if (kept) main.execute { if (!session.disposed) currentResult(text) }
                }
            },
            onError = { main.execute { if (!session.disposed) failed = true } })
        session.attach(camera)
        onDispose { session.dispose() }
    }
    // A stopped activity does not recompose, so leaving the scanner on
    // ON_STOP would not dispose it until the app returns. Close the camera here.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) session.dispose() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (failed) {
        Text(stringResource(R.string.camera_unavailable))
        return
    }
    val scanning = stringResource(if (active) R.string.camera_scanning else R.string.camera_starting)
    val description = stringResource(R.string.camera_preview)
    // Portrait shows the landscape sensor frame upright, so the box takes its
    // rotated aspect ratio.
    AndroidView(
        factory = { viewContext ->
            TextureView(viewContext).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = session.surface(texture)
                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        session.dispose()
                        return true
                    }
                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
            .aspectRatio(choice.size.height.toFloat() / choice.size.width)
            .semantics {
                contentDescription = description
                stateDescription = scanning
            },
    )
}

/**
 * Joins the camera, created by an effect, with the preview surface, which
 * arrives on its own schedule. Main thread only. The camera opens once both
 * exist and closes once, on disposal or when the surface goes away.
 */
private class PreviewSession {
    var disposed = false
        private set
    private var camera: QrCamera? = null
    private var texture: SurfaceTexture? = null

    fun attach(created: QrCamera) {
        if (disposed) { created.close(); return }
        camera = created
        texture?.let(created::open)
    }

    fun surface(available: SurfaceTexture) {
        if (disposed) return
        texture = available
        camera?.open(available)
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        camera?.close()
        camera = null
        texture = null
    }
}
