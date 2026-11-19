package com.bm.backend.testing

import java.util.UUID

/** Digests are not authority. All fields and the raw bytes must match on replay. */
internal class ProdAnalogRelease(
    val schema: String, val environment: String, val binding: ProdAnalogBinding, val supplier: String,
    val deployment: UUID, val ordinal: Long, val revision: String, val semantics: String,
    val origin: String, val digest: String, bytes: ByteArray,
) {
    private val canonical = bytes.copyOf()
    fun bytes() = canonical.copyOf()
    fun same(other: ProdAnalogRelease) = fields() == other.fields() && canonical.contentEquals(other.canonical)
    private fun fields() = listOf(schema, environment, binding, supplier, deployment, ordinal, revision, semantics, origin, digest)
}
