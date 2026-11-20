package com.bm.backend.services

internal data class TeachWorkbookInspectionLimits(
    val xmlPart: Int = 256 * 1024,
    val xmlTotal: Int = 2 * 1024 * 1024,
    val depth: Int = 16,
    val attributes: Int = 32,
    val events: Int = 100_000,
    val sheets: Int = 8,
    val strings: Int = 1_024,
    val rows: Int = 512,
    val cells: Int = 1_024,
    val text: Int = 1_024,
    val response: Int = 64 * 1024,
)
