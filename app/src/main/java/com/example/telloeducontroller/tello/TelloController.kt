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
import kotlin.coroutines.coroutineContext

private const val TAG = "TelloController"
private const val TELLO_IP = "192.168.10.1"
private const val COMMAND_PORT = 8889
private const val STATE_PORT = 8890

private const val RC_INTERVAL_MS = 100L // 10 Hz : sert aussi de "heartbeat" (le Tello atterrit seul après 15 s sans commande)
private const val BATTERY_POLL_INTERVAL_MS = 15_000L
private const val TAKEOFF_TARGET_HEIGHT_CM = 50
private const val MIN_MOVE_CM = 20 // pas minimal accepté par les commandes "up x" / "down x" du SDK
private const val MIN_SEND_INTERVAL_MS = 50L // délai minimal imposé entre deux envois quelconques sur le socket commande
private const val STICK_RAMP_STEP = 6 // variation max de roulis/tangage/lacet par tick "rc" (100 ms) : lisse les à-coups
private const val ALTITUDE_HOLD_KP = 3 // stick de correction par cm d'écart à la consigne d'altitude
private const val ALTITUDE_HOLD_MAX_STICK = 30 // borne du throttle correctif quand on tient l'altitude
private const val TAKEOFF_CONFIRM_TIMEOUT_MS = 10_000L // le "ok" de takeoff peut arriver après plusieurs secondes (montée + stabilisation) ; on reste sous les 15 s d'atterrissage auto du Tello
private const val TAKEOFF_HEIGHT_CONFIRM_DELTA_CM = 15 // hausse d'altitude (tof) suffisante pour confirmer un décollage même si "ok" est perdu/en retard
private const val TAKEOFF_POLL_INTERVAL_MS = 150L

enum class TelloConnectionState { DISCONNECTED, CONNECTING, CONNECTED, FAILED }

data class TelloUiState(
    val connection: TelloConnectionState = TelloConnectionState.DISCONNECTED,
    val isFlying: Boolean = false,
    val batteryPercent: Int? = null,
    val heightCm: Int? = null,
    val targetHeightCm: Int = TAKEOFF_TARGET_HEIGHT_CM,
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

    // Garde-fou d'espacement minimal (50 ms) entre deux envois quelconques sur le socket commande.
    private val sendGateMutex = Mutex()
    @Volatile private var lastSendAtMs = 0L

    // Dernières valeurs envoyées pour roulis/tangage/lacet : rapprochées par pas de [STICK_RAMP_STEP]
    // de la cible ([sticks]) à chaque tick "rc", pour lisser les à-coups d'attitude du téléphone.
    @Volatile private var sentRoll = 0
    @Volatile private var sentPitch = 0
    @Volatile private var sentYaw = 0

    // Consigne d'altitude tenue quand ni Monter ni Descendre n'est pressé : recalée sur l'altitude
    // mesurée à chaque relâchement, pour que le drone conserve la position atteinte.
    @Volatile private var holdTargetHeightCm = TAKEOFF_TARGET_HEIGHT_CM

    private var rcJob: Job? = null
    private var batteryJob: Job? = null
    private var receiveJob: Job? = null
    private var stateJob: Job? = null

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
            stateJob = scope.launch { stateReceiveLoop() }

            val response = sendCommandAwaitingResponse("command", timeoutMs = 3000)
            if (response.equals("ok", ignoreCase = true)) {
                _uiState.value = _uiState.value.copy(connection = TelloConnectionState.CONNECTED, lastMessage = "Connecté au Tello")
                // Le flux "rc" ne démarre qu'après un décollage réussi (voir takeoff()) : envoyé en
                // continu pendant que le drone est encore posé au sol, il entre en conflit avec la
                // séquence auto-takeoff du Tello et la fait échouer avec "error".
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
        if (!sendTakeoffAwaitingConfirmation()) {
            _uiState.value = _uiState.value.copy(lastMessage = "Échec du décollage")
            return
        }
        _uiState.value = _uiState.value.copy(isFlying = true, lastMessage = "En vol, stabilisation de l'altitude...")

        // Le décollage automatique ne vise pas une hauteur précise. On lit la hauteur réelle "h"
        // (tenue à jour en continu par stateReceiveLoop()) puis on corrige d'un seul "up"/"down"
        // pour se stabiliser vers 50 cm. Le SDK n'accepte que des déplacements d'au moins 20 cm :
        // en dessous, pas de correction.
        val height = awaitHeightCm(totalTimeoutMs = 2500)
        AppLog.d(TAG, "Hauteur lue après décollage: ${height ?: "inconnue"} cm")
        if (height != null) {
            val delta = TAKEOFF_TARGET_HEIGHT_CM - height 
            var finalHeight = height
            when {
                delta >= MIN_MOVE_CM -> {
                    sendCommandAwaitingResponse("up $delta", timeoutMs = 5000)
                    finalHeight = TAKEOFF_TARGET_HEIGHT_CM
                }
                -delta >= MIN_MOVE_CM -> {
                    sendCommandAwaitingResponse("down ${-delta}", timeoutMs = 5000)
                    finalHeight = TAKEOFF_TARGET_HEIGHT_CM
                }
            }
            holdTargetHeightCm = finalHeight
            _uiState.value = _uiState.value.copy(lastMessage = "En vol (~50 cm), altitude régulée automatiquement", targetHeightCm = finalHeight)
        } else {
            holdTargetHeightCm = TAKEOFF_TARGET_HEIGHT_CM
            _uiState.value = _uiState.value.copy(lastMessage = "En vol (hauteur inconnue, décollage sans ajustement fin)", targetHeightCm = TAKEOFF_TARGET_HEIGHT_CM)
        }

        // Démarré seulement maintenant : le Tello garde la main sur une bonne partie de la montée
        // et de la stabilisation initiales après "takeoff". Un flux "rc" (même à 0) envoyé pendant
        // cette phase interrompt sa propre séquence de montée ; il n'a jamais vraiment quitté le
        // sol et coupe les moteurs par sécurité ("error Motor stop" au premier ordre suivant).
        startRcLoop()
    }

    suspend fun land() {
        if (_uiState.value.connection != TelloConnectionState.CONNECTED || !_uiState.value.isFlying) return

        updateSticks(0, 0, 0, 0)
        _uiState.value = _uiState.value.copy(lastMessage = "Atterrissage...")
        val response = sendCommandAwaitingResponse("land", timeoutMs = 8000)
        // Stoppé ici : un flux "rc" encore actif une fois posé empêcherait le prochain takeoff().
        rcJob?.cancel()
        rcJob = null
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
                sendRawGated("land")
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
        stateJob?.cancel()
        stateJob = null
        commandSocket?.close()
        commandSocket = null
        stateSocket?.close()
        stateSocket = null
    }

    private fun startRcLoop() {
        sentRoll = 0
        sentPitch = 0
        sentYaw = 0
        rcJob = scope.launch {
            while (isActive) {
                if (!rcPaused) {
                    val s = sticks
                    // Rapproche le manche envoyé de sa cible par petits pas ("par palier") plutôt
                    // que de la recopier telle quelle : limite les à-coups dus au bruit/aux
                    // mouvements brusques du téléphone.
                    sentRoll = rampTowards(sentRoll, s.roll, STICK_RAMP_STEP)
                    sentPitch = rampTowards(sentPitch, s.pitch, STICK_RAMP_STEP)
                    sentYaw = rampTowards(sentYaw, s.yaw, STICK_RAMP_STEP)
                    val throttleOut = resolveThrottle(s.throttle)
                    sendRawGated("rc $sentRoll $sentPitch $throttleOut $sentYaw")
                }
                delay(RC_INTERVAL_MS)
            }
        }
    }

    private fun rampTowards(current: Int, target: Int, maxStep: Int): Int {
        val diff = target - current
        return when {
            diff > maxStep -> current + maxStep
            diff < -maxStep -> current - maxStep
            else -> target
        }
    }

    /**
     * Manche de montée/descente effectivement envoyé : commande manuelle directe si l'utilisateur
     * appuie sur Monter/Descendre (et recale au passage la consigne tenue sur l'altitude atteinte),
     * sinon correction proportionnelle pour tenir la dernière consigne connue.
     */
    private fun resolveThrottle(manualThrottle: Int): Int {
        val current = _uiState.value.heightCm
        if (manualThrottle != 0) {
            if (current != null && current != holdTargetHeightCm) {
                holdTargetHeightCm = current
                _uiState.value = _uiState.value.copy(targetHeightCm = current)
            }
            return manualThrottle
        }
        if (current == null) return 0
        return (ALTITUDE_HOLD_KP * (holdTargetHeightCm - current)).coerceIn(-ALTITUDE_HOLD_MAX_STICK, ALTITUDE_HOLD_MAX_STICK)
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
        // Vérifie le job de cette boucle précise (receiveJob), pas `scope` : `scope` vit tant que
        // le contrôleur existe, donc `scope.isActive` reste vrai même après receiveJob.cancel(). Un
        // socket fermé fait que receive() lève immédiatement (sans bloquer) : avec le mauvais test,
        // la boucle tournait indéfiniment sans délai en journalisant la même erreur en rafale.
        while (coroutineContext[Job]?.isActive != false) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val text = String(packet.data, 0, packet.length).trim()
                AppLog.d(TAG, "<- \"$text\" de ${packet.address}:${packet.port}")
                pendingResponses.trySend(text)
            } catch (_: SocketTimeoutException) {
                // Normal : on reboucle simplement pour vérifier que le job est toujours actif.
            } catch (e: Exception) {
                AppLog.e(TAG, "Erreur de réception sur le socket commande", e)
                if (coroutineContext[Job]?.isActive == false) break
                delay(200) // évite une boucle serrée si l'erreur persiste sans que le job soit annulé
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
                    sendRawGated(command)
                    val result = withTimeoutOrNull(timeoutMs) { pendingResponses.receive() }
                    if (result == null) AppLog.w(TAG, "Timeout (${timeoutMs}ms) en attente de la réponse à \"$command\"")
                    result
                } finally {
                    rcPaused = false
                }
            }
        }

    /**
     * Envoie "takeoff" et attend sa confirmation. Le "ok" du SDK n'arrive qu'une fois la montée et
     * la stabilisation automatiques terminées, ce qui peut dépasser 8-9 s selon les conditions : un
     * timeout trop court fait croire à un échec alors que le drone a réellement décollé, et comme
     * aucun flux "rc" n'est encore démarré à ce stade, il finit par atterrir seul faute de heartbeat
     * (15 s sans commande). On confirme donc aussi via la hausse d'altitude mesurée par le capteur
     * "tof", au cas où la réponse texte serait perdue ou trop en retard.
     */
    private suspend fun sendTakeoffAwaitingConfirmation(): Boolean = withContext(Dispatchers.IO) {
        criticalSendMutex.withLock {
            rcPaused = true
            try {
                delay(RC_INTERVAL_MS + 50)
                while (pendingResponses.tryReceive().isSuccess) { /* purge une réponse obsolète */ }
                val baselineHeight = _uiState.value.heightCm ?: 10
                sendRawGated("takeoff")
                val deadline = System.currentTimeMillis() + TAKEOFF_CONFIRM_TIMEOUT_MS
                var confirmed = false
                while (System.currentTimeMillis() < deadline) {
                    val response = withTimeoutOrNull(TAKEOFF_POLL_INTERVAL_MS) { pendingResponses.receive() }
                    if (response != null) {
                        if (response.equals("ok", ignoreCase = true)) {
                            confirmed = true
                        } else {
                            AppLog.w(TAG, "takeoff() refusé par le drone (\"$response\")")
                        }
                        break
                    }
                    val height = _uiState.value.heightCm
                    if (height != null && height - baselineHeight >= TAKEOFF_HEIGHT_CONFIRM_DELTA_CM) {
                        AppLog.d(TAG, "Décollage confirmé via l'altitude ($height cm), \"ok\" non reçu à temps")
                        confirmed = true
                        break
                    }
                }
                if (!confirmed && System.currentTimeMillis() >= deadline) {
                    AppLog.w(TAG, "Timeout (${TAKEOFF_CONFIRM_TIMEOUT_MS}ms) en attente de confirmation de décollage")
                }
                confirmed
            } finally {
                rcPaused = false
            }
        }
    }

    /** Envoie [command] en respectant un écart minimal de [MIN_SEND_INTERVAL_MS] depuis le dernier envoi. */
    private suspend fun sendRawGated(command: String) {
        sendGateMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastSendAtMs
            if (elapsed < MIN_SEND_INTERVAL_MS) delay(MIN_SEND_INTERVAL_MS - elapsed)
            sendRaw(command)
            lastSendAtMs = System.currentTimeMillis()
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

    /**
     * Boucle de lecture continue du flux d'état (10 Hz, port 8890) : tient [TelloUiState.heightCm]
     * à jour pendant toute la durée de la connexion, pour permettre la régulation d'altitude et
     * l'affichage dans l'UI (plutôt qu'une lecture ponctuelle limitée à l'instant du décollage).
     */
    private suspend fun stateReceiveLoop() {
        val socket = stateSocket ?: return
        val buffer = ByteArray(1024)
        socket.soTimeout = 500
        while (coroutineContext[Job]?.isActive != false) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                parseAltitudeCm(String(packet.data, 0, packet.length))?.let { h ->
                    _uiState.value = _uiState.value.copy(heightCm = h)
                }
            } catch (_: SocketTimeoutException) {
                // Normal : on reboucle simplement pour vérifier que le job est toujours actif.
            } catch (e: Exception) {
                AppLog.e(TAG, "Erreur de réception sur le socket d'état", e)
                if (coroutineContext[Job]?.isActive == false) break
                delay(200) // évite une boucle serrée si l'erreur persiste sans que le job soit annulé
            }
        }
    }

    /** Attend jusqu'à [totalTimeoutMs] que [stateReceiveLoop] ait fourni au moins une hauteur. */
    private suspend fun awaitHeightCm(totalTimeoutMs: Long): Int? = withTimeoutOrNull(totalTimeoutMs) {
        while (_uiState.value.heightCm == null) delay(100)
        _uiState.value.heightCm
    }

    // Lit "tof" (capteur de distance à ultrasons/infrarouge sous le drone), pas "h" (estimation
    // barométrique/IMU, moins fiable près du sol). `\b` évite aussi de matcher "h:" à l'intérieur
    // d'un autre champ comme "pitch:" ou "temph:", ce que faisait l'ancien regex "h:(-?\d+)".
    private fun parseAltitudeCm(state: String): Int? =
        Regex("""\btof:(-?\d+)""").find(state)?.groupValues?.get(1)?.toIntOrNull()
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
