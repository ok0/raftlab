package io.raftlab.kv

import io.raftlab.core.StateMachine
import java.util.TreeMap

/**
 * Key-value state machine with per-client sessions.
 *
 * A command whose `seq` is not greater than the last applied `seq` of its client returns the cached result without
 * being applied again. Sessions never expire in M8, which is a documented limit.
 */
public class KvStateMachine : StateMachine {
    private val data: TreeMap<String, String> = TreeMap()
    private val sessions: TreeMap<String, KvSession> = TreeMap()

    /**
     * Applies a [KvCommand] encoded by [KvCodec.encodeCommand] and returns the [KvResult] encoded by
     * [KvCodec.encodeResult].
     *
     * If [command] is not a valid [KvCommand], returns an empty byte array and changes nothing. Never throws for any
     * input.
     */
    override fun apply(
        index: Long,
        command: ByteArray,
    ): ByteArray {
        val cmd =
            try {
                KvCodec.decodeCommand(command)
            } catch (e: IllegalArgumentException) {
                return ByteArray(0)
            }
        val session = sessions[cmd.clientId]
        if (session != null && cmd.seq <= session.lastSeq) {
            return KvCodec.encodeResult(session.lastResult)
        }
        val result = execute(cmd.op)
        sessions[cmd.clientId] = KvSession(cmd.seq, result)
        return KvCodec.encodeResult(result)
    }

    /** Returns the complete state as bytes; equal states always produce equal bytes. */
    override fun snapshot(): ByteArray = KvCodec.encodeSnapshot(KvSnapshot(TreeMap(data), TreeMap(sessions)))

    /**
     * Replaces all state with the state held in [data], which must have been produced by [snapshot].
     *
     * @throws IllegalArgumentException if [data] was not produced by [snapshot].
     */
    override fun restore(data: ByteArray) {
        val s = KvCodec.decodeSnapshot(data)
        this.data.clear()
        this.sessions.clear()
        this.data.putAll(s.data)
        this.sessions.putAll(s.sessions)
    }

    /** Returns a sorted copy of the current key-value data (sessions excluded). Later changes do not affect the copy. */
    public fun dataSnapshot(): Map<String, String> = TreeMap(data)

    internal fun dataForChecks(): Map<String, String> = TreeMap(data)

    internal fun sessionForChecks(clientId: String): KvSession? = sessions[clientId]

    private fun execute(op: KvOp): KvResult =
        when (op) {
            is KvOp.Put -> {
                data[op.key] = op.value
                KvResult.Ok(null)
            }

            is KvOp.Get -> {
                KvResult.Ok(data[op.key])
            }

            is KvOp.Delete -> {
                KvResult.Ok(data.remove(op.key))
            }

            is KvOp.Cas -> {
                val actual = data[op.key]
                if (actual == op.expected) {
                    if (op.new == null) {
                        data.remove(op.key)
                    } else {
                        data[op.key] = op.new
                    }
                    KvResult.Ok(op.new)
                } else {
                    KvResult.CasFailed(actual)
                }
            }
        }
}
