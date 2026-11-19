package com.bm.backend.models

/** An exact registry entry, interpreted only within its manifest. Not a wire payload. */
internal data class TeachRegisteredSkillRevision(
    val supplierKey: String,
    val revisionId: String,
    val profileKey: String,
    val workbookSha256: String,
    val semantics: String,
    val manifest: String,
    val rule: TeachDraftPreviewRule,
)
