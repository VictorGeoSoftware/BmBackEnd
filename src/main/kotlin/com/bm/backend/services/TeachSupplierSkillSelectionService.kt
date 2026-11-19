package com.bm.backend.services

import com.bm.backend.models.TeachActiveSkill
import com.bm.backend.models.TeachSupplierSkillSelection
import com.bm.backend.repositories.ports.TeachActiveSkillReader
import com.bm.backend.repositories.ports.TeachSkillCatalogUnavailable

internal enum class TeachSupplierSelectionError(val wire: String) {
    Unknown("unknown_supplier"), Ambiguous("ambiguous_supplier"), Conflicting("conflicting_supplier"),
    InvalidEvidence("invalid_supplier_evidence"), NoActiveSkill("no_active_skill"),
}

internal class TeachSupplierSelectionConflict(val error: TeachSupplierSelectionError) : RuntimeException()

internal fun invalidSupplierEvidence(): Nothing = throw TeachSupplierSelectionConflict(TeachSupplierSelectionError.InvalidEvidence)

private const val MARKER = "BM SYNTHETIC SUPPLIER"
private val filenameAliases = listOf(
    "bm-pilot-gesternova" to GESTERNOVA_SUPPLIER,
    "bm-pilot-gesternova-20td" to GESTERNOVA_SUPPLIER,
    "bm-pilot-luz-fixture" to "luz-fixture",
)
private val contentAliases = setOf(GESTERNOVA_SUPPLIER, "luz-fixture")

private fun normalizePilotAlias(literal: String) = literal.trim(' ').map {
    if (it in 'A'..'Z') it.lowercaseChar() else it
}.joinToString("")

internal class TeachSupplierSkillSelectionService private constructor(
    private val reader: TeachActiveSkillReader,
    private val registry: TeachActivationRegistry,
    private val byStem: Map<String, String>,
) {
    val catalogId: String get() = reader.storeId
    val manifest: String get() = registry.manifest
    fun isBoundTo(candidate: TeachActivationRegistry) = registry === candidate
    companion object {
        fun mount(reader: TeachActiveSkillReader, registry: TeachActivationRegistry): TeachSupplierSkillSelectionService? {
            if (!teachFixtureId.matches(reader.storeId)) return null
            val aliases = filenameAliases.groupBy({ normalizePilotAlias(it.first) }, { it.second })
            if (aliases.values.any { it.distinct().size != 1 }) return null
            val service = TeachSupplierSkillSelectionService(reader, registry, aliases.mapValues { it.value.first() })
            return try {
                reader.verify(registry.manifest).forEach { (supplier, active) -> service.resolve(supplier, active) }
                service
            } catch (_: TeachSkillCatalogUnavailable) { null }
        }
    }

    /** The filename passed the upload framing check. Its evidence never bypasses workbook inspection. */
    fun select(
        filename: String, validator: TeachWorkbookValidator, inspector: TeachWorkbookInspector,
        bytes: ByteArray, size: Int, remainingMillis: () -> Long, checkpoint: () -> Unit,
    ): TeachSupplierSkillSelection {
        val byName = byStem[normalizePilotAlias(filename.dropLast(".xlsx".length))]
        val markers = inspector.inspectSupplierMarkers(validator, bytes, size, checkpoint).mapNotNull { (label, value) ->
            if (label == null && value == null) null else {
                if (label != MARKER || value == null) invalidSupplierEvidence()
                normalizePilotAlias(value).takeIf { it in contentAliases } ?: invalidSupplierEvidence()
            }
        }.distinct()
        if (markers.size > 1) throw TeachSupplierSelectionConflict(TeachSupplierSelectionError.Ambiguous)
        val byContent = markers.singleOrNull()
        if (byName != null && byContent != null && byName != byContent) {
            throw TeachSupplierSelectionConflict(TeachSupplierSelectionError.Conflicting)
        }
        val supplier = byName ?: byContent ?: throw TeachSupplierSelectionConflict(TeachSupplierSelectionError.Unknown)
        checkpoint()
        val active = reader.read(registry.manifest, supplier, remainingMillis, checkpoint)
        checkpoint()
        if (active == null) throw TeachSupplierSelectionConflict(TeachSupplierSelectionError.NoActiveSkill)
        val revision = resolve(supplier, active)
        return TeachSupplierSkillSelection(revision, active.generation,
            if (byName != null) TeachSupplierSkillSelection.Source.Filename else TeachSupplierSkillSelection.Source.Content)
    }

    private fun resolve(supplier: String, active: TeachActiveSkill) = active.revisionId
        ?.takeIf { validTeachActive(it, active.generation) }
        ?.let { registry.exact(supplier, it) } ?: throw TeachSkillCatalogUnavailable()
}
