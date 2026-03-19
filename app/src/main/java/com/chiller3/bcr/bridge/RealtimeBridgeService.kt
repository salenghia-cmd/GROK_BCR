package com.chiller3.bcr.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.chiller3.bcr.Preferences
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RealtimeBridgeService : Service() {

    companion object : RealtimePcmSink {
        private const val TAG = "RealtimeBridgeService"

        const val ACTION_CALL_STARTED = "com.chiller3.bcr.bridge.ACTION_CALL_STARTED"
        const val ACTION_CALL_ENDED = "com.chiller3.bcr.bridge.ACTION_CALL_ENDED"
        const val ACTION_CALL_STATE_CHANGED = "com.chiller3.bcr.bridge.ACTION_CALL_STATE_CHANGED"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_SAMPLE_RATE = "sample_rate"
        const val EXTRA_CHANNELS = "channels"

        private val runtimes = ConcurrentHashMap<String, SessionRuntime>()

        // ================== FORCE 16000/1 ĐỂ WEB NÓI → DT2 NGHE ==================
        override fun onPcmChunk(sessionId: String, pcm: ByteArray, sampleRate: Int, channels: Int) {
            pushOutboundPcm(sessionId, pcm, 16000, 1)
        }

        fun pushOutboundPcm(sessionId: String, pcm: ByteArray, sampleRate: Int, channels: Int) {
            runtimes[sessionId]?.sendOutboundPcm(pcm)
        }

        fun buildStartIntent(context: Context, sessionId: String, callId: String, wsUrl: String, sampleRate: Int = 16000, channels: Int = 1) =
            Intent(context, RealtimeBridgeService::class.java).apply {
                action = ACTION_CALL_STARTED
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_WS_URL, wsUrl)
                putExtra(EXTRA_SAMPLE_RATE, sampleRate)
                putExtra(EXTRA_CHANNELS, channels)
            }

        fun buildStopIntent(context: Context, sessionId: String) =
            Intent(context, RealtimeBridgeService::class.java).apply {
                action = ACTION_CALL_ENDED
                putExtra(EXTRA_SESSION_ID, sessionId)
            }

        fun buildStateIntent(context: Context, sessionId: String, callId: String, state: String) =
            Intent(context, RealtimeBridgeService::class.java).apply {
                action = ACTION_CALL_STATE_CHANGED
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra("call_state", state)
            }

        fun ensureSessionId(callId: String): String {
            return "call-${callId.ifBlank { "unknown" }}-${UUID.randomUUID()}"
        }
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CALL_STARTED -> handleCallStarted(intent)
            ACTION_CALL_ENDED -> handleCallEnded(intent)
            ACTION_CALL_STATE_CHANGED -> handleCallStateChanged(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtimes.values.forEach { it.shutdown() }
        runtimes.clear()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleCallStarted(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: prefs.resolvePrimaryWsUrl() ?: return

        val runtime = SessionRuntime(this, sessionId, wsUrl, scope)
        runtimes[sessionId] = runtime
        runtime.connect()

        Log.i(TAG, "Bridge started | Forced 16000Hz mono")
    }

    private fun handleCallEnded(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        runtimes.remove(sessionId)?.shutdown()
        Log.i(TAG, "Bridge ended for $sessionId")
    }

    private fun handleCallStateChanged(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val state = intent.getStringExtra("call_state") ?: return
        runtimes[sessionId]?.sendJson(state)
    }

    private class SessionRuntime(
        private val context: Context,
        private val sessionId: String,
        private val wsUrl: String,
        private val scope: CoroutineScope
    ) {
        private var webSocket: WebSocket? = null
        private var playbackThread: PlaybackThread? = null

        fun connect() {
            val client = OkHttpClient.Builder().pingInterval(15, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "WebSocket connected")
                    startPlayback()
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    playbackThread?.enqueue(bytes.toByteArray())
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket failure", t)
                }
            })
        }

        private fun startPlayback() {
            playbackThread = PlaybackThread(context, 16000, 1, "earpiece")
            playbackThread?.start()
        }

        fun sendOutboundPcm(pcm: ByteArray) {}
        fun sendJson(data: String) { webSocket?.send(data) }

        fun shutdown() {
            webSocket?.close(1000, null)
            playbackThread?.shutdown()
            playbackThread = null
        }
    }
}