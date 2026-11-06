package com.bm.backend.models

/** Single reason a report dataset cannot be assembled; codes never carry customer values. */
enum class ReportDatasetRejection(val code: String) {
    STUDY_NOT_FOUND("study_not_found"),
    STUDY_NOT_READY("study_not_ready"),
    HOLDER_MISSING("holder_missing"),
    ADDRESS_MISSING("address_missing"),
    CUPS_MISMATCH("cups_mismatch"),
    TARIFF_MISMATCH("tariff_mismatch"),
    CONSUMPTION_MISMATCH("consumption_mismatch"),
    NO_OFFERS("no_offers"),
    DUPLICATE_OFFER("duplicate_offer"),
    UNKNOWN_OFFER("unknown_offer"),
    STALE_OFFER("stale_offer"),
    INVALID_AMOUNT("invalid_amount"),
    TAX_MISMATCH("tax_mismatch"),
    CURRENT_CONDITIONS_MISSING("current_conditions_missing")
}
