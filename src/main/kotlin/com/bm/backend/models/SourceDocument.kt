package com.bm.backend.models

/**
 * Identity of an uploaded price proposal PDF.
 *
 * [fileName] is the sanitized original filename supplied by the uploader, and
 * [sha256] is the lowercase hex SHA-256 of the file bytes. Together they let the
 * persistence layer recognise a re-upload of the same proposal.
 */
data class SourceDocument(
    val fileName: String,
    val sha256: String
)
