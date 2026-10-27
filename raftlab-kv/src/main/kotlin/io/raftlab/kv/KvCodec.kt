package io.raftlab.kv

import kotlinx.serialization.json.Json

/**
 * Byte-stable JSON codec for the kv module.
 *
 * Format: UTF-8 JSON without pretty printing. The class discriminator `type` is written first, the remaining fields
 * follow in constructor order, and null values are written explicitly, so equal values always encode to equal bytes.
 */
public object KvCodec {
    private val json = Json { classDiscriminator = "type" }

    /** Encodes [command] to UTF-8 JSON bytes. */
    public fun encodeCommand(command: KvCommand): ByteArray =
        json.encodeToString(KvCommand.serializer(), command).encodeToByteArray()

    /**
     * Decodes a [KvCommand] from UTF-8 JSON bytes.
     *
     * @throws IllegalArgumentException if [bytes] are not a valid command.
     */
    public fun decodeCommand(bytes: ByteArray): KvCommand =
        json.decodeFromString(KvCommand.serializer(), bytes.decodeToString())

    /** Encodes [result] to UTF-8 JSON bytes. */
    public fun encodeResult(result: KvResult): ByteArray =
        json.encodeToString(KvResult.serializer(), result).encodeToByteArray()

    /**
     * Decodes a [KvResult] from UTF-8 JSON bytes.
     *
     * @throws IllegalArgumentException if [bytes] are not a valid result.
     */
    public fun decodeResult(bytes: ByteArray): KvResult =
        json.decodeFromString(KvResult.serializer(), bytes.decodeToString())

    /** Encodes [request] to a JSON string. */
    public fun encodeRequest(request: ClientRequest): String = json.encodeToString(ClientRequest.serializer(), request)

    /**
     * Decodes a [ClientRequest] from a JSON string.
     *
     * @throws IllegalArgumentException if [text] is not a valid request.
     */
    public fun decodeRequest(text: String): ClientRequest = json.decodeFromString(ClientRequest.serializer(), text)

    /** Encodes [response] to a JSON string. */
    public fun encodeResponse(response: ClientResponse): String =
        json.encodeToString(ClientResponse.serializer(), response)

    /**
     * Decodes a [ClientResponse] from a JSON string.
     *
     * @throws IllegalArgumentException if [text] is not a valid response.
     */
    public fun decodeResponse(text: String): ClientResponse = json.decodeFromString(ClientResponse.serializer(), text)

    /** Encodes a state machine snapshot to UTF-8 JSON bytes. */
    internal fun encodeSnapshot(snapshot: KvSnapshot): ByteArray =
        json.encodeToString(KvSnapshot.serializer(), snapshot).encodeToByteArray()

    /**
     * Decodes a state machine snapshot from UTF-8 JSON bytes.
     *
     * @throws IllegalArgumentException if [bytes] are not a valid snapshot.
     */
    internal fun decodeSnapshot(bytes: ByteArray): KvSnapshot =
        json.decodeFromString(KvSnapshot.serializer(), bytes.decodeToString())
}
