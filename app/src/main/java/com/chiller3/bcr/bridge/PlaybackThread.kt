package com.chiller3.bcr.bridge

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

class PlaybackThread(
    private val sampleRate: Int,
    private val channels: Int,
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
            Log.i(TAG, "PlaybackThread started: ${sampleRate}Hz/${channels}ch")
            while (running) {
                val chunk = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                var offset = 0
                while (running && offset < chunk.size) {
                    val written = track.write(chunk, offset, chunk.size - offset)
                    if (written <= 0) break
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
            Log.i(TAG, "PlaybackThread stopped")
        }
    }
}