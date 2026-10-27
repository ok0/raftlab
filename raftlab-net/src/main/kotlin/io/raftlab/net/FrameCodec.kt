package io.raftlab.net

import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Length-prefixed JSON codec for [Frame]s. Wire format: a 4-byte big-endian signed length N,
 * then N bytes of UTF-8 JSON of [Frame], with the discriminator `type`.
 */
public object FrameCodec {
    /** Largest accepted frame body in bytes. */
    public const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

    private val json = Json { classDiscriminator = "type" }

    /**
     * Encodes [frame] with its 4-byte length prefix.
     *
     * @throws FrameException if the encoded body exceeds [MAX_FRAME_BYTES].
     */
    public fun encode(frame: Frame): ByteArray {
        val text = json.encodeToString(Frame.serializer(), frame)
        val body = text.encodeToByteArray()
        if (body.size > MAX_FRAME_BYTES) {
            throw FrameException("frame of ${body.size} bytes exceeds cap of $MAX_FRAME_BYTES")
        }
        val buf = ByteBuffer.allocate(4 + body.size)
        buf.putInt(body.size).put(body)
        return buf.array()
    }

    /**
     * Decodes a frame body that has no length prefix.
     *
     * @throws FrameException if the body is not a valid [Frame].
     */
    public fun decodeBody(body: ByteArray): Frame =
        try {
            json.decodeFromString(Frame.serializer(), body.decodeToString())
        } catch (e: IllegalArgumentException) {
            throw FrameException("undecodable frame: ${e.message}", e)
        }

    /**
     * Reads one length-prefixed frame, or returns null when the stream ends cleanly between frames.
     *
     * @throws FrameException if the length is negative or over [MAX_FRAME_BYTES], the body is
     * truncated or undecodable.
     * @throws java.io.IOException if the underlying stream fails.
     */
    public fun read(input: InputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val rest = ByteArray(3)
        readFully(input, rest)
        val length =
            (b0 shl 24) or
                ((rest[0].toInt() and 0xFF) shl 16) or
                ((rest[1].toInt() and 0xFF) shl 8) or
                (rest[2].toInt() and 0xFF)
        if (length < 0) throw FrameException("negative frame length $length")
        if (length > MAX_FRAME_BYTES) {
            throw FrameException("frame length $length exceeds cap of $MAX_FRAME_BYTES")
        }
        val body = ByteArray(length)
        readFully(input, body)
        return decodeBody(body)
    }

    /**
     * Writes [frame] with its length prefix and flushes [output].
     *
     * @throws FrameException if the encoded body exceeds [MAX_FRAME_BYTES].
     * @throws java.io.IOException if the underlying stream fails.
     */
    public fun write(
        output: OutputStream,
        frame: Frame,
    ) {
        output.write(encode(frame))
        output.flush()
    }

    private fun readFully(
        input: InputStream,
        buf: ByteArray,
    ) {
        if (input.readNBytes(buf, 0, buf.size) < buf.size) throw FrameException("connection closed mid-frame")
    }
}
