package com.rsplwe.esurfing.network

sealed class NetResult<out T : Any> {

    /** 2xx response handed to the caller; [exchange] is the sanitised exchange snapshot. */
    data class Success<out T : Any>(
        val data: T,
        val exchange: HttpExchangeRecord? = null,
    ) : NetResult<T>()

    /**
     * Classified failure: [exception] carries a stable machine-readable label
     * (`HTTP_404`, `REDIRECT_LIMIT`, `EMPTY_BODY`, `TIMEOUT`, `DNS`, ...), never
     * a raw stack trace; [exchange] mirrors [Success].
     */
    data class Error(
        val exception: String,
        val exchange: HttpExchangeRecord? = null,
    ) : NetResult<Nothing>()

    override fun toString(): String {
        return when (this) {
            is Success<*> -> "Success[data=$data]"
            is Error -> "Error[exception=$exception]"
        }
    }
}