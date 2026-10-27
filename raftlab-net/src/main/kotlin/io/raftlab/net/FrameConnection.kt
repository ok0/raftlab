package io.raftlab.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

internal class FrameConnection(
    private val socket: Socket,
) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()

    val remote: String = socket.remoteSocketAddress.toString()

    val isClosed: Boolean
        get() = socket.isClosed

    fun read(): Frame? = FrameCodec.read(input)

    fun write(frame: Frame) {
        val bytes = FrameCodec.encode(frame)
        synchronized(writeLock) {
            output.write(bytes)
            output.flush()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        fun connect(
            address: InetSocketAddress,
            connectTimeoutMillis: Int,
            readTimeoutMillis: Int,
        ): FrameConnection {
            val target = InetSocketAddress(address.hostString, address.port)
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = readTimeoutMillis
                socket.connect(target, connectTimeoutMillis)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            return FrameConnection(socket)
        }
    }
}
