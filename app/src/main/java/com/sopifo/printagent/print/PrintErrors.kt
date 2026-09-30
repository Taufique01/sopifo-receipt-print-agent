package com.sopifo.printagent.print

enum class PrintFailure(val retryable: Boolean) {
    NOT_CONFIGURED(false),
    BLUETOOTH_UNAVAILABLE(false),
    BLUETOOTH_OFF(false),
    NO_PERMISSION(false),
    CONNECT_FAILED(true),
    WRITE_FAILED(true),
    TIMEOUT(true),
    BAD_IMAGE(false),
}

class PrintException(val failure: PrintFailure, message: String, cause: Throwable? = null) : Exception(message, cause)
