package com.bm.backend.models

internal data class TeachSupplierSkillSelection(
    val revision: TeachRegisteredSkillRevision,
    val generation: String,
    val source: Source,
) {
    enum class Source(val wire: String) { Filename("filename"), Content("content") }
}
