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

        fun buildStartIntent(
            context: Context,
            sessionId: String,
            callId: String,
            wsUrl: String,
            sampleRate: Int,
            channels: Int,
        ) = Intent(context, RealtimeBridgeService::class.java).apply {
            action = ACTION_CALL_STARTED
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_CALL_ID, callId)
            putExtra(EXTRA_WS_URL, wsUrl)
            putExtra(EXTRA_SAMPLE_RATE, sampleRate)
            putExtra(EXTRA_CHANNELS, channels)
        }

        fun buildStopIntent(
            context: Context,
            sessionId: String,
            callId: String,
        ) = Intent(context, RealtimeBridgeService::class.java).apply {
            action = ACTION_CALL_ENDED
            putExtra(EXTRA_SESSION_ID, sessionId)
            putExtra(EXTRA_CALL_ID, callId)
        }

        fun buildStateIntent(
            context: Context,
            sessionId: String,
            callId: String,
            state: String,
        ) = Intent(context, RealtimeBridgeService::class.java).apply {
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
        val sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 8000)
        val channels = intent.getIntExtra(EXTRA_CHANNELS, 1)

        val info = CallSessionInfo(
            sessionId = sessionId,
            wsUrl = wsUrl,
            sampleRate = sampleRate,
            channels = channels,
            callId = callId,
            deviceId = prefs.deviceId,
            playbackMode = prefs.playbackMode,
            injectMode = prefs.injectMode,
        )

        runtimes.remove(sessionId)?.shutdown()
        val runtime = SessionRuntime(info, scope)
        runtimes[sessionId] = runtime
        runtime.connect()
        Log.i(TAG, "Bridge started for session=$sessionId callId=$callId")
    }

    private fun handleCallEnded(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        runtimes.remove(sessionId)?.shutdown()
        if (runtimes.isEmpty()) {
            stopSelf()
        }
        Log.i(TAG, "Bridge ended for session=$sessionId")
    }

    private fun handleCallStateChanged(intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val callId = intent.getStringExtra(EXTRA_CALL_ID)
        val state = intent.getStringExtra(EXTRA_CALL_STATE) ?: return
        runtimes[sessionId]?.sendJson(Protocol.state(sessionId, state, callId))
    }

    private class SessionRuntime(
        private val info: CallSessionInfo,
        private val parentScope: CoroutineScope,
    ) {
        private val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private val playbackThread = PlaybackThread(info.sampleRate, info.channels)
        private val uplinkInjector = UplinkInjector(playbackThread, info.injectMode)
        private var socket: WebSocket? = null
        private var heartbeatJob: Job? = null

        @Volatile
        private var closed = false

        @Volatile
        private var lastPongAt = System.currentTimeMillis()

        fun connect() {
            if (!playbackThread.isAlive) {
                playbackThread.start()
            }

            val request = Request.Builder().url(info.wsUrl).build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    lastPongAt = System.currentTimeMillis()
                    sendJson(
                        Protocol.helloHandset(
                            sessionId = info.sessionId,
                            deviceId = info.deviceId,
                            sampleRate = info.sampleRate,
                            channels = info.channels,
                            playbackMode = info.playbackMode,
                            injectMode = info.injectMode,
                        )
                    )
                    sendJson(Protocol.state(info.sessionId, "connected", info.callId))
                    startHeartbeat()
                    Log.i(TAG, "WS open: ${info.sessionId}")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleText(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    uplinkInjector.handleInboundPcm(bytes.toByteArray())
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WS closing: ${info.sessionId}, code=$code, reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i(TAG, "WS closed: ${info.sessionId}, code=$code, reason=$reason")
                    stopHeartbeat()
                    reconnectIfNeeded()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WS failure: ${info.sessionId}", t)
                    stopHeartbeat()
                    reconnectIfNeeded()
                }
            })
        }

        private fun handleText(text: String) {
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    Protocol.TYPE_PING -> sendJson(Protocol.pong(info.sessionId))
                    Protocol.TYPE_PONG -> lastPongAt = System.currentTimeMillis()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Ignore invalid control frame: $text", t)
            }
        }

        private fun startHeartbeat() {
            stopHeartbeat()
            heartbeatJob = parentScope.launch {
                while (isActive && !closed) {
                    delay(10_000)
                    sendJson(Protocol.ping(info.sessionId))
                    if (System.currentTimeMillis() - lastPongAt > 30_000) {
                        Log.w(TAG, "Heartbeat timeout: ${info.sessionId}")
                        socket?.cancel()
                        break
                    }
                }
            }
        }

        private fun stopHeartbeat() {
            heartbeatJob?.cancel()
            heartbeatJob = null
        }

        private fun reconnectIfNeeded() {
            if (closed) return
            parentScope.launch {
                delay(1500)
                if (!closed) {
                    connect()
                }
            }
        }

        fun sendJson(json: JSONObject) {
            socket?.send(json.toString())
        }

        fun sendOutboundPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
            if (closed || pcm.isEmpty()) return
            val current = socket ?: return
            if (sampleRate != info.sampleRate || channels != info.channels) {
                Log.w(
                    TAG,
                    "PCM format mismatch runtime=${info.sampleRate}/${info.channels} actual=$sampleRate/$channels"
                )
            }
            current.send(ByteString.of(*pcm))
        }

        fun shutdown() {
            closed = true
            stopHeartbeat()
            try {
                sendJson(Protocol.state(info.sessionId, "ended", info.callId))
            } catch (_: Throwable) {
            }
            try {
                socket?.close(1000, "call ended")
            } catch (_: Throwable) {
            }
            try {
                socket?.cancel()
            } catch (_: Throwable) {
            }
            socket = null
            playbackThread.shutdown()
        }
    }
}