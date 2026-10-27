package io.raftlab.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Whole-file writes that replace the target atomically: write a temporary sibling, fsync it, then rename it. */
internal object AtomicFiles {
    /** Suffix of the temporary sibling used while a file is being replaced. */
    const val TMP_SUFFIX: String = ".tmp"

    /** Replaces [target] with [bytes] durably; a crash leaves either the old or the new content. */
    fun write(
        target: Path,
        bytes: ByteArray,
    ) {
        val tmp = target.resolveSibling(target.fileName.toString() + TMP_SUFFIX)
        FileChannel
            .open(
                tmp,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use {
                writeFully(it, bytes)
                it.force(true)
            }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(target.toAbsolutePath().parent)
    }

    /** Writes all of [bytes] at the channel position. */
    fun writeFully(
        channel: FileChannel,
        bytes: ByteArray,
    ) {
        val buf = ByteBuffer.wrap(bytes)
        while (buf.hasRemaining()) channel.write(buf)
    }

    /** Fsyncs the directory [dir] so that a rename is durable; ignored where directories cannot be synced. */
    fun syncDirectory(dir: Path) {
        try {
            FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
        } catch (ignored: IOException) {
            // directory fsync is unsupported on some platforms
        }
    }
}
