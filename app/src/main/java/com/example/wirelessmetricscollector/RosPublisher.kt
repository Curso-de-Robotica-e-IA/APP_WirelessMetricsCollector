package com.example.wirelessmetricscollector

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Envia mensagens para um tópico ROS 2 através do rosbridge (WebSocket).
class RosPublisher(
    private val url: String,                      // ex.: ws://localhost:9090
    private val topico: String,                   // ex.: /tb1/ble
    private val aoMudarEstado: (String) -> Unit   // avisa a tela sobre o estado da conexão
) {
    private val cliente = OkHttpClient.Builder()
        .pingInterval(5, TimeUnit.SECONDS)        // detecta conexão morta
        .build()

    private var ws: WebSocket? = null
    @Volatile private var encerrado = false
    @Volatile var conectado = false
        private set

    // Abre a conexão com o rosbridge
    fun conectar() {
        encerrado = false
        aoMudarEstado("conectando...")

        val pedido = Request.Builder().url(url).build()
        ws = cliente.newWebSocket(pedido, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                conectado = true
                // Anuncia o tópico ao rosbridge
                val anuncio = JSONObject()
                    .put("op", "advertise")
                    .put("topic", topico)
                    .put("type", "std_msgs/msg/String")
                webSocket.send(anuncio.toString())
                aoMudarEstado("conectado")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                conectado = false
                aoMudarEstado("sem conexão (${t.message}). Tentando de novo...")
                tentarReconectar()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                conectado = false
                aoMudarEstado("desconectado")
            }
        })
    }

    // Tenta reconectar após 2 segundos (a menos que tenha sido encerrado de propósito)
    private fun tentarReconectar() {
        if (encerrado) return
        Thread {
            Thread.sleep(2000)
            if (!encerrado) conectar()
        }.start()
    }

    // Publica um JSON no tópico. Devolve false se não estiver conectado.
    fun publicar(dados: JSONObject): Boolean {
        val w = ws ?: return false
        if (!conectado) return false

        val mensagem = JSONObject()
            .put("op", "publish")
            .put("topic", topico)
            .put("msg", JSONObject().put("data", dados.toString()))
        return w.send(mensagem.toString())
    }

    // Fecha a conexão de propósito (sem reconectar)
    fun encerrar() {
        encerrado = true
        ws?.send(JSONObject().put("op", "unadvertise").put("topic", topico).toString())
        ws?.close(1000, "fim")
        ws = null
        conectado = false
    }
}
