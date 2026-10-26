package io.raftlab.trace

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

/**
 * Converts trace events to and from JSON Lines.
 *
 * Format: one JSON object per event, each terminated by `\n`. The class discriminator is `type`
 * and appears first; the remaining fields follow in constructor order, so output is stable.
 * Pretty printing is off, nullable fields are written explicitly as `null`, and no floating-point
 * values are used, so the encoding is byte-for-byte reproducible.
 */
public object TraceCodec {
    private val json = Json { classDiscriminator = "type" }
    private val serializer: KSerializer<TraceEvent> = serializer()

    /** Encodes [events] as JSON Lines; an empty list encodes to an empty string. */
    public fun encode(events: List<TraceEvent>): String =
        events.joinToString("") { json.encodeToString(serializer, it) + "\n" }

    /**
     * Decodes JSON Lines produced by [encode].
     *
     * @throws IllegalArgumentException if any line is not a valid trace event;
     * the message names the 1-based line number.
     */
    public fun decode(jsonl: String): List<TraceEvent> {
        if (jsonl.isEmpty()) return emptyList()
        val lines = jsonl.removeSuffix("\n").split('\n')
        return lines.mapIndexed { i, line ->
            val n = i + 1
            try {
                json.decodeFromString(serializer, line)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid trace line $n: ${e.message}", e)
            }
        }
    }
}
