package com.bm.backend.testing

import com.bm.backend.services.*
import java.security.MessageDigest

internal class ProdAnalogUnavailable : RuntimeException()
internal class ProdAnalogConflict(val wire: String) : RuntimeException()

/** Reconstructed only from approved synthetic pins; no QA reader, fake PR or JSON interpreter. */
internal class ProdAnalogCatalog(pins: Set<String>) {
    private val registry = TeachActivationRegistry(pins.toSet())
    fun candidate(supplier: String, revision: String) = registry.exact(supplier, revision)?.let(::ProdAnalogCandidate)
        ?: throw ProdAnalogUnavailable()

    fun verify(expected: ProdAnalogBinding, release: ProdAnalogRelease): ProdAnalogCandidate {
        val selected = candidate(release.supplier, release.revision)
        val bytes = release.bytes()
        val canonical = selected.canonical
        val exact = selected.exact
        if (release.schema != RELEASE_SCHEMA || release.environment != PROD_ANALOG || release.binding != expected ||
            release.ordinal <= 0 || release.semantics != exact.semantics || release.semantics != "gesternova-fixed-extractor/1" ||
            release.origin != exact.manifest || release.digest != canonical.digest || bytes.size !in 1..8192 ||
            release.digest != sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes)) ||
            !bytes.contentEquals(canonical.artifact.encodeToByteArray())) throw ProdAnalogUnavailable()
        return selected
    }
}
