package xyz.bluspring.unitytranslate.client.transcribers.browser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.Util
import net.minecraft.util.HttpUtil
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import xyz.bluspring.unitytranslate.Language
import xyz.bluspring.unitytranslate.UnityTranslate
import xyz.bluspring.unitytranslate.client.gui.OpenBrowserScreen
import xyz.bluspring.unitytranslate.client.transcribers.SpeechTranscriber
import xyz.bluspring.unitytranslate.client.transcribers.TranscriberType
import java.net.InetSocketAddress

class BrowserSpeechTranscriber(language: Language) : SpeechTranscriber(language) {
    val socketPort = HttpUtil.getAvailablePort()
    val server: ApplicationEngine
    val socket = BrowserSocket()

    init {
        val port = HttpUtil.getAvailablePort()

        server = embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
            .start(wait = false)
            .apply {
                wsPort = socketPort
            }

        socket.isDaemon = true
        socket.start()

        ClientPlayConnectionEvents.JOIN.register { listener, sender, mc ->
            if (socket.totalConnections <= 0 && UnityTranslate.config.client.enabled) {
                if (UnityTranslate.config.client.openBrowserWithoutPrompt) {
                    Util.getPlatform().openUri("http://127.0.0.1:$port")
                } else {
                    mc.setScreen(OpenBrowserScreen("http://127.0.0.1:$port"))
                }
            }
        }
    }

    override fun stop() {
        server.stop(1000L, 1000L)
        socket.stop(1000)
    }

    inner class BrowserSocket : WebSocketServer(InetSocketAddress("localhost", socketPort)) {
        var totalConnections = 0

        override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
            ws.sendData("set_language", JsonObject().apply {
                addProperty("language", language.supportedTranscribers[TranscriberType.BROWSER])
            })
            totalConnections++
        }

        override fun onClose(ws: WebSocket, code: Int, reason: String, remote: Boolean) {
            totalConnections--
        }

        override fun onMessage(ws: WebSocket, message: String) {
            val msg = JsonParser.parseString(message).asJsonObject
            val data = if (msg.has("d")) msg.getAsJsonObject("d") else JsonObject()

            when (msg.get("op").asString) {
                "transcript" -> {
                    val results = data.getAsJsonArray("results")
                    val index = data.get("index").asInt

                    val deserialized = mutableListOf<Pair<String, Double>>()
                    for (result in results) {
                        val d = result.asJsonObject
                        deserialized.add(d.get("text").asString to d.get("confidence").asDouble)
                    }

                    lastIndex = currentOffset + index

                    if (deserialized.isEmpty())
                        return

                    val selected = deserialized.sortedByDescending { it.second }[0].first

                    if (selected.isNotBlank()) {
                        updater.accept(lastIndex, selected.trim())
                    }
                }

                "reset" -> {
                    currentOffset = lastIndex + 1
                }
            }
        }

        override fun onError(ws: WebSocket, ex: Exception) {
            ex.printStackTrace()
        }

        override fun onStart() {
            UnityTranslate.logger.info("Started WebSocket server for Browser Transcriber mode at ${this.address}")
        }

        fun WebSocket.sendData(op: String, data: JsonObject = JsonObject()) {
            this.send(JsonObject().apply {
                this.addProperty("op", op)
                this.add("d", data)
            }.toString())
        }
    }
}