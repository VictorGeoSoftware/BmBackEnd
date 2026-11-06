package com.bm.backend.models

/** How the report's tariff periods were decided (spec report-tariff-applicability). */
enum class TariffProfileStatus {
    /** Recognised, supported tariff whose source values fit its periods. */
    SUPPORTED,
    /** No supported tariff could be identified (e.g. 6.1TD, blank, unparseable): periods present in the source. */
    FALLBACK_UNKNOWN,
    /** The snapshot and the comparison name indicate different tariffs: periods present in the source. */
    FALLBACK_CONFLICT,
    /** A supported tariff, but the source publishes non-zero values outside its periods: periods present in the source. */
    FALLBACK_INCONSISTENT
}
