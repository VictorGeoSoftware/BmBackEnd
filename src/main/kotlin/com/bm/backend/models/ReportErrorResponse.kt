package com.bm.backend.models

import kotlinx.serialization.Serializable

/**
 * Error body of the Premium report route. [code] is the stable contract; [message] is fixed Spanish copy per code
 * and never carries customer values, job ids, emails or exception text.
 */
@Serializable
data class ReportErrorResponse(
    val success: Boolean = false,
    val code: String,
    val message: String,
    val requestId: String? = null
)
