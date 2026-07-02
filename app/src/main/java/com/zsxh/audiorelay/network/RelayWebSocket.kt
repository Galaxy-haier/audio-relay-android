package com.zsxh.audiorelay.network

import android.util.Log
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket 客户端 — 连接 Audio Relay 服务端
 */
class RelayWebSocket(
    private val serverUrl: String = "wss://audio.zsxh.me/ws"
) {
    companion object {
        private const val TAG = "RelayWS"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT = 10
        private const val PING_INTERVAL_MS = 5000L
    }

    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var onConnected: (() -> Unit)? = null
    var onJoined: ((String, Int) -> Unit)? = null  // role, consumerCount
    var onError: ((String) -> Unit)? = null
    var onDisconnected: ((Int, String) -> Unit)? = null
    var onProducerLeft: (() -> Unit)? = null

    @Volatile var isConnected = false; private set
    @Volatile var isJoined = false; private set
    private var reconnectCount = 0
    private var roomId = ""
    private var password = ""
    @Volatile private var shouldReconnect = true

    fun connect() {
        shouldReconnect = true
        reconnectCount = 0
        doConnect()
    }

    private fun doConnect() {
        val request = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $serverUrl")
                isConnected = true
                reconnectCount = 0
                onConnected?.invoke()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSignaling(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Android 端是 producer，不接收音频数据
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Closed: $code $reason")
                isConnected = false
                isJoined = false
                onDisconnected?.invoke(code, reason)
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                isJoined = false
                onError?.invoke(t.message ?: "连接失败")
                scheduleReconnect()
            }
        })
    }

    fun joinProducer(room: String, pwd: String = "") {
        roomId = room
        password = pwd
        sendJson(buildJsonObject {
            put("type", "join_producer")
            put("room", room)
            put("password", pwd)
        })
    }

    fun leave() {
        sendJson(buildJsonObject { put("type", "leave") })
        isJoined = false
    }

    fun sendAudioFrame(data: ByteArray) {
        ws?.send(data.toByteString())
    }

    fun disconnect() {
        shouldReconnect = false
        isJoined = false
        ws?.close(1000, "User disconnect")
        ws = null
    }

    private fun handleSignaling(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "connected" -> { /* server ack */ }
                "joined" -> {
                    isJoined = true
                    val role = json.optString("role")
                    val consumers = json.optInt("consumerCount", 0)
                    Log.i(TAG, "Joined as $role, consumers: $consumers")
                    onJoined?.invoke(role, consumers)
                }
                "room_info" -> {
                    Log.i(TAG, "Room info: $text")
                }
                "producer_left" -> {
                    onProducerLeft?.invoke()
                }
                "error" -> {
                    val msg = json.optString("message", "Unknown error")
                    Log.e(TAG, "Server error: $msg")
                    onError?.invoke(msg)
                }
                "pong" -> { /* latency tracking could be added */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun sendJson(obj: JSONObject) {
        if (!isConnected) {
            Log.w(TAG, "Not connected, cannot send")
            return
        }
        ws?.send(obj.toString())
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectCount >= MAX_RECONNECT) return
        reconnectCount++
        val delay = RECONNECT_DELAY_MS * (1L shl (reconnectCount - 1).coerceAtMost(5))
        Log.i(TAG, "Reconnecting in ${delay}ms (attempt $reconnectCount)")
        Thread {
            Thread.sleep(delay)
            if (shouldReconnect && !isConnected) {
                doConnect()
                // rejoin room after reconnect
                if (roomId.isNotEmpty()) {
                    Thread.sleep(1000)
                    joinProducer(roomId, password)
                }
            }
        }.start()
    }

    private inline fun buildJsonObject(block: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply(block)
    }
}
