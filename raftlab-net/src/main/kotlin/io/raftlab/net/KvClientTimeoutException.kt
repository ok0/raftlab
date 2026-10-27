package io.raftlab.net

import java.io.IOException

/**
 * No leader answered a call within its overall timeout; the outcome of the operation is unknown.
 */
public class KvClientTimeoutException(
    message: String,
) : IOException(message)
