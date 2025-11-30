
package webRTCservice

import com.corundumstudio.socketio.Configuration
import com.corundumstudio.socketio.SocketIOServer
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*

fun main() {
    val config = Configuration().apply {
        port = 8081
    }
    val server = SocketIOServer(config)
    val handler = VoiceSignalingHandler(server)

    server.addConnectListener { client ->
        handler.addClient(client)
    }

    server.addDisconnectListener { client ->
        handler.removeClient(client)
    }

    server.addEventListener("message", String::class.java) { client, data, ack ->
        handler.handleMessage(client, data)
    }

    server.start()
}

class VoiceSignalingHandler(private val server: SocketIOServer) {
    private val clientToGameId = mutableMapOf<com.corundumstudio.socketio.SocketIOClient, String>()
    private val clientToUsername = mutableMapOf<com.corundumstudio.socketio.SocketIOClient, String>()
    private val gameToClients = mutableMapOf<String, MutableList<com.corundumstudio.socketio.SocketIOClient>>()
    private val usedGameIds = mutableSetOf<String>()
    private val pendingVoiceMsgs = mutableMapOf<String, MutableList<ServerMessage>>()
    private val heartbeatJobs = mutableMapOf<com.corundumstudio.socketio.SocketIOClient, Job>()
    private val scope = CoroutineScope(Dispatchers.Default)

    fun addClient(client: com.corundumstudio.socketio.SocketIOClient) {
    }

    fun handleMessage(client: com.corundumstudio.socketio.SocketIOClient, message: String) {
        try {
            val clientMessage = Json.decodeFromString<ClientMessage>(message)
            val gameId = clientMessage.gameId ?: clientToGameId[client] ?: return

            if (!usedGameIds.contains(gameId)) {
                usedGameIds.add(gameId)
            }

            when (clientMessage.action) {
                "join" -> {
                    var username = clientMessage.username ?: UUID.randomUUID().toString().substring(0, 8)
                    val clientsInGame = gameToClients.getOrPut(gameId) { mutableListOf() }

                    if (clientsInGame.size >= 2) {
                        client.sendEvent("error", "Game full")
                        return
                    }

                    if (clientsInGame.any { clientToUsername[it] == username }) {
                        username = "$username-${UUID.randomUUID().toString().substring(0, 4)}"
                    }

                    clientToGameId[client] = gameId
                    clientToUsername[client] = username
                    clientsInGame.add(client)
                    client.joinRoom(gameId)
                    resetHeartbeat(client)

                    client.sendEvent("joined", Json.encodeToString(JoinedResponse(username)))

                    val otherClients = clientsInGame.filter { it != client }
                    if (otherClients.isNotEmpty()) {
                        pendingVoiceMsgs[gameId]?.let { msgs ->
                            msgs.forEach { pendingMsg ->
                                client.sendEvent("voice", Json.encodeToString(pendingMsg))
                            }
                            pendingVoiceMsgs.remove(gameId)
                        }
                    }
                }

                "heartbeat" -> {
                    resetHeartbeat(client)
                }

                "voice_offer", "voice_answer", "voice_ice" -> {
                    val serverMsg = ServerMessage(
                        sdpType = clientMessage.sdpType,
                        sdp = clientMessage.sdp,
                        candidate = clientMessage.candidate,
                        sdpMid = clientMessage.sdpMid,
                        sdpMLineIndex = clientMessage.sdpMLineIndex
                    )

                    val clientsInGame = gameToClients[gameId] ?: return
                    val otherClients = clientsInGame.filter { it != client }

                    if (otherClients.isNotEmpty()) {
                        otherClients.forEach { otherClient ->
                            otherClient.sendEvent("voice", Json.encodeToString(serverMsg))
                        }
                    } else {
                        pendingVoiceMsgs.getOrPut(gameId) { mutableListOf() }.add(serverMsg)
                    }
                }

                else -> {
                    client.sendEvent("error", "Unknown action")
                }
            }
        } catch (e: Exception) {
            client.sendEvent("error", "Invalid message")
        }
    }

    private fun resetHeartbeat(client: com.corundumstudio.socketio.SocketIOClient) {
        heartbeatJobs[client]?.cancel()
        heartbeatJobs[client] = scope.launch {
            delay(10000L)
            removeClient(client)
        }
    }

    fun removeClient(client: com.corundumstudio.socketio.SocketIOClient) {
        val gameId = clientToGameId.remove(client)
        heartbeatJobs[client]?.cancel()
        heartbeatJobs.remove(client)

        if (gameId != null) {
            val clientsInGame = gameToClients[gameId]
            clientsInGame?.remove(client)
            if (clientsInGame.isNullOrEmpty()) {
                gameToClients.remove(gameId)
                usedGameIds.remove(gameId)
                pendingVoiceMsgs.remove(gameId)
            }
        }
    }

    fun generateGameId(): String {
        var gameId: String
        do {
            gameId = String.format("%03d", kotlin.random.Random.nextInt(0, 1000))
        } while (usedGameIds.contains(gameId))
        return gameId
    }
}

@Serializable
data class ClientMessage(
    val action: String,
    val gameId: String? = null,
    val username: String? = null,
    val sdpType: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

@Serializable
data class ServerMessage(
    val sdpType: String? = null,
    val sdp: String? = null,
    val candidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null
)

@Serializable
data class JoinedResponse(
    val username: String
)