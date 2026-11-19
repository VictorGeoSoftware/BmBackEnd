package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachWorkbookInspectionReceipt(val attachment: TeachWorkbookAttachment, val inspection: TeachWorkbookInspection)
