package io.wearbridge.watch.diagnostics

import android.bluetooth.BluetoothDevice
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * The only logging path in the app. It keeps a ring buffer for the on-watch Diagnostics screen.
 *
 * NEVER pass notification titles/bodies, contact names, phone numbers, device names or health
 * values. Use [Redact] for identifiers. Bundle IDs, categories, states, status codes and
 * latencies are fine.
 */
object DiagLog {
    private const val TAG = "Bridge"
    private const val CAPACITY = 200
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val buffer = ArrayDeque<String>(CAPACITY)
    private val _entries = MutableStateFlow<List<String>>(emptyList())
    val entries: StateFlow<List<String>> = _entries

    @Synchronized
    fun i(message: String) {
        Log.i(TAG, message)
        if (buffer.size == CAPACITY) buffer.removeFirst()
        buffer.addLast("${LocalTime.now().format(TIME)} $message")
        _entries.value = buffer.toList()
    }

    fun w(message: String) {
        Log.w(TAG, message)
        i("⚠ $message")
    }
}

object Redact {
    @Suppress("UNUSED_PARAMETER")
    fun uid(uid: Long): String = "***"

    /** A short, non-reversible tag so log lines about the same device can be correlated. */
    fun device(device: BluetoothDevice?): String =
        device?.address?.let { "dev#" + (it.hashCode() and 0xFFFF).toString(16) } ?: "dev#none"
}
