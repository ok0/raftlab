package io.raftlab.storage

import io.raftlab.core.LogEntry
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.util.zip.CRC32

/** Binary layout of `log.dat`: a 12-byte header followed by length-prefixed, checksummed JSON records. */
internal object LogCodec {
    const val MAGIC: Int = 0x524C4F47
    const val HEADER_SIZE: Int = 12
    const val RECORD_HEADER_SIZE: Int = 8

    val json: Json = Json { classDiscriminator = "type" }

    /** Result of scanning `log.dat`: the compacted index, the valid entries with their record offsets, and the valid end. */
    class Scan(
        val compactedIndex: Long,
        val entries: List<LogEntry>,
        val offsets: List<Long>,
        val validEnd: Long,
    )

    fun crc32(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size,
    ): Long {
        val crc = CRC32()
        crc.update(bytes, offset, length)
        return crc.value
    }

    fun header(compactedIndex: Long): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE)
        buf.putInt(MAGIC)
        buf.putLong(compactedIndex)
        return buf.array()
    }

    fun record(entry: LogEntry): ByteArray {
        val payload = json.encodeToString(LogEntry.serializer(), entry).encodeToByteArray()
        val buf = ByteBuffer.allocate(RECORD_HEADER_SIZE + payload.size)
        buf.putInt(payload.size)
        buf.putInt(crc32(payload).toInt())
        buf.put(payload)
        return buf.array()
    }

    fun scan(bytes: ByteArray): Scan {
        check(bytes.size >= HEADER_SIZE && ByteBuffer.wrap(bytes).getInt(0) == MAGIC) { "log.dat has no valid header" }
        val base = ByteBuffer.wrap(bytes).getLong(4)
        val entries = ArrayList<LogEntry>()
        val offsets = ArrayList<Long>()
        val buf = ByteBuffer.wrap(bytes)
        var pos = HEADER_SIZE
        while (bytes.size - pos >= RECORD_HEADER_SIZE) {
            val length = buf.getInt(pos)
            val crc = buf.getInt(pos + 4)
            if (length <= 0 || length > bytes.size - pos - RECORD_HEADER_SIZE) break
            val start = pos + RECORD_HEADER_SIZE
            if (crc32(bytes, start, length).toInt() != crc) break
            val entry =
                try {
                    json.decodeFromString(LogEntry.serializer(), bytes.decodeToString(start, start + length))
                } catch (e: IllegalArgumentException) {
                    break
                }
            if (entry.index != base + 1 + entries.size) break
            offsets += pos.toLong()
            entries += entry
            pos = start + length
        }
        return Scan(base, entries, offsets, pos.toLong())
    }
}
