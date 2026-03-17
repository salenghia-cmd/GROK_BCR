package com.chiller3.bcr.bridge

import android.util.Log

class UplinkInjector(
    private val playbackThread: PlaybackThread,
    private val injectMode: String = "noop",
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
            // no-op
        }

        override fun writePcm(bytes: ByteArray) {
            // drop all inbound pcm
        }

        override fun stop() {
            // no-op
        }
    }

    /**
     * Giai đoạn test nhanh:
     * web -> handset -> playback local
     *
     * Ở bước sau, PlaybackThread sẽ được nâng cấp để route ra speaker/earpiece đúng ý.
     * Hiện tại class này chỉ đẩy PCM sang PlaybackThread.
     */
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
     * Stub cho pha vendor_incall.
     *
     * Chưa inject thật vào uplink call.
     * Tạm thời fallback sang playback local để không bị im lặng khi test giữa chừng.
     *
     * Khi làm bước vendor thật:
     * - start(): mở route / mixer / path incall của vendor
     * - writePcm(): ghi PCM vào path vendor
     * - stop(): trả route về trạng thái cũ
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

            // Tạm fallback để giữ pipeline sống trong lúc chưa viết vendor path
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
            "noop" -> NoopInjector()

            // giữ tương thích mode cũ
            "local",
            "speaker_loopback" -> SpeakerLoopbackInjector(playbackThread)

            "vendor_incall" -> VendorIncallInjector(playbackThread)

            else -> {
                Log.w(
                    TAG,
                    "injectMode=$injectMode không hợp lệ, fallback speaker_loopback"
                )
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