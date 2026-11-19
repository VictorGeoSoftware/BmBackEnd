package com.bm.backend.routes

import com.bm.backend.models.TeachDraftPreviewFrame
import com.bm.backend.models.TeachDraftPreviewSelection
import io.ktor.utils.io.ByteReadChannel
import java.security.MessageDigest

internal const val DRAFT_PREVIEW_MEDIA_TYPE = "application/vnd.bm.teach-draft-preview"
private const val BODY_LIMIT = 4 + TEACH_MANIFEST_LIMIT + WORKBOOK_BODY_LIMIT
private val key = Regex("[a-z0-9-]{1,48}")

internal suspend fun readDraftPreviewFrame(channel: ByteReadChannel, checkpoint: () -> Unit): TeachDraftPreviewFrame {
    val reader = TeachFrameReader(channel, checkpoint)
    val tuple = reader.manifest()
    val count = when (tuple.string(1)) {
        "prepare" -> 4
        "preview" -> 7
        else -> throw InvalidTeachUpload()
    }
    if (tuple.size != count) throw InvalidTeachUpload()
    val size = tuple.workbookSize()
    fun keyAt(index: Int) = tuple.string(index).takeIf(key::matches) ?: throw InvalidTeachUpload()
    val profile = keyAt(3)
    val selection = if (count == 7) TeachDraftPreviewSelection(keyAt(4), tuple.digest(5), tuple.digest(6)) else null
    val hasher = MessageDigest.getInstance("SHA-256")
    val workbook = reader.segment(size, hasher)
    reader.requireEnd(size, BODY_LIMIT)
    return TeachDraftPreviewFrame(workbook, hasher.hexDigest(), profile, selection)
}
