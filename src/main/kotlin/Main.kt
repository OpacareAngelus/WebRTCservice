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
    println("Voice signaling server started on port 8081")
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
        println("Client connected: ${client.sessionId}")
    }

    fun handleMessage(client: com.corundumstudio.socketio.SocketIOClient, message: String) {
        println("Received message from client ${client.sessionId}: $message")
        try {
            val clientMessage = Json.decodeFromString<ClientMessage>(message)
            val gameId = clientMessage.gameId ?: clientToGameId[client] ?: run {
                println("No gameId for client ${client.sessionId}")
                return
            }
            println("Processing action '${clientMessage.action}' for gameId $gameId from client ${client.sessionId}")

            if (!usedGameIds.contains(gameId)) {
                usedGameIds.add(gameId)
                println("Added new gameId: $gameId")
            }

            when (clientMessage.action) {
                "join" -> {
                    var username = clientMessage.username ?: UUID.randomUUID().toString().substring(0, 8)
                    val clientsInGame = gameToClients.getOrPut(gameId) { mutableListOf() }
                    println("Join attempt for gameId $gameId, current players: ${clientsInGame.size}")

                    if (clientsInGame.size >= 2) {
                        client.sendEvent("error", "Game full")
                        println("Game full for gameId $gameId")
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
                    println("Client ${client.sessionId} joined gameId $gameId as $username")

                    client.sendEvent("joined", Json.encodeToString(JoinedResponse(username)))
                    println("Sent 'joined' to client ${client.sessionId}")

                    val otherClients = clientsInGame.filter { it != client }
                    if (otherClients.isNotEmpty()) {
                        pendingVoiceMsgs[gameId]?.let { msgs ->
                            msgs.forEach { pendingMsg ->
                                client.sendEvent("voice", Json.encodeToString(pendingMsg))
                            }
                            pendingVoiceMsgs.remove(gameId)
                            println("Sent ${msgs.size} pending voice messages to client ${client.sessionId} for gameId $gameId")
                        }
                    }
                }

                "heartbeat" -> {
                    resetHeartbeat(client)
                    println("Heartbeat received from client ${client.sessionId} for gameId $gameId")
                }

                "voice_offer", "voice_answer", "voice_ice" -> {
                    val serverMsg = ServerMessage(
                        sdpType = clientMessage.sdpType,
                        sdp = clientMessage.sdp,
                        candidate = clientMessage.candidate,
                        sdpMid = clientMessage.sdpMid,
                        sdpMLineIndex = clientMessage.sdpMLineIndex
                    )
                    println("Voice message type '${clientMessage.action}' received for gameId $gameId")

                    val clientsInGame = gameToClients[gameId] ?: return
                    val otherClients = clientsInGame.filter { it != client && it.isChannelOpen }

                    if (otherClients.isNotEmpty()) {
                        otherClients.forEach { otherClient ->
                            otherClient.sendEvent("voice", Json.encodeToString(serverMsg))
                            println("Forwarded voice message to other client ${otherClient.sessionId}")
                        }
                    } else {
                        pendingVoiceMsgs.getOrPut(gameId) { mutableListOf() }.add(serverMsg)
                        println("No other clients in gameId $gameId, pending voice message")
                    }
                }

                else -> {
                    client.sendEvent("error", "Unknown action")
                    println("Unknown action '${clientMessage.action}' from client ${client.sessionId}")
                }
            }
        } catch (e: Exception) {
            client.sendEvent("error", "Invalid message")
            println("Error handling message from client ${client.sessionId}: ${e.message}")
        }
    }

    private fun resetHeartbeat(client: com.corundumstudio.socketio.SocketIOClient) {
        heartbeatJobs[client]?.cancel()
        heartbeatJobs[client] = scope.launch {
            delay(10000L)
            removeClient(client)
        }
        println("Reset heartbeat for client ${client.sessionId}")
    }

    fun removeClient(client: com.corundumstudio.socketio.SocketIOClient) {
        val transport = client.transport.name
        println("Disconnect attempt for client ${client.sessionId} with transport $transport")
        if (transport != "WEBSOCKET") {
            println("Ignoring disconnect for non-WebSocket transport $transport")
            return
        }
        val gameId = clientToGameId.remove(client)
        heartbeatJobs[client]?.cancel()
        heartbeatJobs.remove(client)
        println("Client fully disconnected: ${client.sessionId}, gameId: $gameId")

        if (gameId != null) {
            val clientsInGame = gameToClients[gameId]
            clientsInGame?.remove(client)
            if (clientsInGame.isNullOrEmpty()) {
                gameToClients.remove(gameId)
                usedGameIds.remove(gameId)
                pendingVoiceMsgs.remove(gameId)
                println("Removed empty gameId $gameId")
            }
        }
    }

    fun generateGameId(): String {
        var gameId: String
        do {
            gameId = String.format("%03d", kotlin.random.Random.nextInt(0, 1000))
        } while (usedGameIds.contains(gameId))
        println("Generated new gameId: $gameId")
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