package com.bm.backend.services

/** Signals that no Premium report document is available yet (mapped to 501 `report_unavailable`). */
class PremiumReportUnavailableException : RuntimeException("Premium report renderer not available")
