package com.chiller3.bcr.bridge

import org.json.JSONObject

object Protocol {
    const val TYPE_HELLO = "hello"
    const val TYPE_STATE = "state"
    const val TYPE_PING = "ping"
    const val TYPE_PONG = "pong"
    const val TYPE_ERROR = "error"

    const val ROLE_HANDSET = "handset"
    const val ROLE_WEB = "web"

    const val CODEC_PCM_S16LE = "pcm_s16le"

    fun helloHandset(
        sessionId: String,
        deviceId: String?,
        sampleRate: Int,
        channels: Int,
        bits: Int = 16,
        playbackMode: String = "local",
        injectMode: String = "noop",
    ): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_HELLO)
            put("role", ROLE_HANDSET)
            put("session", sessionId)
            put("deviceId", deviceId ?: "android")
            put("sampleRate", sampleRate)
            put("channels", channels)
            put("bits", bits)
            put("format", CODEC_PCM_S16LE)
            put("playbackMode", playbackMode)
            put("injectMode", injectMode)
        }
    }

    fun state(
        sessionId: String,
        state: String,
        callId: String?,
    ): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_STATE)
            put("session", sessionId)
            put("state", state)
            put("callId", callId ?: "unknown")
        }
    }

    fun ping(sessionId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_PING)
            put("session", sessionId)
            put("ts", System.currentTimeMillis())
        }
    }

    fun pong(sessionId: String): JSONObject {
        return JSONObject().apply {
            put("type", TYPE_PONG)
            put("session", sessionId)
            put("ts", System.currentTimeMillis())
        }
    }
}

interface RealtimePcmSink {
    fun onPcmChunk(sessionId: String, pcm: ByteArray, sampleRate: Int, channels: Int)
}