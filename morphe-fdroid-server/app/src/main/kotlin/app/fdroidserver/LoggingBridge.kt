package app.fdroidserver

import org.slf4j.bridge.SLF4JBridgeHandler

/**
 * Routes `java.util.logging` into SLF4J/logback.
 *
 * Everything under `app.fdroidserver` logs through SLF4J, but the vendored
 * `app.morphe.engine` package and morphe-patcher itself both log through JUL
 * (they're written for a desktop app that has no SLF4J on its classpath). With
 * no bridge those records go to JUL's own default console handler: a different
 * format, a different level, and - in the patch worker child process, whose
 * stdout is inherited by the admin server - interleaved with our own lines but
 * not obeying `LOG_LEVEL`. Progress and per-patch failure detail from the
 * engine is exactly what you want in the logs when an unattended patch run
 * goes wrong, so it's worth having it come out the same pipe as everything
 * else.
 *
 * Called from both entry points ([main] and
 * [app.fdroidserver.patching.PatchWorkerEntryPoint]) - the worker runs in its
 * own JVM and doesn't inherit anything installed here.
 */
object LoggingBridge {
    fun install() {
        // Drop JUL's default console handler first, otherwise every record is
        // printed twice (once by JUL, once by logback via the bridge).
        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()
    }
}
