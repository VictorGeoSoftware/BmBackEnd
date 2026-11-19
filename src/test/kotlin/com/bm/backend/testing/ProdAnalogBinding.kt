package com.bm.backend.testing

import java.util.UUID

internal const val PROD_ANALOG = "prod-analog"
internal const val RELEASE_SCHEMA = "bm.teach.prod-analog-release/v1"

internal data class ProdAnalogBinding(val store: UUID, val authority: UUID, val destination: UUID)
