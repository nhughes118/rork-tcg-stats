package com.rork.tcgstatsandroid.ui.scan

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import com.rork.tcgstatsandroid.data.RecognizedLine
import java.util.concurrent.Executor

/** Runs ML Kit OCR on throttled camera frames and reports recognized lines. */
class TextAnalyzer(
    private val recognizer: TextRecognizer,
    private val callbackExecutor: Executor,
    private val onLines: (List<RecognizedLine>) -> Unit,
) : ImageAnalysis.Analyzer {
    private var lastRun = 0L

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(image: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        val media = image.image
        if (media == null || now - lastRun < 350) {
            image.close()
            return
        }
        lastRun = now
        val rotation = image.imageInfo.rotationDegrees
        val uprightHeight = (if (rotation % 180 == 0) image.height else image.width).toDouble().coerceAtLeast(1.0)
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            .addOnSuccessListener(callbackExecutor) { text ->
                val lines = text.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val cy = line.boundingBox?.exactCenterY()?.toDouble() ?: (uprightHeight / 2)
                        RecognizedLine(line.text, (1.0 - cy / uprightHeight).coerceIn(0.0, 1.0))
                    }
                }
                onLines(lines)
            }
            .addOnCompleteListener { image.close() }
    }
}
