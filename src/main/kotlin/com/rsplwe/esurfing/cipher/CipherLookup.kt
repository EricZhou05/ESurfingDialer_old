package com.rsplwe.esurfing.cipher

/**
 * Result of looking up an algorithm by its Algo-Id.
 *
 * [Unknown] replaces the previous `IllegalArgumentException` thrown for an
 * unrecognised Algo-Id: the boundary now returns a typed, non-throwing outcome
 * (Todo 5). The dispatch mapping itself — which Algo-Id resolves to which
 * algorithm, key and IV — is unchanged.
 */
sealed interface CipherLookup {

    data class Found(val cipher: CipherInterface) : CipherLookup

    data class Unknown(val algoId: String) : CipherLookup
}