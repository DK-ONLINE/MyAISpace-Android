package com.openclaw.assistant.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenClaw WebSocket Client (Node Protocol)
 */
class OpenClawClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Use a large timeout for the WS
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected: Boolean = false
    private val callId = AtomicLong(0)

    // Channel to pass incoming messages (responses) back to the caller
    private val _responseChannel = Channel<OpenClawResponse>(Channel.UNLIMITED)
    val responseFlow = _responseChannel.receiveAsFlow()

    fun isConnected(): Boolean = isConnected

    fun connect(gatewayUrl: String, authToken: String?): Result<Boolean> {
        if (isConnected) return Result.success(true)
        
        try {
            // Note: We MUST convert http/https to ws/wss for OkHttp
            val wsUrl = gatewayUrl.replace("http", "ws")
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, WebSocketListenerImpl())
            // We return success here and let the listener handle the final state
            return Result.success(true)
        } catch (e: Exception) {
            isConnected = false
            webSocket = null
            return Result.failure(e)
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        isConnected = false
        webSocket = null
    }

    /**
     * Send message using the OpenClaw session.send command.
     */
    fun sendMessage(
        message: String,
        sessionId: String,
        authToken: String? = null // Auth token is passed in the URL header for WS connection, keeping it here for now
    ) {
        if (!isConnected || webSocket == null) {
            _responseChannel.trySend(OpenClawResponse(error = "Gateway not connected. Please check settings."))
            return
        }
        
        val id = callId.incrementAndGet().toString()

        val data = JsonObject().apply {
            addProperty("message", message)
            addProperty("sessionKey", sessionId) // Assuming sessionId stores the sessionKey ('main')
            addProperty("agentId", "main")
        }

        val nodeMessage = JsonObject().apply {
            addProperty("id", id)
            addProperty("kind", "session.send")
            add("data", data)
        }

        webSocket?.send(gson.toJson(nodeMessage))
        // Response will be received asynchronously via responseFlow
    }

    /**
     * Test connection is now a simple connect/disconnect attempt.
     */
    suspend fun testConnection(
        gatewayUrl: String,
        authToken: String?
    ): Result<Boolean> = connect(gatewayUrl, authToken)

    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            // Immediately send the first user message: hello
            sendMessage("Hello from new MyAISpace Android Node", "main", null)
            _responseChannel.trySend(OpenClawResponse(response = "CONNECTED"))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                // Filter for "agent.turn" or "session.message" events
                val kind = json.get("kind")?.asString

                if (kind == "session.message" || kind == "agent.turn") {
                    val data = json.getAsJsonObject("data")
                    val message = data.get("message")?.asString
                    _responseChannel.trySend(OpenClawResponse(response = message))
                }
            } catch (e: Exception) {
                // Log or ignore malformed JSON/non-chat messages
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            _responseChannel.trySend(OpenClawResponse(error = "Disconnected ($code: $reason)"))
            this@OpenClawClient.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            _responseChannel.trySend(OpenClawResponse(error = "Connection failed: ${t.message}"))
            this@OpenClawClient.webSocket = null
        }
    }
}

/**
 * Response wrapper
 */
data class OpenClawResponse(
    val response: String? = null,
    val error: String? = null
) {
    fun getResponseText(): String? = response
}
