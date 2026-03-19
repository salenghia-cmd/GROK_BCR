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
        private const val DEFAULT_CALL_SAMPLE_RATE = 8000
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
        private val requestedSampleRate: Int,
        private val requestedChannels: Int,
    ) : InboundAudioInjector {

        private val targetSampleRate = sanitizeCallSampleRate(requestedSampleRate)
        private val targetChannels = 1

        private var callInjectTrack: AudioTrack? = null

        @Volatile
        private var usingFallback = false

        @Volatile
        private var warnedFallback = false

        private var previousAudioMode: Int? = null
        private var previousSpeakerphoneOn: Boolean? = null
        private var routePrepared = false

        override fun start() {
            prepareVoiceRoute()

            val track = tryCreateCallInjectionTrack()
            if (track != null) {
                callInjectTrack = track
                usingFallback = false
                Log.i(
                    TAG,
                    "VendorIncallInjector started -> hidden PSTN call injection active ${targetSampleRate}Hz/${targetChannels}ch (requested=${requestedSampleRate}Hz/${requestedChannels}ch)"
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

            val normalized = normalizeForCallInjection(bytes)
            if (normalized.isEmpty()) return

            val track = callInjectTrack
            if (track != null && !usingFallback) {
                try {
                    var offset = 0
                    while (offset < normalized.size) {
                        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            track.write(normalized, offset, normalized.size - offset, AudioTrack.WRITE_BLOCKING)
                        } else {
                            track.write(normalized, offset, normalized.size - offset)
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
            playbackThread.enqueue(normalized)
        }

        override fun stop() {
            releaseTrackQuietly()
            restoreVoiceRoute()
            Log.i(TAG, "VendorIncallInjector stopped")
        }

        private fun prepareVoiceRoute() {
            if (routePrepared) return

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            previousAudioMode = audioManager.mode
            previousSpeakerphoneOn = try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn
            } catch (_: Throwable) {
                false
            }

            try {
                val targetMode = when {
                    audioManager.mode == AudioManager.MODE_IN_CALL -> AudioManager.MODE_IN_CALL
                    previousAudioMode == AudioManager.MODE_IN_CALL -> AudioManager.MODE_IN_CALL
                    else -> AudioManager.MODE_IN_COMMUNICATION
                }

                if (audioManager.mode != targetMode) {
                    audioManager.mode = targetMode
                }

                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                routePrepared = true

                Log.i(
                    TAG,
                    "VendorIncallInjector route prepared mode=$targetMode speakerphone=false"
                )
            } catch (t: Throwable) {
                Log.w(TAG, "VendorIncallInjector route prepare failed", t)
            }
        }

        private fun restoreVoiceRoute() {
            if (!routePrepared) return

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            try {
                previousSpeakerphoneOn?.let {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = it
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VendorIncallInjector restore speakerphone failed", t)
            }

            try {
                val previousMode = previousAudioMode
                if (previousMode != null && audioManager.mode != previousMode) {
                    audioManager.mode = previousMode
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VendorIncallInjector restore mode failed", t)
            }

            routePrepared = false
        }

        @SuppressLint("SoonBlockedPrivateApi")
        private fun tryCreateCallInjectionTrack(): AudioTrack? {
            return try {
                val minBuffer = max(
                    AudioTrack.getMinBufferSize(
                        targetSampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    4096,
                )

                val builder = AudioTrack.Builder()

                try {
                    val setContextMethod = AudioTrack.Builder::class.java.getMethod("setContext", Context::class.java)
                    setContextMethod.invoke(builder, context)
                } catch (t: Throwable) {
                    Log.i(TAG, "AudioTrack.Builder.setContext unavailable, continue without it")
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
                        .setSampleRate(targetSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                builder.setTransferMode(AudioTrack.MODE_STREAM)
                builder.setBufferSizeInBytes(minBuffer * 4)

                val audioManagerClass = Class.forName("android.media.AudioManager")
                val callRedirectPstn = audioManagerClass.getDeclaredField("CALL_REDIRECT_PSTN")
                    .getInt(null)

                val setCallRedirectionMode = AudioTrack.Builder::class.java.getDeclaredMethod(
                    "setCallRedirectionMode",
                    Int::class.javaPrimitiveType,
                )
                setCallRedirectionMode.isAccessible = true
                setCallRedirectionMode.invoke(builder, callRedirectPstn)

                val track = builder.build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    try {
                        track.release()
                    } catch (_: Throwable) {
                    }
                    Log.w(TAG, "Hidden PSTN inject AudioTrack not initialized")
                    return null
                }

                try {
                    track.setVolume(1.0f)
                } catch (_: Throwable) {
                }
                track.play()
                track
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "Hidden PSTN inject AudioTrack unavailable. Cần kiểm tra app đã được cấp/allowlist các quyền privileged: MODIFY_AUDIO_ROUTING, CALL_AUDIO_INTERCEPTION, MODIFY_PHONE_STATE",
                    t,
                )
                null
            }
        }

        private fun normalizeForCallInjection(bytes: ByteArray): ByteArray {
            if (bytes.isEmpty()) return ByteArray(0)
            val safeChannels = max(1, requestedChannels)

            var pcm16 = if (safeChannels == 1) {
                if (bytes.size and 1 == 0) bytes else bytes.copyOf(bytes.size - 1)
            } else {
                downmixToMono16Le(bytes, safeChannels)
            }

            if (pcm16.isEmpty()) return ByteArray(0)

            if (requestedSampleRate != targetSampleRate) {
                pcm16 = resampleMonoPcm16Le(pcm16, requestedSampleRate, targetSampleRate)
            }

            return pcm16
        }

        private fun downmixToMono16Le(bytes: ByteArray, inputChannels: Int): ByteArray {
            val frameCount = bytes.size / 2 / inputChannels
            if (frameCount <= 0) return ByteArray(0)

            val out = ByteArray(frameCount * 2)
            var inIndex = 0
            var outIndex = 0

            repeat(frameCount) {
                var sum = 0
                repeat(inputChannels) {
                    val lo = bytes[inIndex].toInt() and 0xff
                    val hi = bytes[inIndex + 1].toInt()
                    val sample = ((hi shl 8) or lo).toShort().toInt()
                    sum += sample
                    inIndex += 2
                }

                val mono = (sum / inputChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[outIndex] = (mono and 0xff).toByte()
                out[outIndex + 1] = ((mono shr 8) and 0xff).toByte()
                outIndex += 2
            }

            return out
        }

        private fun resampleMonoPcm16Le(bytes: ByteArray, inputRate: Int, outputRate: Int): ByteArray {
            if (bytes.isEmpty() || inputRate <= 0 || outputRate <= 0 || inputRate == outputRate) {
                return bytes
            }

            val inSamples = bytes.size / 2
            if (inSamples <= 0) return ByteArray(0)

            val input = FloatArray(inSamples)
            var index = 0
            repeat(inSamples) { i ->
                val lo = bytes[index].toInt() and 0xff
                val hi = bytes[index + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt()
                input[i] = sample / 32768f
                index += 2
            }

            val ratio = inputRate.toDouble() / outputRate.toDouble()
            val outSamples = max(1, kotlin.math.round(inSamples / ratio).toInt())
            val out = ByteArray(outSamples * 2)
            var outIndex = 0

            repeat(outSamples) { i ->
                val position = i * ratio
                val left = position.toInt().coerceIn(0, inSamples - 1)
                val right = (left + 1).coerceAtMost(inSamples - 1)
                val frac = position - left
                val sample = (input[left] * (1.0 - frac) + input[right] * frac)
                    .toFloat()
                    .coerceIn(-1f, 1f)
                val pcm16 = if (sample < 0f) {
                    (sample * 32768f).toInt()
                } else {
                    (sample * 32767f).toInt()
                }.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())

                out[outIndex] = (pcm16 and 0xff).toByte()
                out[outIndex + 1] = ((pcm16 shr 8) and 0xff).toByte()
                outIndex += 2
            }

            return out
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

            private fun sanitizeCallSampleRate(sampleRate: Int): Int {
                return when {
                    sampleRate <= 0 -> DEFAULT_CALL_SAMPLE_RATE
                    sampleRate <= 8000 -> 8000
                    sampleRate <= 16000 -> 16000
                    else -> 16000
                }
            }
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
                requestedSampleRate = sampleRate,
                requestedChannels = channels,
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
