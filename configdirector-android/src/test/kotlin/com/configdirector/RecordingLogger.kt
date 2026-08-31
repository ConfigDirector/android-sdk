package com.configdirector

import java.util.concurrent.CopyOnWriteArrayList

/** Collects what the SDK logs. The client logs from its own threads, so the lists are concurrent. */
class RecordingLogger(override val level: LogLevel = LogLevel.DEBUG) : ConfigDirectorLogger {
    val messages: MutableList<String> = CopyOnWriteArrayList()
    val errors: MutableList<Throwable?> = CopyOnWriteArrayList()

    override fun log(level: LogLevel, message: String, error: Throwable?) {
        messages += "$level: $message"
        errors += error
    }

    fun messagesContaining(text: String): List<String> = messages.filter { text in it }
}
