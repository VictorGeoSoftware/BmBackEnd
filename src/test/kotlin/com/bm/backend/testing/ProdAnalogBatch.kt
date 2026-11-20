package com.bm.backend.testing

import com.bm.backend.models.TeachDraftPreviewRow
import kotlinx.serialization.Serializable

@Serializable
internal data class ProdAnalogBatch(val importId: String, val supplierKey: String, val revisionId: String,
                                    val generation: String, val artifactSha256: String, val importedAt: String,
                                    val proposals: List<TeachDraftPreviewRow>)
