package app.memoryvault.platform.capture

/** SDK stop schedules a service-wide asynchronous close. A completed utterance needs only
 * listener removal and release; scheduling another stop can interrupt its successor.
 */
internal class PcmEngineCleanup(
    private val stop: () -> Unit,
    private val removeListener: () -> Unit,
    private val release: () -> Unit,
    private val clear: () -> Unit,
) {
    var closed = false
        private set
    private var recognitionCompleted = false
    fun completed() { recognitionCompleted = true }
    fun close() {
        if (closed) return
        closed = true
        val failures = listOfNotNull(
            if (recognitionCompleted) null else runCatching(stop).exceptionOrNull(),
            runCatching(removeListener).exceptionOrNull(),
            runCatching(release).exceptionOrNull(),
            runCatching(clear).exceptionOrNull(),
        )
        failures.firstOrNull()?.let { throw it }
    }
}
