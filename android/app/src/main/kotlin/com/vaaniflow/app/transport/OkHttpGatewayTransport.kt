package com.vaaniflow.app.transport

import com.vaaniflow.app.config.GatewayConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class OkHttpGatewayTransport(
    private val config: GatewayConfig,
    // The server already pings every 45 s; our own ping only catches a dead socket, so it can be
    // rare. Every ping wakes the mobile radio, which is the biggest background battery cost.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(120, TimeUnit.SECONDS)
        .build(),
) : GatewayTransport {
    @Volatile
    private var listener: GatewayTransport.Listener? = null

    @Volatile
    private var socket: WebSocket? = null

    override fun setListener(listener: GatewayTransport.Listener?) {
        this.listener = listener
    }

    override fun connect() {
        if (socket != null) return

        val request = Request.Builder()
            .url(config.wsUrl)
            .header("X-Device-Id", config.deviceId)
            .header("X-Device-Token", config.deviceToken)
            .build()

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener?.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener?.onMessage(text)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    socket = null
                    listener?.onClosed()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    socket = null
                    listener?.onFailure()
                }
            },
        )
    }

    override fun send(text: String): Boolean = socket?.send(text) == true

    override fun close() {
        socket?.close(1000, "client_reconfigure")
        socket = null
    }
}
