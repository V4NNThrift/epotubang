package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow

object ScreenshotServiceState {
    val isRunning = MutableStateFlow(false)
    val totalCaptured = MutableStateFlow(0)
    val lastCapturedPath = MutableStateFlow<String?>(null)
}
