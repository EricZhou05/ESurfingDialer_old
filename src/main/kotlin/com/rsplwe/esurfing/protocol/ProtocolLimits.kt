package com.rsplwe.esurfing.protocol

/**
 * Protocol-contract bounds for the keep-retry / heartbeat interval, in seconds.
 *
 * Every parsed interval is required to fall inside
 * [MIN_INTERVAL_SECONDS]..[MAX_INTERVAL_SECONDS]: zero/negative would busy-loop
 * the heartbeat and values beyond the cap defeat the anti-high-frequency guard.
 * Anything else (blank, non-numeric, overflow, out-of-range) is a typed
 * contract failure — never a `toLong()` crash at the call site.
 */
object ProtocolLimits {

    const val MIN_INTERVAL_SECONDS: Long = 1L

    const val MAX_INTERVAL_SECONDS: Long = 3600L

    /**
     * Safe placeholder for the client-side keep-retry field before the first
     * validated login response arrives (also the value left behind after a
     * failed authorization attempt, so the field never carries an invalid or
     * blank string).
     */
    const val DEFAULT_KEEP_RETRY_SECONDS: Long = 30L
}