package com.chiller3.bcr.bridge

import android.util.Log

class UplinkInjector(
    private val playbackThread: PlaybackThread,
    private val injectMode: String = "vendor_incall",
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
     * Với bản custom này, vendor_incall sẽ đẩy PCM vào AudioTrack STREAM_VOICE_CALL
     * của PlaybackThread và giữ mode/route phía telephony càng nguyên bản càng tốt.
     * Trên các máy hỗ trợ voice-call mixer path, đây là đường gần nhất để web -> dt2 nghe.
     */
    private class VendorIncallInjector(
        private val playbackThread: PlaybackThread,
    ) : InboundAudioInjector {
        override fun start() {
            Log.i(TAG, "VendorIncallInjector started -> call-path playback")
        }

        override fun writePcm(bytes: ByteArray) {
            if (bytes.isEmpty()) return
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
            "local", "speaker", "speaker_loopback", "follow_call", "call", "auto" -> SpeakerLoopbackInjector(playbackThread)
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