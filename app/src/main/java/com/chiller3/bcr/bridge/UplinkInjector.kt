package com.chiller3.bcr.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import kotlin.math.max

class UplinkInjector(
    private val context: Context,
    private val playbackThread: PlaybackThread,
    private val sampleRate: Int,
    private val channels: Int,
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
     * Best-effort inject thật vào uplink PSTN bằng hidden call-redirection AudioTrack.
     * Nếu máy / ROM / quyền không hỗ trợ thì fallback về local playback như trước.
     */
    private class VendorIncallInjector(
        private val context: Context,
        private val playbackThread: PlaybackThread,
        private val sampleRate: Int,
        private val channels: Int,
    ) : InboundAudioInjector {

        private var callInjectTrack: AudioTrack? = null
        @Volatile
        private var usingFallback = false
        @Volatile
        private var warnedFallback = false

        override fun start() {
            val track = tryCreateCallInjectionTrack()
            if (track != null) {
                callInjectTrack = track
                usingFallback = false
                Log.i(
                    TAG,
                    "VendorIncallInjector started -> hidden PSTN call injection active ${sampleRate}Hz/${channels}ch"
                )
            } else {
                usingFallback = true
                Log.w(
                    TAG,
                    "VendorIncallInjector cannot create PSTN inject track -> fallback local playback"
                )
            }
        }

        override fun writePcm(bytes: ByteArray) {
            if (bytes.isEmpty()) return

            val track = callInjectTrack
            if (track != null && !usingFallback) {
                try {
                    var offset = 0
                    while (offset < bytes.size) {
                        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                        } else {
                            track.write(bytes, offset, bytes.size - offset)
                        }

                        if (written <= 0) {
                            throw IllegalStateException("AudioTrack.write returned $written")
                        }

                        offset += written
                    }
                    return
                } catch (t: Throwable) {
                    Log.w(TAG, "Call injection write failed -> switch fallback local playback", t)
                    usingFallback = true
                    releaseTrackQuietly()
                }
            }

            if (!warnedFallback) {
                warnedFallback = true
                Log.w(TAG, "vendor_incall fallback local playback; dt2 có thể không nghe nếu ROM không support uplink inject")
            }
            playbackThread.enqueue(bytes)
        }

        override fun stop() {
            releaseTrackQuietly()
            Log.i(TAG, "VendorIncallInjector stopped")
        }

        @SuppressLint("SoonBlockedPrivateApi")
        private fun tryCreateCallInjectionTrack(): AudioTrack? {
            return try {
                val channelMask = if (channels >= 2) {
                    AudioFormat.CHANNEL_OUT_STEREO
                } else {
                    AudioFormat.CHANNEL_OUT_MONO
                }

                val minBuffer = max(
                    AudioTrack.getMinBufferSize(
                        sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    4096,
                )

                val builder = AudioTrack.Builder()

                try {
                    val setContextMethod =
                        AudioTrack.Builder::class.java.getMethod("setContext", Context::class.java)
                    setContextMethod.invoke(builder, context)
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioTrack.Builder.setContext unavailable", t)
                    return null
                }

                builder.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                builder.setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build()
                )
                builder.setTransferMode(AudioTrack.MODE_STREAM)
                builder.setBufferSizeInBytes(minBuffer * 4)

                val audioManagerClass = Class.forName("android.media.AudioManager")
                val callRedirectPstn = audioManagerClass.getDeclaredField("CALL_REDIRECT_PSTN")
                    .getInt(null)

                val setCallRedirectionMode = AudioTrack.Builder::class.java.getDeclaredMethod(
                    "setCallRedirectionMode",
                    Int::class.javaPrimitiveType
                )
                setCallRedirectionMode.isAccessible = true
                setCallRedirectionMode.invoke(builder, callRedirectPstn)

                val track = builder.build()
                track.play()
                track
            } catch (t: Throwable) {
                Log.w(TAG, "Hidden PSTN inject AudioTrack unavailable", t)
                null
            }
        }

        private fun releaseTrackQuietly() {
            val track = callInjectTrack ?: return
            callInjectTrack = null

            try {
                track.pause()
            } catch (_: Throwable) {
            }
            try {
                track.flush()
            } catch (_: Throwable) {
            }
            try {
                track.stop()
            } catch (_: Throwable) {
            }
            try {
                track.release()
            } catch (_: Throwable) {
            }
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
            "local", "speaker", "speaker_loopback", "follow_call", "call", "auto" ->
                SpeakerLoopbackInjector(playbackThread)
            "vendor_incall" -> VendorIncallInjector(
                context = context,
                playbackThread = playbackThread,
                sampleRate = sampleRate,
                channels = channels,
            )
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
