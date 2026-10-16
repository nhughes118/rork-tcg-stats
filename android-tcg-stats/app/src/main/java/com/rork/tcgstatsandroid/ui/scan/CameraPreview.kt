package com.rork.tcgstatsandroid.ui.scan

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.rork.tcgstatsandroid.data.RecognizedLine
import java.util.concurrent.Executors

enum class CameraStatus { Starting, Ready, NoDevice, Failed }

/** CameraX preview with ML Kit OCR analysis bound to the current lifecycle. */
@Composable
fun CameraPreview(
    torchEnabled: Boolean,
    onLines: (List<RecognizedLine>) -> Unit,
    onStatus: (status: CameraStatus, hasTorch: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLines by rememberUpdatedState(onLines)
    val currentOnStatus by rememberUpdatedState(onStatus)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) {
                currentOnStatus(CameraStatus.Failed, false)
                return@addListener
            }
            provider = p
            val selector = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
                .firstOrNull { runCatching { p.hasCamera(it) }.getOrDefault(false) }
            if (selector == null) {
                currentOnStatus(CameraStatus.NoDevice, false)
                return@addListener
            }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, TextAnalyzer(recognizer, analysisExecutor) { lines -> currentOnLines(lines) }) }
            try {
                p.unbindAll()
                val bound = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                camera = bound
                currentOnStatus(CameraStatus.Ready, bound.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                Log.w("TCGStats", "Camera bind failed: ${e.javaClass.simpleName}")
                currentOnStatus(CameraStatus.Failed, false)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            camera = null
            provider?.unbindAll()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recognizer.close()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled, camera) {
        camera?.let { c -> if (c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(torchEnabled) }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
