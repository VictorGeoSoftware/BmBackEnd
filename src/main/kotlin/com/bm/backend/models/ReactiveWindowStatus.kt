package com.bm.backend.models

/** Coverage status of one family's LATEST_12M window in the reactive summary. */
enum class ReactiveWindowStatus {
    COMPLETE,
    INCOMPLETE,
    OVERLAP_UNRESOLVED,
    UNAVAILABLE
}
