package app.skerry.ui.sync.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import app.skerry.ui.generated.resources.Res
import app.skerry.ui.generated.resources.qr_cancel
import app.skerry.ui.generated.resources.qr_permission_needed
import app.skerry.ui.generated.resources.qr_scan_hint
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Android: camera QR scanner is available (CameraX + on-device ML Kit). */
actual val qrScannerAvailable: Boolean = true

/**
 * Full-screen camera QR scanner: requests permission, shows a CameraX preview, and runs frames
 * through ML Kit barcode scanning (on-device). The first decoded QR is delivered to [onResult] exactly
 * once ([AtomicBoolean] guard against a burst of frames); denied permission or Cancel calls [onCancel].
 * Raw QR text is decoded by the caller ([PairingPayload.decode]).
 */
@Composable
actual fun QrScannerScreen(onResult: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (!granted) onCancel() // no camera access; fall back to manual entry
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraScanPreview(onDetected = onResult)
            Text(
                stringResource(Res.string.qr_scan_hint),
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp, start = 28.dp, end = 28.dp),
            )
        } else {
            Text(
                stringResource(Res.string.qr_permission_needed),
                color = Color.White, fontSize = 14.sp,
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
            )
        }
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp)
                .clip(RoundedCornerShape(9.dp)).background(Color(0xFF0E2230)).clickable(onClick = onCancel)
                .padding(horizontal = 22.dp, vertical = 11.dp),
        ) {
            Text(stringResource(Res.string.qr_cancel), color = Color(0xFF2BBDEE), fontSize = 14.sp)
        }
    }
}

@Composable
private fun CameraScanPreview(onDetected: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Guard: the analyzer delivers frames in a burst, so without this a single QR would trigger
    // onDetected dozens of times. The first success closes the scanner; later frames are ignored.
    val handled = remember { AtomicBoolean(false) }
    // scanner and provider are held to release them in onDispose: the camera is bound to the
    // Activity's lifecycle (LocalLifecycleOwner), not this composable, so without explicit unbinding
    // it would keep running after leaving the scanner screen.
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val providerHolder = remember { AtomicReference<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            providerHolder.get()?.unbindAll()
            scanner.close() // ML Kit's native models (JNI) must be released explicitly or they outlive the process lifetime
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                providerHolder.set(provider)
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                    scanFrame(proxy, scanner, handled, onDetected)
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

@OptIn(ExperimentalGetImage::class)
private fun scanFrame(
    proxy: ImageProxy,
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    handled: AtomicBoolean,
    onDetected: (String) -> Unit,
) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    // If fromMediaImage/process throws synchronously, addOnCompleteListener never runs to close proxy,
    // and STRATEGY_KEEP_ONLY_LATEST stalls on it. try/catch ensures proxy is closed in that case too;
    // on success, closing happens in addOnCompleteListener instead.
    try {
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { it.rawValue != null }?.rawValue
                // compareAndSet ensures only the first decoded code reaches onDetected (on the main
                // thread, where ML Kit's success listener runs by default).
                if (raw != null && handled.compareAndSet(false, true)) onDetected(raw)
            }
            .addOnCompleteListener { proxy.close() }
    } catch (e: Exception) {
        proxy.close()
    }
}
