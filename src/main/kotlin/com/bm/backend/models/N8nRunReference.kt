package com.bm.backend.models

/** Identifies the n8n execution that produced a study, so its source files (e.g. the Excel) can be found. */
data class N8nRunReference(
    val executionId: String,
    val workflowId: String?
) {
    /** Editor URL of the execution, or null when the public n8n URL or the workflow id is unknown. */
    fun url(n8nPublicUrl: String?): String? {
        val base = n8nPublicUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val workflow = workflowId?.takeIf { it.isNotBlank() } ?: return null
        return "$base/workflow/$workflow/executions/$executionId"
    }
}
