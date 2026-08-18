package com.boxpix.app.core

/** Typed failures of the storage layer — no swallowed exceptions, no stringly-typed errors. */
sealed interface FreeboxError {
    /** Transport failed: DNS, timeout, TLS, connection reset… */
    data class Network(val cause: Throwable) : FreeboxError

    /** HTTP error with a body that was not a Freebox envelope. */
    data class Http(val status: Int) : FreeboxError

    /** The box answered with success=false; `code` is the API error_code. */
    data class Api(val code: String, val message: String? = null) : FreeboxError

    data object NotPaired : FreeboxError
    data object BoxNotFound : FreeboxError
    data object PairingDenied : FreeboxError
    data object PairingTimeout : FreeboxError
}

sealed class FbxResult<out T> {
    data class Ok<out T>(val value: T) : FbxResult<T>()
    data class Err(val error: FreeboxError) : FbxResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): FbxResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): FreeboxError? = (this as? Err)?.error
}

fun <T> T.ok(): FbxResult<T> = FbxResult.Ok(this)
fun FreeboxError.err(): FbxResult.Err = FbxResult.Err(this)

/** True for errors that a fresh login can fix (expired or missing session). */
fun FreeboxError.isAuthError(): Boolean = when (this) {
    is FreeboxError.Api -> code == "auth_required" || code == "invalid_token"
    is FreeboxError.Http -> status == 403
    else -> false
}
