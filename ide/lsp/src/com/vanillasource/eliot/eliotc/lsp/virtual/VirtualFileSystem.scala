package com.vanillasource.eliot.eliotc.lsp.virtual

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

/** An in-memory overlay of editor buffer contents, keyed by normalised absolute file path.
  *
  * The editor reports unsaved edits via `textDocument/didChange`; until those edits are saved the on-disk file is stale,
  * so the compiler must type-check the buffer, not the file. This overlay holds the live content of each open, modified
  * document and is consulted through the `vfs:` namespace facts: [[VfsStatProcessor]] serves the total per-file
  * override state the scan routes on ([[VfsRoutedMount]]), and [[VfsSourceContentProcessor]] serves the buffer content
  * for routed files. A file without an override is read from disk as
  * usual, so the overlay is fully transparent to the CLI (which never populates it) and to unopened files.
  *
  * Each override carries a strictly increasing `stamp` (a monotonic tick, not a wall-clock time). The stat processor
  * reports that stamp as the file's modification time, so any buffer change makes the leaf `FileStat` differ and the
  * incremental generator invalidates exactly the edited file's dependency cone — while re-setting the *same* content
  * (re-applied byte for byte) still recompiles only down to the equality cutoff. Stamps start near zero, so they never
  * collide with a real on-disk mtime: dropping an override (on close) flips the stat back to the disk time, which
  * differs from any stamp, so the file is re-read from disk.
  *
  * The overlay is mutated from the lsp4j notification threads and read from compilation threads; an [[AtomicReference]] CAS
  * loop keeps those consistent without locking.
  */
final class VirtualFileSystem {
  private val overrides = new AtomicReference[Map[Path, VirtualFileSystem.Document]](Map.empty)
  private val stampSeq  = new AtomicLong(0L)

  /** Record (or replace) the live buffer content for a document. No-op for non-`file:` URIs (e.g. untitled buffers),
    * which have no on-disk path the compiler can key by.
    */
  def update(uri: URI, content: String): Unit =
    VirtualFileSystem.keyOf(uri).foreach { key =>
      val document = VirtualFileSystem.Document(content, stampSeq.incrementAndGet())
      overrides.updateAndGet(_.updated(key, document))
    }

  /** Drop a document's override so the compiler reverts to its on-disk content. */
  def remove(uri: URI): Unit =
    VirtualFileSystem.keyOf(uri).foreach(key => overrides.updateAndGet(_.removed(key)))

  /** The buffer override currently held for a file, if any. */
  def get(file: File): Option[VirtualFileSystem.Document] =
    overrides.get.get(VirtualFileSystem.keyOf(file.toPath))
}

object VirtualFileSystem {

  /** A live buffer: its content plus the monotonic stamp that stands in for the file's modification time. */
  final case class Document(content: String, stamp: Long)

  private def keyOf(uri: URI): Option[Path] =
    Option.when(uri.getScheme == "file")(keyOf(Paths.get(uri)))

  private def keyOf(path: Path): Path = path.toAbsolutePath.normalize
}
