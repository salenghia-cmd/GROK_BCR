package com.chiller3.bcr.bridge

import android.util.Log

class UplinkInjector(
    private val playbackThread: PlaybackThread,
    private val injectMode: String = "speaker_loopback",
) {
    companion object {
        private const val TAG = "UplinkInjector"
    }

    private interface InboundAudioInjector {
        fun start()
        fun writePcm(bytes: ByteArray)
        fun stop()
    }

    private class NoopInjector : InboundAudioInjector {
        override fun start() {
            Log.i(TAG, "NoopInjector started")
        }

        override fun writePcm(bytes: ByteArray) {
            // drop
        }

        override fun stop() {
            Log.i(TAG, "NoopInjector stopped")
        }

        companion object {
            private const val TAG = "NoopInjector"
        }
    }

    private class SpeakerLoopbackInjector(
        private val playbackThread: PlaybackThread,
    ) : InboundAudioInjector {
        override fun start() {
            Log.i(TAG, "SpeakerLoopbackInjector started")
        }

        override fun writePcm(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            playbackThread.enqueue(bytes)
        }

        override fun stop() {
            Log.i(TAG, "SpeakerLoopbackInjector stopped")
        }

        companion object {
            private const val TAG = "SpeakerLoopbackInjector"
        }
    }

    /**
     * Chưa inject thật vào uplink call.
     * Tạm fallback sang playback local để không bị câm hoàn toàn.
     */
    private class VendorIncallInjector(
        private val playbackThread: PlaybackThread,
    ) : InboundAudioInjector {
        @Volatile
        private var warned = false

        override fun start() {
            Log.w(TAG, "VendorIncallInjector chưa implement thật, đang fallback local playback")
        }

        override fun writePcm(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            if (!warned) {
                warned = true
                Log.w(TAG, "vendor_incall chưa inject thật -> fallback local playback")
            }
            playbackThread.enqueue(bytes)
        }

        override fun stop() {
            Log.i(TAG, "VendorIncallInjector stopped")
        }

        companion object {
            private const val TAG = "VendorIncallInjector"
        }
    }

    private val impl: InboundAudioInjector by lazy {
        when (injectMode.trim().lowercase()) {
            "noop" -> {
                Log.w(TAG, "injectMode=noop -> sẽ drop audio từ web xuống handset")
                NoopInjector()
            }
            "local", "speaker", "speaker_loopback" -> SpeakerLoopbackInjector(playbackThread)
            "vendor_incall" -> VendorIncallInjector(playbackThread)
            else -> {
                Log.w(TAG, "injectMode=$injectMode không hợp lệ, fallback speaker_loopback")
                SpeakerLoopbackInjector(playbackThread)
            }
        }
    }

    fun start() {
        impl.start()
    }

    fun handleInboundPcm(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        impl.writePcm(bytes)
    }

    fun stop() {
        impl.stop()
    }
}