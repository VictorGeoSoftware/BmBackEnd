package com.bm.backend.models

/** Exactly one regular data file addition. No arbitrary operations, paths or metadata. */
internal data class TeachPromotionDiff(val revision: String, val artifact: String, val digest: String) {
    val path get() = "skills-fixture/gesternova/$revision.json"
    val title get() = "Promote gesternova $revision (simulation)"
    val body get() = "Validated QA revision gesternova $revision. Simulation only."
}
