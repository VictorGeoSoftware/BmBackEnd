package com.bm.backend.routes

/**
 * Turns the client-supplied multipart filename into a safe, stable name.
 *
 * The result becomes part of the persistence natural key, is shown on the
 * dashboard and is placed in a `Content-Disposition` header sent to the
 * extractor, so it must be deterministic for the same input and free of path
 * segments, quotes and control characters.
 */
object UploadedFileNameSanitizer {

    /**
     * `file_name` is VARCHAR(255) and multi-product PDFs are stored as
     * "<fileName> - <productName>", so leave headroom for the product suffix.
     */
    const val MAX_LENGTH = 150

    private const val FALLBACK = "uploaded.pdf"
    private const val EXTENSION = ".pdf"

    fun sanitize(rawFileName: String?): String {
        val baseName = rawFileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.filterNot { it.isISOControl() || it == '"' }
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        if (baseName.isEmpty() || baseName.equals(EXTENSION, ignoreCase = true)) return FALLBACK
        if (baseName.length <= MAX_LENGTH) return baseName

        val hasPdfExtension = baseName.endsWith(EXTENSION, ignoreCase = true)
        val stem = if (hasPdfExtension) baseName.dropLast(EXTENSION.length) else baseName
        val extension = if (hasPdfExtension) baseName.takeLast(EXTENSION.length) else ""
        return stem.take(MAX_LENGTH - extension.length).trimEnd() + extension
    }
}
