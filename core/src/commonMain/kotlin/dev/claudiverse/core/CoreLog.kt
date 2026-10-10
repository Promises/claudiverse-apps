package dev.claudiverse.core

/**
 * Where core's log lines go. Each app points [sink] at its own log (logcat on
 * Android, the unified log on macOS); until then they are dropped.
 */
object CoreLog {
    var sink: (String) -> Unit = {}

    internal fun info(message: String) = sink(message)
}
