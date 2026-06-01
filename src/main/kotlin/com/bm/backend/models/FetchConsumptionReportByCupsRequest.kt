package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class FetchConsumptionReportByCupsRequest(
    val cupsCode: String,
)
