package com.chiller3.bcr.bridge

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class BridgeMixedWavRecorder(
    private val context: Context,
    private val sessionId: String,
    private val callId: String,
    private val sampleRate: Int,
) {
    companion object {
        private const val TAG = "BridgeMixedWavRecorder"
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2

        // Giữ tối đa ~0.5 giây audio chờ bên còn lại để cố gắng mix 2 chiều
        private fun maxHoldSamples(sampleRate: Int): Int = (sampleRate / 2).coerceAtLeast(4000)
    }

    private val inboundPending = ShortPcmQueue()
    private val outboundPending = ShortPcmQueue()

    private val displayName: String = buildFileName()
    private val tempDir = File(context.cacheDir, "bridge_recordings").apply { mkdirs() }
    private val tempFile = File(tempDir, "$displayName.tmp")
    private val raf = RandomAccessFile(tempFile, "rw")

    @Volatile
    private var closed = false

    private var dataBytesWritten: Long = 0L

    init {
        writeHeaderPlaceholder()
        Log.i(TAG, "Bridge WAV temp file: ${tempFile.absolutePath}")
    }

    @Synchronized
    fun appendInbound(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        inboundPending.add(bytesToShortArray(bytes))
        drainMixed()
    }

    @Synchronized
    fun appendOutbound(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        outboundPending.add(bytesToShortArray(bytes))
        drainMixed()
    }

    @Synchronized
    fun closeAndPublish() {
        if (closed) return
        closed = true

        flushAllPending()
        finalizeHeader()

        try {
            raf.fd.sync()
        } catch (_: Throwable) {
        }

        raf.close()

        if (dataBytesWritten <= 0L) {
            Log.w(TAG, "No bridge audio captured, deleting temp file")
            tempFile.delete()
            return
        }

        try {
            val published = publishToDownloads()
            Log.i(TAG, "Bridge WAV saved: $published")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to publish bridge WAV, temp kept at ${tempFile.absolutePath}", t)
        }
    }

    fun closeQuietly() {
        try {
            closeAndPublish()
        } catch (t: Throwable) {
            Log.e(TAG, "closeQuietly failed", t)
        }
    }

    @Synchronized
    private fun drainMixed() {
        // Nếu cả 2 bên đều có dữ liệu thì mix phần giao nhau trước
        while (inboundPending.availableSamples() > 0 && outboundPending.availableSamples() > 0) {
            val n = minOf(inboundPending.availableSamples(), outboundPending.availableSamples())
            if (n <= 0) break

            val mixed = ShortArray(n)
            for (i in 0 until n) {
                val inSample = inboundPending.pollOrZero().toInt()
                val outSample = outboundPending.pollOrZero().toInt()

                // Average để giảm clipping
                mixed[i] = ((inSample + outSample) / 2).toShort()
            }
            writeShorts(mixed)
        }

        // Nếu 1 bên chờ quá nhiều mà bên kia không có, ghi ra riêng để tránh treo buffer
        val maxHold = maxHoldSamples(sampleRate)

        if (inboundPending.availableSamples() > maxHold) {
            val count = inboundPending.availableSamples() - maxHold
            writeSingleSide(inboundPending, count)
        }

        if (outboundPending.availableSamples() > maxHold) {
            val count = outboundPending.availableSamples() - maxHold
            writeSingleSide(outboundPending, count)
        }
    }

    private fun flushAllPending() {
        // Khi kết thúc cuộc gọi, đẩy hết phần còn lại ra file
        while (inboundPending.availableSamples() > 0 && outboundPending.availableSamples() > 0) {
            val n = minOf(inboundPending.availableSamples(), outboundPending.availableSamples())
            val mixed = ShortArray(n)
            for (i in 0 until n) {
                val inSample = inboundPending.pollOrZero().toInt()
                val outSample = outboundPending.pollOrZero().toInt()
                mixed[i] = ((inSample + outSample) / 2).toShort()
            }
            writeShorts(mixed)
        }

        if (inboundPending.availableSamples() > 0) {
            writeSingleSide(inboundPending, inboundPending.availableSamples())
        }

        if (outboundPending.availableSamples() > 0) {
            writeSingleSide(outboundPending, outboundPending.availableSamples())
        }
    }

    private fun writeSingleSide(queue: ShortPcmQueue, count: Int) {
        if (count <= 0) return

        val out = ShortArray(count)
        for (i in 0 until count) {
            // Giảm biên độ nhẹ để tránh gắt
            out[i] = (queue.pollOrZero().toInt() / 2).toShort()
        }
        writeShorts(out)
    }

    private fun writeShorts(samples: ShortArray) {
        if (samples.isEmpty()) return

        val bytes = ByteArray(samples.size * 2)
        var j = 0
        for (sample in samples) {
            val v = sample.toInt()
            bytes[j++] = (v and 0xff).toByte()
            bytes[j++] = ((v ushr 8) and 0xff).toByte()
        }

        raf.write(bytes)
        dataBytesWritten += bytes.size.toLong()
    }

    private fun writeHeaderPlaceholder() {
        raf.setLength(0)

        writeAscii("RIFF")
        writeIntLE(0) // patch later
        writeAscii("WAVE")

        writeAscii("fmt ")
        writeIntLE(16) // PCM fmt size
        writeShortLE(1) // PCM
        writeShortLE(CHANNELS)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * CHANNELS * BYTES_PER_SAMPLE)
        writeShortLE(CHANNELS * BYTES_PER_SAMPLE)
        writeShortLE(BITS_PER_SAMPLE)

        writeAscii("data")
        writeIntLE(0) // patch later
    }

    private fun finalizeHeader() {
        val dataSize = dataBytesWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val riffSize = 36 + dataSize

        raf.seek(4)
        writeIntLE(riffSize)

        raf.seek(40)
        writeIntLE(dataSize)

        raf.seek(raf.length())
    }

    private fun publishToDownloads(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToDownloadsQ()
        } else {
            publishToDownloadsLegacy()
        }
    }

    private fun publishToDownloadsQ(): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/BCR")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri, "w").use { out ->
                requireNotNull(out) { "MediaStore output stream is null" }
                tempFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }

            val done = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)

            tempFile.delete()
            return uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun publishToDownloadsLegacy(): Uri {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "BCR"
        ).apply { mkdirs() }

        val target = File(dir, displayName)
        tempFile.copyTo(target, overwrite = true)
        tempFile.delete()

        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("audio/wav"),
            null,
        )

        return Uri.fromFile(target)
    }

    private fun buildFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeCallId = sanitize(callId).ifBlank { "unknown" }
        val safeSessionId = sanitize(sessionId).take(24).ifBlank { "session" }
        return "bridge_${ts}_${safeCallId}_${safeSessionId}.wav"
    }

    private fun sanitize(value: String): String {
        return value.replace(Regex("[^A-Za-z0-9._-]+"), "_")
    }

    private fun bytesToShortArray(bytes: ByteArray): ShortArray {
        val sampleCount = bytes.size / 2
        val out = ShortArray(sampleCount)

        var bi = 0
        var si = 0
        while (si < sampleCount) {
            val lo = bytes[bi].toInt() and 0xff
            val hi = bytes[bi + 1].toInt()
            out[si] = ((hi shl 8) or lo).toShort()
            bi += 2
            si += 1
        }

        return out
    }

    private fun writeAscii(text: String) {
        raf.write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun writeIntLE(value: Int) {
        raf.write(value and 0xff)
        raf.write((value ushr 8) and 0xff)
        raf.write((value ushr 16) and 0xff)
        raf.write((value ushr 24) and 0xff)
    }

    private fun writeShortLE(value: Int) {
        raf.write(value and 0xff)
        raf.write((value ushr 8) and 0xff)
    }

    private class ShortPcmQueue {
        private val chunks = ArrayDeque<ShortArray>()
        private var headIndex = 0
        private var available = 0

        fun add(samples: ShortArray) {
            if (samples.isEmpty()) return
            chunks.addLast(samples)
            available += samples.size
        }

        fun availableSamples(): Int = available

        fun pollOrZero(): Short = poll() ?: 0

        private fun poll(): Short? {
            while (chunks.isNotEmpty()) {
                val head = chunks.first()
                if (headIndex < head.size) {
                    val value = head[headIndex++]
                    available--

                    if (headIndex >= head.size) {
                        chunks.removeFirst()
                        headIndex = 0
                    }

                    return value
                } else {
                    chunks.removeFirst()
                    headIndex = 0
                }
            }

            return null
        }
    }
}