package com.bm.backend.services.premiumreport

/** One cell of the economic comparison table, in reading order. [row]/[column] are the cell's top-left grid slot. */
data class ComparisonCell(
    val row: Int,
    val column: Int,
    val text: String,
    val style: ComparisonCellStyle,
    val colSpan: Int = 1,
    val rowSpan: Int = 1
)
