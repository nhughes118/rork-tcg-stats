package com.rork.tcgstatsandroid.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rork.tcgstatsandroid.data.CardMatcher
import com.rork.tcgstatsandroid.data.RecognizedLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Debounces OCR matches into a stable detected card. */
class ScanViewModel : ViewModel() {
    private val _detectedKey = MutableStateFlow<String?>(null)
    val detectedKey: StateFlow<String?> = _detectedKey.asStateFlow()

    private val _isTorchOn = MutableStateFlow(false)
    val isTorchOn: StateFlow<Boolean> = _isTorchOn.asStateFlow()

    @Volatile
    var matcher: CardMatcher? = null

    private var candidateKey: String? = null
    private var candidateHits = 0
    private var missCount = 0

    /** Called from the camera analysis thread. */
    fun process(lines: List<RecognizedLine>) {
        val key = matcher?.match(lines)
        viewModelScope.launch { handle(key) }
    }

    fun toggleTorch() {
        _isTorchOn.value = !_isTorchOn.value
    }

    fun setTorch(on: Boolean) {
        _isTorchOn.value = on
    }

    fun clearDetection() {
        _detectedKey.value = null
        candidateKey = null
        candidateHits = 0
    }

    private fun handle(key: String?) {
        if (key == null) {
            missCount += 1
            if (missCount > 8) {
                candidateKey = null
                candidateHits = 0
            }
            return
        }
        missCount = 0
        if (key == candidateKey) candidateHits += 1 else {
            candidateKey = key
            candidateHits = 1
        }
        if (candidateHits >= 2 && _detectedKey.value != key) _detectedKey.value = key
    }
}
