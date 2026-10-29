package com.bm.backend.models

/** Upstream comparison of portal vs. contracted powers; stored only, never changes status or values. */
enum class ContractedPowerCheck {
    MATCH,
    MISMATCH,
    NOT_COMPARABLE
}
