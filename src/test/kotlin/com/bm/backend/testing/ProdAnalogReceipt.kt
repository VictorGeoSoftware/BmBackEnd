package com.bm.backend.testing

import kotlinx.serialization.Serializable

@Serializable
internal data class ProdAnalogReceipt(val kind: String, val environment: String, val importId: String,
                                      val revisionId: String, val generation: String, val artifactSha256: String,
                                      val proposalCount: Int)
