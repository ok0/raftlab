package io.raftlab.net

import java.io.IOException

/**
 * A frame violated the wire format (cap, negative length, undecodable body, truncated);
 * the connection that produced it must be closed.
 */
public class FrameException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
