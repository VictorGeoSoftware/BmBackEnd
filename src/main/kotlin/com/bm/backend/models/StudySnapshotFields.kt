package com.bm.backend.models

/**
 * Supply snapshot values exactly as published upstream (contract v1).
 * Absent optional fields are `null`; dates are the source ISO strings after strict validation.
 * Energies in kWh, powers in kW (no conversion).
 */
data class StudySnapshotFields(
    val cups: String,
    val distributorCode: String? = null,
    val distributorName: String? = null,
    val postalCode: String? = null,
    val province: String? = null,
    val municipality: String? = null,
    val tariff: String? = null,
    val supplyStartDate: String? = null,
    val lastContractChangeDate: String? = null,
    val lastSupplierChangeDate: String? = null,
    val lastReadingDate: String? = null,
    val annualConsumptionKwh: Double? = null,
    val maxPowerBieKw: Double? = null,
    val maxPowerApmKw: Double? = null,
    val accessRightsKw: Double? = null,
    val extensionRightsKw: Double? = null,
    val annualConsumptionPeriodsKwh: PeriodValues? = null,
    val contractedPowerKw: PeriodValues? = null
)
