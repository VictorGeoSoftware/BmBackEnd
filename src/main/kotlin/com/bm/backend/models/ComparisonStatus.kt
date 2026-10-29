package com.bm.backend.models

/** Outcome of comparing an annual source against the history window. */
enum class ComparisonStatus {
    COMPARED,
    NOT_COMPARABLE,
    SOURCE_UNAVAILABLE
}
