package com.bm.backend.models

/** Classification of the latest 12-month maximeter demand window (spec report-demand-summary, M1). */
enum class DemandWindowStatus {
    COMPLETE,
    INCOMPLETE,
    UNAVAILABLE
}
