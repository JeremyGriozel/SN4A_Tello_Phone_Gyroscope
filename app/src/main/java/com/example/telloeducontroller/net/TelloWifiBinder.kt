package com.example.telloeducontroller.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Le Wi-Fi du Tello ne fournit aucun accès Internet. Par défaut, Android privilégie une autre
 * interface (données mobiles) pour tout le trafic applicatif dès qu'une connexion Internet est
 * disponible ailleurs, ce qui empêche silencieusement les paquets UDP d'atteindre le drone. On
 * force donc explicitement le processus à utiliser le réseau Wi-Fi actif, même sans connectivité
 * Internet.
 */
class TelloWifiBinder(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun bindToTelloWifi(onResult: (Boolean) -> Unit) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.bindProcessToNetwork(network)
                onResult(true)
            }

            override fun onUnavailable() {
                onResult(false)
            }
        }
        callback = networkCallback
        connectivityManager.requestNetwork(request, networkCallback)
    }

    fun release() {
        callback?.let {
            runCatching { connectivityManager.unregisterNetworkCallback(it) }
        }
        callback = null
        connectivityManager.bindProcessToNetwork(null)
    }
}
