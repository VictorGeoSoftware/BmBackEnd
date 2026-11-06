package com.bm.backend.models

/** Whether an active-energy window has a firm total, a partial total, an unresolved overlap or no data. */
enum class ActiveWindowStatus {
    COMPLETE,
    INCOMPLETE,
    OVERLAP_UNRESOLVED,
    UNAVAILABLE
}
