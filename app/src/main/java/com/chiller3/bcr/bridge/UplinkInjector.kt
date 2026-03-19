package com.chiller3.bcr.bridge

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.lang.reflect.Method
import kotlin.math.max

class UplinkInjector(
    private val context: Context,
    private val playbackThread: PlaybackThread,
    private val sampleRate: Int,
    private val channels: Int,
    private val injectMode: String = "call_tx",
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

    private interface CallInjectionTarget {
        val stageName: String
        fun write(bytes: ByteArray)
        fun stop()
    }

    private class AudioTrackTarget(
        override val stageName: String,
        private val track: AudioTrack,
        private val onStop: (() -> Unit)? = null,
    ) : CallInjectionTarget {
        override fun write(bytes: ByteArray) {
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
        }

        override fun stop() {
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
            try {
                onStop?.invoke()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Inject thật vào call TX theo 2 tầng app-only:
     * 1) direct_attr: dựng AudioTrack thường nhưng gắn AudioAttributes#setForCallRedirection()
     *    (đây là nhánh gần với AOSP PSTN branch nhất, không cần CALL_REDIRECT_PSTN field).
     * 2) policy_mix: dựng AudioPolicy + AudioMix injector để có thêm một đường inject thật nữa nếu direct_attr fail.
     *
     * Không còn fallback local playback trong inject mode chính.
     * Local playback chỉ là monitor song song cho dt1 sau khi inject thật đã active.
     */
    private class CallTxInjector(
        private val context: Context,
        private val playbackThread: PlaybackThread,
        private val requestedSampleRate: Int,
        private val requestedChannels: Int,
    ) : InboundAudioInjector {
        private val targetSampleRate = sanitizeCallSampleRate(requestedSampleRate)
        private val targetChannels = 1

        private var previousAudioMode: Int? = null
        private var previousSpeakerphoneOn: Boolean? = null
        private var routePrepared = false

        private var nextCandidateIndex = 0
        private var activeTarget: CallInjectionTarget? = null
        private var warnedInactive = false

        override fun start() {
            prepareVoiceRoute()
            activeTarget = openNextTarget()
            if (activeTarget != null) {
                Log.i(
                    TAG,
                    "CallTxInjector started stage=${activeTarget?.stageName} ${targetSampleRate}Hz/${targetChannels}ch (requested=${requestedSampleRate}Hz/${requestedChannels}ch). localMonitor=enabled-when-inject-active"
                )
            } else {
                Log.e(
                    TAG,
                    "CallTxInjector start failed: không tạo được bất kỳ inject thật nào. web->dt2 sẽ KHÔNG nghe; tuyệt đối không fallback local playback"
                )
            }
        }

        override fun writePcm(bytes: ByteArray) {
            if (bytes.isEmpty()) return

            val normalized = normalizeForCallInjection(bytes)
            if (normalized.isEmpty()) return

            val target = activeTarget
            if (target == null) {
                warnInactiveOnce("drop inbound pcm vì inject thật chưa active")
                return
            }

            try {
                target.write(normalized)
                playbackThread.enqueue(normalized)
                return
            } catch (t: Throwable) {
                Log.e(TAG, "CallTxInjector write failed stage=${target.stageName}", t)
                target.stop()
                activeTarget = openNextTarget()
            }

            val switched = activeTarget
            if (switched != null) {
                Log.w(TAG, "CallTxInjector switched injector stage=${switched.stageName}")
                try {
                    switched.write(normalized)
                    playbackThread.enqueue(normalized)
                    return
                } catch (t: Throwable) {
                    Log.e(TAG, "CallTxInjector retry failed stage=${switched.stageName}", t)
                    switched.stop()
                    activeTarget = null
                }
            }

            warnInactiveOnce("drop inbound pcm vì mọi inject thật đều thất bại")
        }

        override fun stop() {
            activeTarget?.stop()
            activeTarget = null
            restoreVoiceRoute()
            Log.i(TAG, "CallTxInjector stopped")
        }

        private fun warnInactiveOnce(reason: String) {
            if (warnedInactive) return
            warnedInactive = true
            Log.e(
                TAG,
                "CallTxInjector inactive: $reason. Không có fallback local playback trong mode inject chính"
            )
        }

        private fun openNextTarget(): CallInjectionTarget? {
            while (nextCandidateIndex < 2) {
                val target = when (nextCandidateIndex++) {
                    0 -> openDirectAttrTarget()
                    1 -> openPolicyMixTarget()
                    else -> null
                }
                if (target != null) return target
            }
            return null
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
                Log.i(TAG, "CallTxInjector route prepared mode=$targetMode speakerphone=false")
            } catch (t: Throwable) {
                Log.w(TAG, "CallTxInjector route prepare failed", t)
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
                Log.w(TAG, "CallTxInjector restore speakerphone failed", t)
            }
            try {
                previousAudioMode?.let {
                    if (audioManager.mode != it) {
                        audioManager.mode = it
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "CallTxInjector restore mode failed", t)
            }
            routePrepared = false
        }

        @SuppressLint("SoonBlockedPrivateApi")
        private fun openDirectAttrTarget(): CallInjectionTarget? {
            Log.i(TAG, "CallTxInjector stage=direct_attr start")
            return try {
                val attributesBuilder = AudioAttributes.Builder()
                    .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

                if (!invokeHiddenNoArg(attributesBuilder, "setForCallRedirection")) {
                    Log.w(TAG, "CallTxInjector stage=direct_attr fail: setForCallRedirection unavailable/blocked")
                    return null
                }

                val trackBuilder = AudioTrack.Builder()
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        trackBuilder.setContext(context)
                    } catch (t: Throwable) {
                        Log.i(TAG, "CallTxInjector stage=direct_attr setContext unavailable; continue without it")
                    }
                }

                val minBuffer = max(
                    AudioTrack.getMinBufferSize(
                        targetSampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ),
                    4096,
                )

                trackBuilder.setAudioAttributes(attributesBuilder.build())
                trackBuilder.setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(targetSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                trackBuilder.setTransferMode(AudioTrack.MODE_STREAM)
                trackBuilder.setBufferSizeInBytes(minBuffer * 4)

                val track = trackBuilder.build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    try {
                        track.release()
                    } catch (_: Throwable) {
                    }
                    Log.w(TAG, "CallTxInjector stage=direct_attr fail: AudioTrack not initialized")
                    return null
                }

                try {
                    track.setVolume(1.0f)
                } catch (_: Throwable) {
                }
                track.play()
                Log.i(TAG, "CallTxInjector stage=direct_attr success")
                AudioTrackTarget(stageName = "direct_attr", track = track)
            } catch (t: Throwable) {
                Log.w(TAG, "CallTxInjector stage=direct_attr fail", t)
                null
            }
        }

        @SuppressLint("SoonBlockedPrivateApi")
        private fun openPolicyMixTarget(): CallInjectionTarget? {
            Log.i(TAG, "CallTxInjector stage=policy_mix start")
            return try {
                val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
                val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
                val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
                val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

                val captureAttrsBuilder = AudioAttributes.Builder()
                if (!invokeHiddenInt(captureAttrsBuilder, "setCapturePreset", MediaRecorder.AudioSource.VOICE_COMMUNICATION)) {
                    Log.w(TAG, "CallTxInjector stage=policy_mix fail: setCapturePreset unavailable/blocked")
                    return null
                }
                if (!invokeHiddenNoArg(captureAttrsBuilder, "setForCallRedirection")) {
                    Log.w(TAG, "CallTxInjector stage=policy_mix fail: setForCallRedirection unavailable/blocked")
                    return null
                }
                val captureAttrs = captureAttrsBuilder.build()

                val ruleBuilder = ruleBuilderClass.getDeclaredConstructor().newInstance()
                findMethod(ruleBuilderClass, "addMixRule", 2).invoke(
                    ruleBuilder,
                    readStaticInt(ruleClass, "RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET"),
                    captureAttrs,
                )
                findMethod(ruleBuilderClass, "setTargetMixRole", 1).invoke(
                    ruleBuilder,
                    readStaticInt(ruleClass, "MIX_ROLE_INJECTOR"),
                )
                val mixRule = findMethod(ruleBuilderClass, "build", 0).invoke(ruleBuilder)

                val mixBuilder = mixBuilderClass.getDeclaredConstructor(ruleClass).newInstance(mixRule)
                findMethod(mixBuilderClass, "setFormat", 1).invoke(
                    mixBuilder,
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(targetSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                findMethod(mixBuilderClass, "setRouteFlags", 1).invoke(
                    mixBuilder,
                    readStaticInt(mixClass, "ROUTE_FLAG_LOOP_BACK"),
                )
                val audioMix = findMethod(mixBuilderClass, "build", 0).invoke(mixBuilder)

                val policyBuilder = policyBuilderClass.getDeclaredConstructor(Context::class.java).newInstance(context)
                findMethod(policyBuilderClass, "addMix", 1).invoke(policyBuilder, audioMix)
                val policy = findMethod(policyBuilderClass, "build", 0).invoke(policyBuilder)

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val registerAny = findMethod(audioManager.javaClass, "registerAudioPolicy", 1)
                    .invoke(audioManager, policy)
                val registerResult = (registerAny as? Number)?.toInt() ?: AudioManager.ERROR
                if (registerResult != AudioManager.SUCCESS) {
                    Log.w(TAG, "CallTxInjector stage=policy_mix fail: registerAudioPolicy result=$registerResult")
                    return null
                }

                val track = findMethod(policyClass, "createAudioTrackSource", 1)
                    .invoke(policy, audioMix) as? AudioTrack
                if (track == null) {
                    unregisterAudioPolicy(audioManager, policy)
                    Log.w(TAG, "CallTxInjector stage=policy_mix fail: createAudioTrackSource returned null")
                    return null
                }
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    try {
                        track.release()
                    } catch (_: Throwable) {
                    }
                    unregisterAudioPolicy(audioManager, policy)
                    Log.w(TAG, "CallTxInjector stage=policy_mix fail: AudioTrack not initialized")
                    return null
                }

                try {
                    track.setVolume(1.0f)
                } catch (_: Throwable) {
                }
                track.play()
                Log.i(TAG, "CallTxInjector stage=policy_mix success")
                AudioTrackTarget(
                    stageName = "policy_mix",
                    track = track,
                    onStop = { unregisterAudioPolicy(audioManager, policy) },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "CallTxInjector stage=policy_mix fail", t)
                null
            }
        }

        private fun unregisterAudioPolicy(audioManager: AudioManager, policy: Any) {
            try {
                findMethod(audioManager.javaClass, "unregisterAudioPolicyAsync", 1).invoke(audioManager, policy)
                return
            } catch (_: Throwable) {
            }
            try {
                findMethod(audioManager.javaClass, "unregisterAudioPolicy", 1).invoke(audioManager, policy)
            } catch (t: Throwable) {
                Log.w(TAG, "CallTxInjector policy unregister failed", t)
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

        private fun readStaticInt(clazz: Class<*>, fieldName: String): Int {
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true
            return field.getInt(null)
        }

        private fun findMethod(clazz: Class<*>, name: String, argCount: Int): Method {
            val method = (clazz.methods + clazz.declaredMethods)
                .firstOrNull { it.name == name && it.parameterTypes.size == argCount }
                ?: throw NoSuchMethodException("${clazz.name}#$name/$argCount")
            method.isAccessible = true
            return method
        }

        private fun invokeHiddenNoArg(target: Any, methodName: String): Boolean {
            return try {
                findMethod(target.javaClass, methodName, 0).invoke(target)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Hidden call failed: ${target.javaClass.simpleName}#$methodName", t)
                false
            }
        }

        private fun invokeHiddenInt(target: Any, methodName: String, value: Int): Boolean {
            return try {
                findMethod(target.javaClass, methodName, 1).invoke(target, value)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "Hidden call failed: ${target.javaClass.simpleName}#$methodName($value)", t)
                false
            }
        }

        companion object {
            private const val TAG = "CallTxInjector"

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
                Log.w(TAG, "injectMode=noop -> drop audio từ web xuống handset")
                NoopInjector()
            }
            "local", "speaker", "speaker_loopback" -> SpeakerLoopbackInjector(playbackThread)
            "call_tx", "vendor_incall", "follow_call", "call", "auto" -> CallTxInjector(
                context = context,
                playbackThread = playbackThread,
                requestedSampleRate = sampleRate,
                requestedChannels = channels,
            )
            else -> {
                Log.w(TAG, "injectMode=$injectMode không hợp lệ, dùng call_tx")
                CallTxInjector(
                    context = context,
                    playbackThread = playbackThread,
                    requestedSampleRate = sampleRate,
                    requestedChannels = channels,
                )
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
