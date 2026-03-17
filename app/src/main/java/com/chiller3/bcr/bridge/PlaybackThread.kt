package com.chiller3.bcr.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

class PlaybackThread(
    private val context: Context,
    private val sampleRate: Int,
    private val channels: Int,
    private val routeMode: String = "speaker",
) : Thread("RealtimePlaybackThread") {

    companion object {
        private const val TAG = "PlaybackThread"
        private const val QUEUE_CAPACITY = 64
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
        val normalizedRoute = routeMode.trim().lowercase()

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

        var forcedSpeakerphone = false
        var communicationDeviceSet = false
        var selectedDevice: AudioDeviceInfo? = null

        try {
            val routeResult = prepareRoute(audioManager, normalizedRoute)
            forcedSpeakerphone = routeResult.forcedSpeakerphone
            communicationDeviceSet = routeResult.communicationDeviceSet
            selectedDevice = routeResult.selectedDevice

            val track = AudioTrack(
                AudioAttributes.Builder()
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

            try {
                track.play()
                Log.i(
                    TAG,
                    "PlaybackThread started: ${sampleRate}Hz/${channels}ch route=$normalizedRoute device=${selectedDevice?.productName ?: "none"}"
                )

                while (running) {
                    val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue

                    var offset = 0
                    while (running && offset < chunk.size) {
                        val written = track.write(chunk, offset, chunk.size - offset)
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
                    forcedSpeakerphone = forcedSpeakerphone,
                    communicationDeviceSet = communicationDeviceSet,
                )

                Log.i(TAG, "PlaybackThread stopped")
            }
        } catch (t: Throwable) {
            restoreRoute(
                audioManager = audioManager,
                forcedSpeakerphone = forcedSpeakerphone,
                communicationDeviceSet = communicationDeviceSet,
            )
            Log.e(TAG, "PlaybackThread route/setup failure", t)
        }
    }

    private data class RoutePrepareResult(
        val forcedSpeakerphone: Boolean,
        val communicationDeviceSet: Boolean,
        val selectedDevice: AudioDeviceInfo?,
    )

    private fun prepareRoute(
        audioManager: AudioManager,
        normalizedRoute: String,
    ): RoutePrepareResult {
        var forcedSpeakerphone = false
        var communicationDeviceSet = false
        var selectedDevice: AudioDeviceInfo? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val devices = audioManager.availableCommunicationDevices
                val wantedType = when (normalizedRoute) {
                    "earpiece" -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    "speaker", "speaker_loopback" -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    else -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }

                selectedDevice = devices.firstOrNull { it.type == wantedType }

                if (selectedDevice != null) {
                    communicationDeviceSet = audioManager.setCommunicationDevice(selectedDevice!!)
                    Log.i(
                        TAG,
                        "setCommunicationDevice route=$normalizedRoute success=$communicationDeviceSet device=${selectedDevice?.productName}"
                    )
                } else {
                    Log.w(TAG, "No matching communication device for route=$normalizedRoute")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "setCommunicationDevice failed", t)
            }
        }

        if (!communicationDeviceSet) {
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

                    else -> {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        forcedSpeakerphone = true
                        Log.i(TAG, "Unknown route=$normalizedRoute -> fallback speakerphone ON")
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
        )
    }

    private fun restoreRoute(
        audioManager: AudioManager,
        forcedSpeakerphone: Boolean,
        communicationDeviceSet: Boolean,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && communicationDeviceSet) {
                audioManager.clearCommunicationDevice()
                Log.i(TAG, "clearCommunicationDevice done")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clearCommunicationDevice failed", t)
        }

        try {
            if (forcedSpeakerphone) {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
                Log.i(TAG, "Speakerphone restored OFF")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Restore speakerphone failed", t)
        }
    }
}