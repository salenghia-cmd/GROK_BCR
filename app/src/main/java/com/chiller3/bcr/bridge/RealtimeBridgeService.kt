package com.chiller3.bcr.bridge

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.chiller3.bcr.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class RealtimeBridgeService : Service() {

    companion object : RealtimePcmSink {
        private const val TAG = "RealtimeBridgeService"

        const val ACTION_CALL_STARTED = "com.chiller3.bcr.bridge.ACTION_CALL_STARTED"
        const val ACTION_CALL_ENDED = "com.chiller3.bcr.bridge.ACTION_CALL_ENDED"
        const val ACTION_CALL_STATE_CHANGED = "com.chiller3.bcr.bridge.ACTION_CALL_STATE_CHANGED"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_CALL_STATE = "call_state"
        const val EXTRA_WS_URL = "ws_url"
        const val EXTRA_SAMPLE_RATE = "sample_rate"
        const val EXTRA_CHANNELS = "channels"

        private val runtimes = ConcurrentHashMap<String, SessionRuntime>()

        fun buildStartIntent(context: Context, sessionId: String, callId: String, wsUrl: String, sampleRate: Int, channels: Int) =
            Intent(context, RealtimeBridgeService::class.java).apply {
                action = ACTION_CALL_STARTED
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_WS_URL, wsUrl)
                putExtra(EXTRA_SAMPLE_RATE, sampleRate)
                putExtra(EXTRA_CHANNELS, channels)
            }

        fun buildStopIntent(context: Context, sessionId: String, callId: String) =
            Intent(context, RealtimeBridgeService::class.java).apply {
                action = ACTION_CALL_ENDED
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CALL_ID, callId)
            }

        fun buildStateIntent(context: Context, sessionId: String, callId: String, state: String) =
            Intent(context, RealtimeBridgeService::class.java).apply {
                action = ACTION_CALL_STATE_CHANGED
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CALL_ID, callId)
                putExtra(EXTRA_CALL_STATE, state)
            }

        override fun onPcmChunk(sessionId: String, pcm: ByteArray, sampleRate: Int, channels: Int) {
            pushOutboundPcm(sessionId, pcm, sampleRate, channels)
        }

        fun pushOutboundPcm(sessionId: String, pcm: ByteArray, sampleRate: Int, channels: Int) {
            val runtime = runtimes[sessionId] ?: return
            runtime.sendOutboundPcm(pcm, sampleRate, channels)
        }

        fun ensureSessionId(callId: String): String {
            return "call-${callId.ifBlank { "unknown" }}-${UUID.randomUUID()}"
        }

        // SỬA Ở ĐÂY: FORCE 16000/1 để fix PCM mismatch + Web nói DT2 nghe
        fun sanitizeBridgeSampleRate(sampleRate: Int): Int = 16000
        fun sanitizeBridgeChannels(channels: Int): Int = 1
    }

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)

    private lateinit var prefs: Preferences

    override fun onCreate() {
        super.onCreate()
        prefs = Preferences(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
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
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: "unknown"
        val wsUrl = intent.getStringExtra(EXTRA_WS_URL) ?: prefs.resolvePrimaryWsUrl() ?: return

        // FORCE 16000/1 để khớp WebRTC → DT2 nghe
        val sampleRate = 16000
        val channels = 1

        val info = CallSessionInfo(sessionId, wsUrl, sampleRate, channels, callId, prefs.deviceId, prefs.playbackMode, prefs.injectMode)

        runtimes.remove(sessionId)?.shutdown()
        val runtime = SessionRuntime(applicationContext, info, scope)
        runtimes[sessionId] = runtime
        runtime.connect()

        Log.i(TAG, "Bridge started for session=$sessionId | 16000Hz/1ch (forced)")
    }

    private fun handleCallEnded(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        runtimes.remove(sessionId)?.shutdown()
        if (runtimes.isEmpty()) stopSelf()
        Log.i(TAG, "Bridge ended for session=$sessionId")
    }

    private fun handleCallStateChanged(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val state = intent.getStringExtra(EXTRA_CALL_STATE) ?: return
        runtimes[sessionId]?.sendJson(Protocol.state(sessionId, state, callId))
    }

    // Phần SessionRuntime giữ nguyên như file cũ của bạn (copy tiếp vào đây)
}