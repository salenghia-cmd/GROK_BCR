package com.chiller3.bcr.bridge

data class CallSessionInfo(
    val sessionId: String,
    val wsUrl: String,
    val sampleRate: Int,
    val channels: Int,
    val callId: String,
    val deviceId: String? = null,
    val playbackMode: String = "local",
    val injectMode: String = "noop",
    val startedAt: Long = System.currentTimeMillis(),
)