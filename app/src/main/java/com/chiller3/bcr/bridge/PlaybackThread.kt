package com.chiller3.bcr.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

class PlaybackThread(
    private val context: Context,
    private val sampleRate: Int,
    private val channels: Int,
    private val routeMode: String = "bluetooth_sco",
) : Thread("RealtimePlaybackThread") {

    companion object {
        private const val TAG = "PlaybackThread"
        private const val QUEUE_CAPACITY = 96
        private const val SCO_WARMUP_MS = 350L
    }

    @Volatile
    private var running = true

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    fun enqueue(bytes: ByteArray) {
        if (!running || bytes.isEmpty()) return
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    fun shutdown() {
        running = false
        interrupt()
    }

    override fun run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val normalizedRoute = normalizeRouteMode(routeMode)
        val channelMask = AudioFormat.CHANNEL_OUT_MONO

        val minBuffer = max(
            AudioTrack.getMinBufferSize(
                sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            4096,
        )

        val previousAudioMode = audioManager.mode
        val previousSpeakerphoneOn = try {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        } catch (_: Throwable) {
            false
        }
        val previousBluetoothScoOn = try {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn
        } catch (_: Throwable) {
            false
        }

        var routeState = RoutePrepareResult()

        try {
            routeState = prepareRoute(
                audioManager = audioManager,
                normalizedRoute = normalizedRoute,
                previousAudioMode = previousAudioMode,
            )

            val streamType = resolveLegacyStreamType(normalizedRoute)
            val track = AudioTrack(
                AudioAttributes.Builder()
                    .setLegacyStreamType(streamType)
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build(),
                minBuffer * 4,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )

            if (routeState.selectedDevice != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val preferredOk = track.setPreferredDevice(routeState.selectedDevice)
                    Log.i(
                        TAG,
                        "AudioTrack.setPreferredDevice route=$normalizedRoute success=$preferredOk device=${routeState.selectedDevice?.productName}"
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioTrack.setPreferredDevice failed", t)
                }
            }

            try {
                track.setVolume(1.0f)
            } catch (_: Throwable) {
            }

            try {
                if (routeState.bluetoothScoStarted) {
                    SystemClock.sleep(SCO_WARMUP_MS)
                } else {
                    SystemClock.sleep(120)
                }

                track.play()
                Log.i(
                    TAG,
                    "PlaybackThread started: ${sampleRate}Hz/in=${channels}ch/out=1ch route=$normalizedRoute stream=${streamTypeName(streamType)} device=${routeState.selectedDevice?.productName ?: "none"} sco=${routeState.bluetoothScoStarted}"
                )

                while (running) {
                    val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    val normalizedChunk = normalizeForVoicePlayback(chunk)
                    if (normalizedChunk.isEmpty()) continue

                    var offset = 0
                    while (running && offset < normalizedChunk.size) {
                        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            track.write(
                                normalizedChunk,
                                offset,
                                normalizedChunk.size - offset,
                                AudioTrack.WRITE_BLOCKING,
                            )
                        } else {
                            track.write(normalizedChunk, offset, normalizedChunk.size - offset)
                        }

                        if (written <= 0) {
                            Log.w(TAG, "AudioTrack.write returned $written")
                            break
                        }
                        offset += written
                    }
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "PlaybackThread interrupted")
            } catch (t: Throwable) {
                Log.e(TAG, "PlaybackThread failure", t)
            } finally {
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

                track.release()
                queue.clear()

                restoreRoute(
                    audioManager = audioManager,
                    previousAudioMode = previousAudioMode,
                    previousSpeakerphoneOn = previousSpeakerphoneOn,
                    previousBluetoothScoOn = previousBluetoothScoOn,
                    routeState = routeState,
                )

                Log.i(TAG, "PlaybackThread stopped")
            }
        } catch (t: Throwable) {
            restoreRoute(
                audioManager = audioManager,
                previousAudioMode = previousAudioMode,
                previousSpeakerphoneOn = previousSpeakerphoneOn,
                previousBluetoothScoOn = previousBluetoothScoOn,
                routeState = routeState,
            )
            Log.e(TAG, "PlaybackThread route/setup failure", t)
        }
    }

    private data class RoutePrepareResult(
        val forcedSpeakerphone: Boolean = false,
        val communicationDeviceSet: Boolean = false,
        val selectedDevice: AudioDeviceInfo? = null,
        val bluetoothScoStarted: Boolean = false,
    )

    private fun prepareRoute(
        audioManager: AudioManager,
        normalizedRoute: String,
        previousAudioMode: Int,
    ): RoutePrepareResult {
        var forcedSpeakerphone = false
        var communicationDeviceSet = false
        var selectedDevice: AudioDeviceInfo? = null
        var bluetoothScoStarted = false

        try {
            val targetMode = when {
                previousAudioMode == AudioManager.MODE_IN_CALL -> AudioManager.MODE_IN_CALL
                audioManager.mode == AudioManager.MODE_IN_CALL -> AudioManager.MODE_IN_CALL
                else -> AudioManager.MODE_IN_COMMUNICATION
            }

            if (audioManager.mode != targetMode) {
                audioManager.mode = targetMode
                Log.i(
                    TAG,
                    "Audio mode -> ${modeToString(targetMode)} (was=${modeToString(previousAudioMode)}) route=$normalizedRoute"
                )
            } else {
                Log.i(
                    TAG,
                    "Audio mode giữ nguyên ${modeToString(targetMode)} route=$normalizedRoute"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to switch audio mode", t)
        }

        val wantsBluetoothSco = isBluetoothScoRoute(normalizedRoute)
        val shouldForceRoute = wantsBluetoothSco || normalizedRoute in setOf("speaker", "speaker_loopback", "earpiece")

        if (wantsBluetoothSco) {
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            } catch (_: Throwable) {
            }

            try {
                @Suppress("DEPRECATION")
                audioManager.setBluetoothScoOn(true)
            } catch (t: Throwable) {
                Log.w(TAG, "setBluetoothScoOn(true) failed", t)
            }

            try {
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                bluetoothScoStarted = true
                Log.i(TAG, "startBluetoothSco requested")
            } catch (t: Throwable) {
                Log.w(TAG, "startBluetoothSco failed", t)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && shouldForceRoute) {
            try {
                val devices = audioManager.availableCommunicationDevices
                val wantedType = when {
                    wantsBluetoothSco -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    normalizedRoute == "earpiece" -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    normalizedRoute in setOf("speaker", "speaker_loopback") -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    else -> null
                }

                if (wantedType != null) {
                    selectedDevice = devices.firstOrNull { it.type == wantedType }
                }

                if (selectedDevice != null) {
                    communicationDeviceSet = audioManager.setCommunicationDevice(selectedDevice!!)
                    Log.i(
                        TAG,
                        "setCommunicationDevice route=$normalizedRoute success=$communicationDeviceSet device=${selectedDevice?.productName}"
                    )
                } else if (wantsBluetoothSco) {
                    Log.w(TAG, "No TYPE_BLUETOOTH_SCO communication device found; keep trying legacy SCO route")
                } else {
                    Log.w(TAG, "No matching communication device for route=$normalizedRoute")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "setCommunicationDevice failed", t)
            }
        } else if (!shouldForceRoute) {
            Log.i(TAG, "Route=$normalizedRoute -> follow current in-call route, no forced device")
        }

        if (!communicationDeviceSet && !wantsBluetoothSco && shouldForceRoute) {
            try {
                when (normalizedRoute) {
                    "speaker", "speaker_loopback" -> {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        forcedSpeakerphone = true
                        Log.i(TAG, "Fallback route -> speakerphone ON")
                    }

                    "earpiece" -> {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = false
                        forcedSpeakerphone = false
                        Log.i(TAG, "Fallback route -> speakerphone OFF (earpiece)")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Speakerphone fallback failed", t)
            }
        }

        return RoutePrepareResult(
            forcedSpeakerphone = forcedSpeakerphone,
            communicationDeviceSet = communicationDeviceSet,
            selectedDevice = selectedDevice,
            bluetoothScoStarted = bluetoothScoStarted,
        )
    }

    private fun normalizeForVoicePlayback(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return ByteArray(0)
        val safeChannels = max(1, channels)
        if (safeChannels == 1) {
            return if (bytes.size and 1 == 0) bytes else bytes.copyOf(bytes.size - 1)
        }

        val frameCount = bytes.size / 2 / safeChannels
        if (frameCount <= 0) return ByteArray(0)

        val out = ByteArray(frameCount * 2)
        var inIndex = 0
        var outIndex = 0

        repeat(frameCount) {
            var sum = 0
            repeat(safeChannels) {
                val lo = bytes[inIndex].toInt() and 0xff
                val hi = bytes[inIndex + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toInt()
                sum += sample
                inIndex += 2
            }

            val mono = (sum / safeChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[outIndex] = (mono and 0xff).toByte()
            out[outIndex + 1] = ((mono shr 8) and 0xff).toByte()
            outIndex += 2
        }

        return out
    }

    private fun normalizeRouteMode(raw: String): String {
        return when (raw.trim().lowercase()) {
            "bluetooth_sco", "bt_sco", "sco", "hfp", "hfp_sco" -> "bluetooth_sco"
            "speaker", "speaker_loopback" -> raw.trim().lowercase()
            "follow_call" -> "follow_call"
            "earpiece", "local", "call", "auto", "" -> "earpiece"
            else -> "bluetooth_sco"
        }
    }

    private fun isBluetoothScoRoute(route: String): Boolean = route == "bluetooth_sco"

    private fun resolveLegacyStreamType(normalizedRoute: String): Int {
        if (!isBluetoothScoRoute(normalizedRoute)) {
            return AudioManager.STREAM_VOICE_CALL
        }

        return try {
            AudioManager::class.java.getDeclaredField("STREAM_BLUETOOTH_SCO").getInt(null)
        } catch (t: Throwable) {
            Log.w(TAG, "STREAM_BLUETOOTH_SCO unavailable, fallback STREAM_VOICE_CALL", t)
            AudioManager.STREAM_VOICE_CALL
        }
    }

    private fun streamTypeName(streamType: Int): String {
        return when (streamType) {
            AudioManager.STREAM_VOICE_CALL -> "STREAM_VOICE_CALL"
            else -> {
                val sco = try {
                    AudioManager::class.java.getDeclaredField("STREAM_BLUETOOTH_SCO").getInt(null)
                } catch (_: Throwable) {
                    Int.MIN_VALUE
                }
                if (streamType == sco) "STREAM_BLUETOOTH_SCO" else "stream=$streamType"
            }
        }
    }

    private fun modeToString(mode: Int): String {
        return when (mode) {
            AudioManager.MODE_IN_CALL -> "MODE_IN_CALL"
            AudioManager.MODE_IN_COMMUNICATION -> "MODE_IN_COMMUNICATION"
            AudioManager.MODE_NORMAL -> "MODE_NORMAL"
            AudioManager.MODE_RINGTONE -> "MODE_RINGTONE"
            else -> "mode=$mode"
        }
    }

    private fun restoreRoute(
        audioManager: AudioManager,
        previousAudioMode: Int,
        previousSpeakerphoneOn: Boolean,
        previousBluetoothScoOn: Boolean,
        routeState: RoutePrepareResult,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && routeState.communicationDeviceSet) {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "clearCommunicationDevice done")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clearCommunicationDevice failed", t)
        }

        if (routeState.bluetoothScoStarted || previousBluetoothScoOn) {
            try {
                @Suppress("DEPRECATION")
                audioManager.setBluetoothScoOn(previousBluetoothScoOn)
            } catch (t: Throwable) {
                Log.w(TAG, "Restore bluetoothScoOn failed", t)
            }

            try {
                if (!previousBluetoothScoOn) {
                    @Suppress("DEPRECATION")
                    audioManager.stopBluetoothSco()
                }
                Log.i(TAG, "Bluetooth SCO restored -> $previousBluetoothScoOn")
            } catch (t: Throwable) {
                Log.w(TAG, "Restore Bluetooth SCO failed", t)
            }
        }

        try {
            if (routeState.forcedSpeakerphone || previousSpeakerphoneOn) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
                Log.i(TAG, "Speakerphone restored -> $previousSpeakerphoneOn")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Restore speakerphone failed", t)
        }

        try {
            if (audioManager.mode != previousAudioMode) {
                audioManager.mode = previousAudioMode
                Log.i(TAG, "Audio mode restored -> ${modeToString(previousAudioMode)}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Restore audio mode failed", t)
        }
    }
}
