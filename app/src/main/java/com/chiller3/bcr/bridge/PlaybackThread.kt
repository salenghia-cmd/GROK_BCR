package com.chiller3.bcr.bridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max

class PlaybackThread(
    private val context: Context,
    private val sampleRate: Int,
    private val channels: Int,
    private val routeMode: String = "earpiece"
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

        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minBuffer = max(AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT), 4096)

        var track: AudioTrack? = null
        val previousMode = audioManager.mode
        val previousSpeakerphoneOn = audioManager.isSpeakerphoneOn

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            track = AudioTrack(
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
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            track.play()

            while (running) {
                val chunk = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                track.write(chunk, 0, chunk.size)
            }
        } catch (e: InterruptedException) {
            Log.i(TAG, "PlaybackThread interrupted")
        } catch (t: Throwable) {
            Log.e(TAG, "PlaybackThread error", t)
        } finally {
            try { track?.stop(); track?.release() } catch (_: Throwable) {}
            try {
                audioManager.isSpeakerphoneOn = previousSpeakerphoneOn
                audioManager.mode = previousMode
            } catch (_: Throwable) {}
            Log.i(TAG, "PlaybackThread stopped")
        }
    }
}