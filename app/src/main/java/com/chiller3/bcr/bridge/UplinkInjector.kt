package com.chiller3.bcr.bridge

import android.util.Log

class UplinkInjector(
    private val playbackThread: PlaybackThread,
    private val injectMode: String = "noop",
) {
    companion object {
        private const val TAG = "UplinkInjector"
    }

    fun handleInboundPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return

        when (injectMode) {
            "local", "noop" -> playbackThread.enqueue(bytes)
            else -> {
                Log.w(TAG, "injectMode=$injectMode chưa implement, fallback local playback")
                playbackThread.enqueue(bytes)
            }
        }
    }
}