package com.vanillasource.eliot.eliotc.lsp.server

import cats.effect.unsafe.IORuntime
import org.eclipse.lsp4j.launch.LSPLauncher

/** Standalone entry point for the Eliot language server: speaks LSP (JSON-RPC) over this process's stdin/stdout, which
  * is how an editor — the client — drives a server it spawned. All logging is routed to stderr (see
  * `resources/eliot-lsp-log4j2.xml`) so it cannot corrupt the stdout protocol stream.
  *
  * The configuration is named explicitly, before anything logs: the server's classpath also carries the apidoc jar,
  * whose own `log4j2.xml` logs to stdout, and which of two same-named resources log4j finds first is up to the
  * classpath order.
  *
  * cats-effect's fiber tracing is turned off unless the command line set it, before the runtime exists, as the
  * compiler's own `Main` does: it costs a compilation 10% (`docs/compiler-performance.md` §10).
  */
object LspMain {
  def main(args: Array[String]): Unit = {
    val _        = System.setProperty("log4j2.configurationFile", "eliot-lsp-log4j2.xml")
    if (System.getProperty("cats.effect.tracing.mode") == null) {
      val _ = System.setProperty("cats.effect.tracing.mode", "none")
    }
    val service  = new EliotCompilationService(IORuntime.global)
    val server   = new EliotLanguageServer(service)
    val launcher = LSPLauncher.createServerLauncher(server, System.in, System.out)
    server.connect(launcher.getRemoteProxy)
    val _        = launcher.startListening().get() // blocks until the client closes the input stream
  }
}
