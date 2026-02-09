package com.openclaw.assistant.api

import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenClaw WebSocket Client (Node Protocol v3)
 * 
 * Implements the OpenClaw gateway handshake protocol:
 * 1. Connect to WebSocket endpoint
 * 2. Send "connect" handshake with auth token in params
 * 3. Wait for "hello-ok" response
 * 4. Use "chat.send" for messages
 */
class OpenClawClient {

    companion object {
        private const val TAG = "OpenClawClient"
        private const val CLIENT_ID = "openclaw-android"
        private const val CLIENT_VERSION = "0.9.3"
        private const val PROTOCOL_MIN = 1
        private const val PROTOCOL_MAX = 3
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // Use a large timeout for the WS
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private var isConnected: Boolean = false
    private var isHandshakeComplete: Boolean = false
    private val callId = AtomicLong(System.currentTimeMillis())
    
    // Store auth token for handshake
    private var storedAuthToken: String? = null

    // Channel to pass incoming messages (responses) back to the caller
    private val _responseChannel = Channel<OpenClawResponse>(Channel.UNLIMITED)
    val responseFlow = _responseChannel.receiveAsFlow()

    fun isConnected(): Boolean = isConnected && isHandshakeComplete

    fun connect(gatewayUrl: String, authToken: String?): Result<Boolean> {
        if (isConnected && isHandshakeComplete) return Result.success(true)
        
        // Reset state for new connection
        isConnected = false
        isHandshakeComplete = false
        storedAuthToken = authToken
        
        try {
            // Note: We MUST convert http/https to ws/wss for OkHttp
            val wsUrl = gatewayUrl.replace("http", "ws")
            Log.d(TAG, "Connecting to WebSocket: $wsUrl")

            // No Authorization header - token goes in handshake message
            val request = Request.Builder().url(wsUrl).build()
            webSocket = client.newWebSocket(request, WebSocketListenerImpl())
            return Result.success(true)
        } catch (e: Exception) {
            isConnected = false
            isHandshakeComplete = false
            webSocket = null
            Log.e(TAG, "Connection attempt failed", e)
            return Result.failure(e)
        }
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting WebSocket")
        webSocket?.close(1000, "User disconnect")
        isConnected = false
        isHandshakeComplete = false
        webSocket = null
    }

    /**
     * Send the OpenClaw Node Protocol connect handshake.
     * Must be the first message after WebSocket opens.
     */
    private fun sendConnectHandshake() {
        val id = callId.incrementAndGet().toString()
        
        // Build client info
        val clientInfo = JsonObject().apply {
            addProperty("id", CLIENT_ID)
            addProperty("version", CLIENT_VERSION)
            addProperty("platform", "android")
            addProperty("deviceFamily", Build.MANUFACTURER)
            addProperty("modelIdentifier", Build.MODEL)
            addProperty("mode", "ui")
        }
        
        // Build params
        val params = JsonObject().apply {
            addProperty("minProtocol", PROTOCOL_MIN)
            addProperty("maxProtocol", PROTOCOL_MAX)
            add("client", clientInfo)
            addProperty("role", "operator")
            
            // Add auth token if provided
            if (!storedAuthToken.isNullOrEmpty()) {
                val authObj = JsonObject().apply {
                    addProperty("token", storedAuthToken)
                }
                add("auth", authObj)
            }
        }
        
        // Build request frame
        val handshake = JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", id)
            addProperty("method", "connect")
            add("params", params)
        }
        
        val json = gson.toJson(handshake)
        Log.d(TAG, "Sending connect handshake: $json")
        val success = webSocket?.send(json) ?: false
        if (!success) {
            Log.e(TAG, "Failed to send connect handshake")
            _responseChannel.trySend(OpenClawResponse(error = "Failed to send handshake"))
        }
    }

    /**
     * Send message using the OpenClaw chat.send RPC method.
     */
    fun sendMessage(
        message: String,
        sessionKey: String,
        authToken: String? = null
    ) {
        if (!isConnected || !isHandshakeComplete || webSocket == null) {
            Log.e(TAG, "Cannot send message: Not connected or handshake incomplete")
            _responseChannel.trySend(OpenClawResponse(error = "Gateway not connected. Please check settings."))
            return
        }
        
        val id = callId.incrementAndGet().toString()
        val idempotencyKey = "android-${System.currentTimeMillis()}-$id"

        // Build params for chat.send
        val params = JsonObject().apply {
            addProperty("sessionKey", sessionKey)
            addProperty("message", message)
            addProperty("idempotencyKey", idempotencyKey)
        }

        // Build request frame
        val request = JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", id)
            addProperty("method", "chat.send")
            add("params", params)
        }

        val json = gson.toJson(request)
        Log.d(TAG, "Sending chat.send request: $json")
        val success = webSocket?.send(json) ?: false
        if (!success) {
            Log.e(TAG, "Failed to queue chat.send request")
            _responseChannel.trySend(OpenClawResponse(error = "Failed to send message"))
        }
    }

    suspend fun testConnection(
        gatewayUrl: String,
        authToken: String?
    ): Result<Boolean> = connect(gatewayUrl, authToken)

    private inner class WebSocketListenerImpl : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket transport connected, sending handshake...")
            isConnected = true
            // Send the connect handshake immediately
            sendConnectHandshake()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Received message: $text")
            try {
                val json = gson.fromJson(text, JsonObject::class.java)
                val type = json.get("type")?.asString
                
                // Handle hello-ok response (handshake complete)
                if (type == "hello-ok") {
                    Log.i(TAG, "Handshake complete - connected to gateway")
                    isHandshakeComplete = true
                    _responseChannel.trySend(OpenClawResponse(response = "CONNECTED"))
                    return
                }
                
                // Handle response frames
                if (type == "res") {
                    val ok = json.get("ok")?.asBoolean ?: false
                    if (!ok) {
                        val error = json.getAsJsonObject("error")
                        val errorMsg = error?.get("message")?.asString ?: "Unknown error"
                        Log.e(TAG, "Request failed: $errorMsg")
                        _responseChannel.trySend(OpenClawResponse(error = errorMsg))
                    }
                    return
                }
                
                // Handle event frames (chat responses)
                if (type == "event") {
                    val event = json.get("event")?.asString
                    if (event == "chat" || event == "chat.delta" || event == "chat.final") {
                        val payload = json.getAsJsonObject("payload")
                        val message = payload?.get("message")?.asString
                        if (message != null) {
                            _responseChannel.trySend(OpenClawResponse(response = message))
                        }
                    }
                    return
                }
                
                // Legacy format support (kind-based)
                val kind = json.get("kind")?.asString
                if (kind == "session.message" || kind == "agent.turn") {
                    val data = json.getAsJsonObject("data")
                    val message = data?.get("message")?.asString
                    _responseChannel.trySend(OpenClawResponse(response = message))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing incoming message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket Closing: $code / $reason")
            isConnected = false
            isHandshakeComplete = false
            _responseChannel.trySend(OpenClawResponse(error = "Disconnected ($code: $reason)"))
            this@OpenClawClient.webSocket = null
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket Failure", t)
            isConnected = false
            isHandshakeComplete = false
            val errorMsg = response?.message ?: t.message ?: "Unknown error"
            _responseChannel.trySend(OpenClawResponse(error = "Connection failed: $errorMsg"))
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
