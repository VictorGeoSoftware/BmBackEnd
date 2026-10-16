package com.bm.backend.models

/** File types accepted by the consumption-report upload endpoint. */
enum class ConsumptionUploadType(val tempSuffix: String) {
    PDF(".pdf"),
    JPEG(".jpg"),
    PNG(".png");

    val isImage: Boolean get() = this != PDF

    companion object {
        /** Resolves the type from the file extension (case-insensitive); null when unsupported. */
        fun fromFileName(fileName: String?): ConsumptionUploadType? {
            val ext = fileName?.substringAfterLast('.', "")?.lowercase() ?: return null
            return when (ext) {
                "pdf" -> PDF
                "jpg", "jpeg" -> JPEG
                "png" -> PNG
                else -> null
            }
        }
    }
}
