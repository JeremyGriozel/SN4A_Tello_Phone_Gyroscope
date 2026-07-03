package com.example.telloeducontroller.tello

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.telloeducontroller.net.TelloWifiBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

private const val TAG = "TelloController"
private const val TELLO_IP = "192.168.10.1"
private const val COMMAND_PORT = 8889
private const val STATE_PORT = 8890

private const val RC_INTERVAL_MS = 100L // 10 Hz : sert aussi de "heartbeat" (le Tello atterrit seul après 15 s sans commande)
private const val BATTERY_POLL_INTERVAL_MS = 15_000L
private const val TAKEOFF_TARGET_HEIGHT_CM = 50
private const val MIN_MOVE_CM = 20 // pas minimal accepté par les commandes "up x" / "down x" du SDK

enum class TelloConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class TelloUiState(
    val connection: TelloConnectionState = TelloConnectionState.DISCONNECTED,
    val isFlying: Boolean = false,
    val batteryPercent: Int? = null,
    val lastMessage: String = ""
)

private data class Sticks(val roll: Int = 0, val pitch: Int = 0, val throttle: Int = 0, val yaw: Int = 0)

/**
 * Pilote un drone Tello EDU via le protocole texte UDP du SDK 2.0 (commande sur le port 8889,
 * état sur le port 8890). Envoie en continu des trames "rc a b c d" (roulis, tangage, altitude,
 * lacet) qui servent aussi de heartbeat pour éviter l'atterrissage de sécurité automatique.
 */
class TelloController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wifiBinder = TelloWifiBinder(context)
    private val telloAddress: InetAddress by lazy { InetAddress.getByName(TELLO_IP) }

    private var commandSocket: DatagramSocket? = null
    private var stateSocket: DatagramSocket? = null

    private val _uiState = mutableStateOf(TelloUiState())
    val uiState: State<TelloUiState> get() = _uiState

    @Volatile private var sticks = Sticks()
    @Volatile private var rcPaused = false

    private val criticalSendMutex = Mutex()
    private val pendingResponses = Channel<String>(Channel.CONFLATED)

    private var rcJob: Job? = null
    private var batteryJob: Job? = null
    private var receiveJob: Job? = null

    /** Met à jour les 4 voies envoyées en continu ; bornées à [-100, 100]. */
    fun updateSticks(roll: Int, pitch: Int, throttle: Int, yaw: Int) {
        sticks = Sticks(roll.coerceIn(-100, 100), pitch.coerceIn(-100, 100), throttle.coerceIn(-100, 100), yaw.coerceIn(-100, 100))
    }

    fun connect() {
        val current = _uiState.value.connection
        if (current == TelloConnectionState.CONNECTING || current == TelloConnectionState.CONNECTED) return

        _uiState.value = _uiState.value.copy(connection = TelloConnectionState.CONNECTING, lastMessage = "Connexion au drone...")

        wifiBinder.bindToTelloWifi(
            onResult = { bound ->
                if (!bound) {
                    AppLog.w(TAG, "Aucun réseau Wi-Fi correspondant trouvé (timeout)")
                    _uiState.value = _uiState.value.copy(
                        connection = TelloConnectionState.FAILED,
                        lastMessage = "Connecte le Wi-Fi du téléphone à \"TELLO-...\" puis réessaie"
                    )
                } else {
                    scope.launch { openSocketsAndHandshake() }
                }
            },
            onLost = {
                // Le téléphone a quitté le Wi-Fi du Tello (ex: reconnexion auto à un autre
                // réseau enregistré comme "eduroam"). Les sockets existants ne servent plus à
                // rien : on les ferme pour qu'une reconnexion ultérieure reparte proprement.
                AppLog.w(TAG, "Wi-Fi du Tello perdu")
                closeExistingConnection()
                _uiState.value = _uiState.value.copy(
                    connection = TelloConnectionState.FAILED,
                    lastMessage = if (_uiState.value.isFlying) {
                        "Wi-Fi du drone perdu en plein vol — il atterrira seul sous 15s sans commande. Reconnecte-toi puis réessaie."
                    } else {
                        "Wi-Fi du drone perdu (reconnecté à un autre réseau ?) — reconnecte-toi puis réessaie"
                    }
                )
            }
        )
    }

    private suspend fun openSocketsAndHandshake() {
        try {
            // Une tentative précédente (ex: "Réessayer la connexion") a pu laisser des sockets
            // ouverts, notamment sur le port fixe 8890 : sans cette fermeture, un nouveau bind sur
            // ce port échoue avec "EADDRINUSE".
            closeExistingConnection()

            commandSocket = DatagramSocket().apply { soTimeout = 2000 }
            stateSocket = DatagramSocket(STATE_PORT).apply { soTimeout = 2000 }
            AppLog.d(TAG, "Sockets ouverts, socket commande liée au port local ${commandSocket?.localPort}")
            receiveJob = scope.launch { receiveLoop() }

            val response = sendCommandAwaitingResponse("command", timeoutMs = 3000)
            if (response.equals("ok", ignoreCase = true)) {
                _uiState.value = _uiState.value.copy(connection = TelloConnectionState.CONNECTED, lastMessage = "Connecté au Tello")
                startRcLoop()
                startBatteryLoop()
            } else {
                AppLog.w(TAG, "Handshake \"command\" échoué, réponse=$response")
                _uiState.value = _uiState.value.copy(
                    connection = TelloConnectionState.FAILED,
                    lastMessage = "Pas de réponse du drone (vérifie le Wi-Fi \"TELLO-...\")"
                )
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Erreur lors de l'ouverture des sockets / handshake", e)
            _uiState.value = _uiState.value.copy(connection = TelloConnectionState.FAILED, lastMessage = "Erreur de connexion: ${e.message}")
        }
    }

    suspend fun takeoff() {
        if (_uiState.value.connection != TelloConnectionState.CONNECTED || _uiState.value.isFlying) return

        AppLog.d(TAG, "takeoff() demandé")
        _uiState.value = _uiState.value.copy(lastMessage = "Décollage...")
        val response = sendCommandAwaitingResponse("takeoff", timeoutMs = 8000)
        if (!response.equals("ok", ignoreCase = true)) {
            AppLog.w(TAG, "takeoff() échoué, réponse=$response")
            _uiState.value = _uiState.value.copy(lastMessage = "Échec du décollage (${response ?: "aucune réponse"})")
            return
        }
        _uiState.value = _uiState.value.copy(isFlying = true, lastMessage = "En vol, stabilisation de l'altitude...")

        // Le décollage automatique ne vise pas une hauteur précise. On lit la hauteur réelle "h"
        // dans le flux d'état puis on corrige d'un seul "up"/"down" pour se stabiliser vers 50 cm.
        // Le SDK n'accepte que des déplacements d'au moins 20 cm : en dessous, pas de correction.
        val height = readLatestStateHeightCm(totalTimeoutMs = 2500)
        AppLog.d(TAG, "Hauteur lue après décollage: ${height ?: "inconnue"} cm")
        if (height != null) {
            val delta = TAKEOFF_TARGET_HEIGHT_CM - height
            when {
                delta >= MIN_MOVE_CM -> sendCommandAwaitingResponse("up $delta", timeoutMs = 5000)
                -delta >= MIN_MOVE_CM -> sendCommandAwaitingResponse("down ${-delta}", timeoutMs = 5000)
            }
            _uiState.value = _uiState.value.copy(lastMessage = "En vol (~50 cm), altitude pilotée par les boutons")
        } else {
            _uiState.value = _uiState.value.copy(lastMessage = "En vol (hauteur inconnue, décollage sans ajustement fin)")
        }
    }

    suspend fun land() {
        if (_uiState.value.connection != TelloConnectionState.CONNECTED || !_uiState.value.isFlying) return

        updateSticks(0, 0, 0, 0)
        _uiState.value = _uiState.value.copy(lastMessage = "Atterrissage...")
        val response = sendCommandAwaitingResponse("land", timeoutMs = 8000)
        _uiState.value = _uiState.value.copy(
            isFlying = false,
            lastMessage = if (response.equals("ok", ignoreCase = true)) "Atterri" else "Réponse atterrissage: ${response ?: "aucune"}"
        )
    }

    /** À appeler quand l'écran de pilotage quitte la composition : atterrit si besoin et libère les sockets. */
    fun shutdown() {
        rcJob?.cancel()
        batteryJob?.cancel()
        scope.launch {
            if (_uiState.value.isFlying) {
                sendRaw("land")
                delay(300)
            }
            closeExistingConnection()
            wifiBinder.release()
        }
    }

    /** Ferme sockets et coroutines d'une éventuelle tentative précédente avant d'en ouvrir de nouveaux. */
    private fun closeExistingConnection() {
        rcJob?.cancel()
        rcJob = null
        batteryJob?.cancel()
        batteryJob = null
        receiveJob?.cancel()
        receiveJob = null
        commandSocket?.close()
        commandSocket = null
        stateSocket?.close()
        stateSocket = null
    }

    private fun startRcLoop() {
        rcJob = scope.launch {
            while (isActive) {
                if (!rcPaused) {
                    val s = sticks
                    sendRaw("rc ${s.roll} ${s.pitch} ${s.throttle} ${s.yaw}")
                }
                delay(RC_INTERVAL_MS)
            }
        }
    }

    private fun startBatteryLoop() {
        batteryJob = scope.launch {
            while (isActive) {
                val response = sendCommandAwaitingResponse("battery?", timeoutMs = 1000)
                response?.toIntOrNull()?.let { battery ->
                    _uiState.value = _uiState.value.copy(batteryPercent = battery)
                }
                delay(BATTERY_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun receiveLoop() {
        val socket = commandSocket ?: return
        val buffer = ByteArray(1024)
        while (scope.isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length).trim()
                AppLog.d(TAG, "<- \"$text\" de ${packet.address}:${packet.port}")
                pendingResponses.trySend(text)
            } catch (_: SocketTimeoutException) {
                // Normal : on reboucle simplement pour vérifier que le scope est toujours actif.
            } catch (e: Exception) {
                AppLog.e(TAG, "Erreur de réception sur le socket commande", e)
                if (!scope.isActive) break
            }
        }
    }

    /**
     * Envoie une commande "critique" (command/takeoff/land/battery?) et attend sa réponse.
     * Forcé sur Dispatchers.IO : takeoff()/land() sont appelés depuis l'UI via
     * rememberCoroutineScope() (thread principal par défaut), et un envoi UDP sur le thread
     * principal lève NetworkOnMainThreadException.
     */
    private suspend fun sendCommandAwaitingResponse(command: String, timeoutMs: Long): String? =
        withContext(Dispatchers.IO) {
            criticalSendMutex.withLock {
                rcPaused = true
                try {
                    // Laisse le temps à un éventuel "ok" encore en vol, émis par une trame "rc"
                    // envoyée juste avant la pause, d'arriver avant de purger : sinon il pourrait
                    // être confondu avec la réponse de la commande qu'on s'apprête à envoyer.
                    delay(RC_INTERVAL_MS + 50)
                    while (pendingResponses.tryReceive().isSuccess) { /* purge une réponse obsolète */ }
                    sendRaw(command)
                    val result = withTimeoutOrNull(timeoutMs) { pendingResponses.receive() }
                    if (result == null) AppLog.w(TAG, "Timeout (${timeoutMs}ms) en attente de la réponse à \"$command\"")
                    result
                } finally {
                    rcPaused = false
                }
            }
        }

    private fun sendRaw(command: String) {
        val socket = commandSocket
        if (socket == null) {
            AppLog.w(TAG, "sendRaw(\"$command\") ignoré : socket commande non initialisé")
            return
        }
        try {
            val bytes = command.toByteArray()
            socket.send(DatagramPacket(bytes, bytes.size, telloAddress, COMMAND_PORT))
            // Les trames "rc" partent à 10 Hz : ne pas les journaliser pour ne pas noyer les logs.
            if (!command.startsWith("rc ")) AppLog.d(TAG, "-> \"$command\"")
        } catch (e: Exception) {
            AppLog.e(TAG, "Échec d'envoi de \"$command\"", e)
        }
    }

    /** Draine le flux d'état (10 Hz) pour renvoyer la hauteur "h" (cm) la plus récente disponible. */
    private suspend fun readLatestStateHeightCm(totalTimeoutMs: Long): Int? = withContext(Dispatchers.IO) {
        val socket = stateSocket ?: return@withContext null
        val buffer = ByteArray(1024)
        var lastHeight: Int? = null
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        socket.soTimeout = 300
        while (System.currentTimeMillis() < deadline) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                parseHeight(String(packet.data, 0, packet.length))?.let { lastHeight = it }
            } catch (_: SocketTimeoutException) {
                if (lastHeight != null) break
            } catch (_: Exception) {
                break
            }
        }
        lastHeight
    }

    private fun parseHeight(state: String): Int? =
        Regex("""h:(-?\d+)""").find(state)?.groupValues?.get(1)?.toIntOrNull()
}

@Composable
fun rememberTelloController(): TelloController {
    val context = LocalContext.current
    val controller = remember { TelloController(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { controller.shutdown() }
    }
    return controller
}
