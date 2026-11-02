package app.fdroidserver

import app.morphe.engine.util.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger as JulLogger

/**
 * Guards local delta `0004-logger-through-jul.patch`: the vendored engine's
 * [Logger] has to emit through `java.util.logging`, because that is the only
 * way its records reach logback (see [LoggingBridge] and `logback.xml`).
 *
 * Upstream prints them straight to stdout/stderr instead, so a re-sync that
 * drops or fuzzily applies that delta would silently put every engine line -
 * all the progress and per-patch failure detail of an unattended patch run -
 * outside `LOG_LEVEL` and the shared log format. Nothing else would fail.
 */
class EngineLoggingTest {

    private class Capture : Handler() {
        val records = mutableListOf<LogRecord>()
        override fun publish(record: LogRecord) { records += record }
        override fun flush() {}
        override fun close() {}
    }

    private fun capture(block: () -> Unit): List<LogRecord> {
        val julLogger = JulLogger.getLogger("app.morphe.engine")
        val handler = Capture()
        val previousLevel = julLogger.level
        julLogger.level = Level.ALL
        julLogger.addHandler(handler)
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = previousLevel
        }
        return handler.records
    }

    @Test
    fun `engine records reach java util logging`() {
        val records = capture { Logger.info("patching com.example 1.2.3") }

        assertEquals(1, records.size)
        assertEquals("patching com.example 1.2.3", records.single().message)
        assertEquals(Level.INFO, records.single().level)
    }

    @Test
    fun `engine levels map onto java util logging levels`() {
        val records = capture {
            Logger.debug("debug")
            Logger.warn("warn")
            Logger.error("error")
        }

        assertEquals(
            listOf(Level.FINE, Level.WARNING, Level.SEVERE),
            records.map { it.level },
        )
    }

    @Test
    fun `an engine error with a throwable carries the stack trace`() {
        val records = capture { Logger.error("signing failed", IllegalStateException("no keystore")) }

        assertTrue(
            records.single().message.contains("IllegalStateException: no keystore"),
            "expected the stack trace in the record, got: ${records.single().message}",
        )
    }
}
