package com.example.telloeducontroller.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

private const val BIND_TIMEOUT_MS = 8000L

/**
 * Le Wi-Fi du Tello ne fournit aucun accès Internet. Par défaut, Android privilégie une autre
 * interface (données mobiles) pour tout le trafic applicatif dès qu'une connexion Internet est
 * disponible ailleurs, ce qui empêche silencieusement les paquets UDP d'atteindre le drone. On
 * force donc explicitement le processus à utiliser le réseau Wi-Fi actif, même sans connectivité
 * Internet.
 *
 * `requestNetwork()` sans réseau Wi-Fi correspondant (téléphone non connecté à un Wi-Fi, ou
 * reconnecté entretemps à un autre réseau enregistré) n'échoue jamais de lui-même : il attend
 * indéfiniment. On ajoute donc notre propre timeout pour pouvoir remonter une erreur claire.
 */
class TelloWifiBinder(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * [onLost] est appelé si le réseau Wi-Fi auquel le process est lié disparaît après coup (ex:
     * le téléphone bascule automatiquement vers un autre Wi-Fi enregistré comme "eduroam", ou perd
     * le signal du Tello) : sans ça, les sockets restent liés à un réseau mort et échouent
     * silencieusement jusqu'au prochain redémarrage manuel de la connexion.
     */
    fun bindToTelloWifi(onResult: (Boolean) -> Unit, onLost: () -> Unit = {}) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val resultDelivered = AtomicBoolean(false)
        fun deliver(success: Boolean) {
            if (resultDelivered.compareAndSet(false, true)) onResult(success)
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                deliver(true)
            }

            override fun onUnavailable() {
                deliver(false)
            }

            override fun onLost(network: Network) {
                connectivityManager.bindProcessToNetwork(null)
                if (resultDelivered.get()) onLost()
            }
        }
        callback = networkCallback
        connectivityManager.requestNetwork(request, networkCallback)
        mainHandler.postDelayed({ deliver(false) }, BIND_TIMEOUT_MS)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        callback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback = null
        connectivityManager.bindProcessToNetwork(null)
    }
}
