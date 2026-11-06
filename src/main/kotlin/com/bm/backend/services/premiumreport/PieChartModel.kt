package com.bm.backend.services.premiumreport

import com.bm.backend.models.PeriodValues

/** Pure geometry of the P1–P6 share pie (original study's "Porcentajes por tramos horarios"); zero shares omitted. */
data class PieChartModel(
    val width: Double,
    val height: Double,
    val centerX: Double,
    val centerY: Double,
    val radius: Double,
    val slices: List<PieSlice>
) {
    companion object {
        fun of(shares: PeriodValues, periods: List<Int>): PieChartModel? {
            val all = listOf(shares.p1, shares.p2, shares.p3, shares.p4, shares.p5, shares.p6)
            val parts = periods.mapNotNull { p -> all[p - 1]?.takeIf { it > 0.0 }?.let { p to it } }
            val sum = parts.sumOf { it.second }
            if (parts.isEmpty() || sum <= 0.0) return null
            var start = 90.0
            val slices = parts.map { (p, share) ->
                val extent = -share / sum * 360.0
                PieSlice(p, share, start, extent, "P$p ${PremiumReportFormat.percent(share)}").also { start += extent }
            }
            return PieChartModel(ActiveChartModel.WIDTH, 170.0, 150.0, 85.0, 70.0, slices)
        }
    }
}
