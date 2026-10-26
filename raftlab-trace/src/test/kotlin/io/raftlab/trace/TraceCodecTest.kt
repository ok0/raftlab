package io.raftlab.trace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Test

class TraceCodecTest {
    @Test
    fun roundTripsEverySubtype() {
        TraceCodec.decode(TraceCodec.encode(TraceSamples.all)) shouldBe TraceSamples.all
    }

    @Test
    fun samplesCoverEverySubtype() {
        val d = serializer<TraceEvent>().descriptor.getElementDescriptor(1)
        val declared = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        val lines = TraceCodec.encode(TraceSamples.all).lines().filter { it.isNotEmpty() }
        val sampled = lines.map { typeOf(it) }.toSet()
        sampled shouldBe declared
        declared.size shouldBe 21
    }

    private fun typeOf(line: String): String {
        val obj = Json.parseToJsonElement(line).jsonObject
        return obj["type"]!!.jsonPrimitive.content
    }

    @Test
    fun encodesGoldenOutput() {
        TraceCodec.encode(TraceSamples.all) shouldBe TraceSamples.golden
        TraceSamples.golden.contains('\r') shouldBe false
        Regex(":-?\\d+\\.\\d").containsMatchIn(TraceSamples.golden) shouldBe false
        TraceSamples.golden.endsWith("\n") shouldBe true
    }

    @Test
    fun encodesEmptyList() {
        TraceCodec.encode(emptyList()) shouldBe ""
        TraceCodec.decode("") shouldBe emptyList()
    }

    @Test
    fun rejectsMalformedLine() {
        val input = TraceSamples.golden.lines().first() + "\n{not json\n"
        val e = shouldThrow<IllegalArgumentException> { TraceCodec.decode(input) }
        e.message!! shouldContain "line 2"
    }

    @Test
    fun rejectsUnknownType() {
        val input = "{\"type\":\"Bogus\",\"seq\":0,\"time\":0,\"node\":null}\n"
        val e = shouldThrow<IllegalArgumentException> { TraceCodec.decode(input) }
        e.message!! shouldContain "line 1"
    }

    @Test
    fun rejectsBlankLine() {
        shouldThrow<IllegalArgumentException> { TraceCodec.decode("\n\n") }
    }
}
