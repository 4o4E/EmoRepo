package top.e404.emorepo.protocol

class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
