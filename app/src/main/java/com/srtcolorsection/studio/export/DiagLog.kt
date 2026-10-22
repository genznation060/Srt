package com.srtcolorsection.studio.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagLog {
    private val lines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun add(message: String) {
        val line = "${clock.format(Date())}  $message"
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > 250) lines.removeFirst()
        }
    }

    fun text(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
    }
}
