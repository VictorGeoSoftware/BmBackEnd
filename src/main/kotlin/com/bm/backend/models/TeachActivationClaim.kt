package com.bm.backend.models

internal data class TeachActivationClaim(
    val actor: String, val supplierKey: String, val profileKey: String, val workbookSha256: String,
    val revisionId: String, val expected: TeachActiveSkill, val nonce: String, val issuedAt: Long, val expiresAt: Long,
)
