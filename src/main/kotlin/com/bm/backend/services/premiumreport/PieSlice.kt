package com.bm.backend.services.premiumreport

/** One pie sector: tariff [period], its [share] (%), start angle and extent in degrees (counter-clockwise from 90°). */
data class PieSlice(
    val period: Int,
    val share: Double,
    val startDegrees: Double,
    val extentDegrees: Double,
    val label: String
)
