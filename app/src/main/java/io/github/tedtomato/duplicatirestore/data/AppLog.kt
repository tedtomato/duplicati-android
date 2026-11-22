package io.github.tedtomato.duplicatirestore.data

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-memory log shown under Settings > Diagnostics, so problems can be read on the phone. */
object AppLog {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    @Synchronized
    fun i(msg: String) {
        Log.i("DuplicatiRestore", msg)
        lines.addLast("${fmt.format(Date())} $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    fun e(msg: String, t: Throwable? = null) = i("ERROR $msg${t?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: ""}")

    @Synchronized
    fun text(): String = lines.joinToString("\n")
}
