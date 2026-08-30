package com.rsplwe.esurfing.fixture

/**
 * Fully fictitious canary secrets used ONLY by tests.
 *
 * These values are deliberately fake and MUST NOT be usable against any real
 * campus network. They are the sentinel values the FixtureSecretScanner greps
 * for: if any of them ever shows up in a log, fixture, test report or artifact,
 * a secret-leak regression has happened and the scan must fail.
 */
object CanarySecrets {

    const val CANARY_PASSWORD_DEMO: String = "CAnary-P@ssw0rd-0f1e2d3c4b5a6978"
    const val CANARY_TICKET_DEMO: String = "CAnary-T1cket-6f5e4d3c2b1a0987"
    const val CANARY_KEY_DEMO: String = "CAnary-K3y-a1b2c3d4e5f60718"
    const val CANARY_SMS_DEMO: String = "CAnary-SmsC0de-135790"
    const val CANARY_USER_DEMO: String = "138-0000-0000"

    /** 16-byte fictitious AES key (ASCII 'K' + 15 'x'). Never a real key. */
    val CANARY_BYTE_KEY: ByteArray = byteArrayOf(
        0x4B, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78,
        0x78, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78, 0x78
    )

    val all: List<String> = listOf(
        CANARY_PASSWORD_DEMO,
        CANARY_TICKET_DEMO,
        CANARY_KEY_DEMO,
        CANARY_SMS_DEMO,
        CANARY_USER_DEMO,
    )
}
