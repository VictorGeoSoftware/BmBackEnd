package com.bm.backend.testing

import kotlinx.serialization.Serializable

@Serializable
internal data class ProdAnalogPage(val kind: String, val environment: String, val batches: List<ProdAnalogBatch>, val nextCursor: String?)
