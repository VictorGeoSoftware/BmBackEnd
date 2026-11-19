package com.bm.backend.routes

import com.bm.backend.models.TeachSupplierColumnChoice
import com.bm.backend.models.TeachSupplierDraftFrame
import io.ktor.utils.io.ByteReadChannel
import java.security.MessageDigest

internal const val SUPPLIER_DRAFT_MEDIA_TYPE = "application/vnd.bm.teach-supplier-draft"
private const val BODY_LIMIT = 4 + TEACH_MANIFEST_LIMIT + WORKBOOK_BODY_LIMIT

internal suspend fun readSupplierDraftFrame(channel: ByteReadChannel, checkpoint: () -> Unit): TeachSupplierDraftFrame {
    val reader = TeachFrameReader(channel, checkpoint)
    val tuple = reader.manifest()
    val fields = when (tuple.string(1)) {
        "ask" -> 3
        "draft" -> 6
        "revise" -> 7
        else -> throw InvalidTeachUpload()
    }
    if (tuple.size != fields) throw InvalidTeachUpload()
    val xlsx = tuple.workbookSize()
    val expected = if (fields > 3) tuple.digest(3) else null
    val supplier = if (fields > 3) tuple.string(4) else null
    val prior = if (fields == 7) tuple.digest(5) else null
    val choice = if (fields > 3) {
        val answer = tuple.string(fields - 1).also(::validateAnswer).trim(' ')
        TeachSupplierColumnChoice.entries.singleOrNull { it.answer == answer } ?: throw InvalidTeachUpload()
    } else null
    val hasher = MessageDigest.getInstance("SHA-256")
    val workbook = reader.segment(xlsx, hasher)
    reader.requireEnd(xlsx, BODY_LIMIT)
    return TeachSupplierDraftFrame(workbook, hasher.hexDigest(), expected, supplier, prior, choice)
}
