package com.example.telloeducontroller.tello

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Journal en mémoire, affiché directement dans l'app pour pouvoir déboguer en vol sans accès à
 * `adb logcat` (utile une fois loin de l'ordinateur, avec juste le téléphone en main).
 *
 * Le buffer est un simple ArrayDeque protégé par un verrou, pas une SnapshotStateList : les écritures
 * viennent de threads réseau (rc loop, réception UDP...) pendant que l'UI lit `snapshot()` pour
 * peupler une LazyColumn. Faire lire/indexer directement une SnapshotStateList mutée en parallèle par
 * un autre thread expose à un retrait (removeAt) en plein milieu d'une passe de layout, d'où
 * l'IndexOutOfBoundsException observée une fois le buffer plein (le trim par `removeAt(0)` ne
 * démarre qu'à ce moment-là). `version` ne sert qu'à signaler à Compose qu'il faut relire.
 */
object AppLog {
    private const val MAX_LINES = 300
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE)
    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_LINES)

    private val _version = mutableStateOf(0)
    val version: State<Int> get() = _version

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append(tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append(tag, "! $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        // Le type d'exception est toujours affiché (même sans message), pour ne pas perdre le
        // diagnostic quand throwable.message est vide (fréquent pour les erreurs réseau).
        val detail = throwable?.let { " (${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""})" } ?: ""
        append(tag, "ERREUR $message$detail")
    }

    fun clear() {
        synchronized(lock) { buffer.clear() }
        _version.value++
    }

    /** Copie stable et immuable du buffer, à utiliser pour tout affichage (LazyColumn, copie...). */
    fun snapshot(): List<String> = synchronized(lock) { buffer.toList() }

    private fun append(tag: String, message: String) {
        synchronized(lock) {
            buffer.addLast("${timeFormat.format(System.currentTimeMillis())} [$tag] $message")
            if (buffer.size > MAX_LINES) buffer.removeFirst()
        }
        _version.value++
    }
}
