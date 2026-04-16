package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
data class CustomerCurrentConditions(
    val cups: String? = null,
    val atr_tariff: String? = null,
    val contracted_power_punta_kw: Double? = null,
    val contracted_power_valle_kw: Double? = null,
    val power_terms: List<PowerTermCondition> = emptyList(),
    val energy_terms: List<EnergyTermCondition> = emptyList(),
    val extra_services: List<ExtraServiceCondition> = emptyList(),
    val iva_percentage: Double? = null,
    val electrical_tax_percentage: Double? = null,
    val billing_period_days: Int? = null,
    val total_amount: Double? = null,
    val loyalty_discount: Double? = null,
)

@Serializable
data class PowerTermCondition(
    val period: String,
    val contracted_kw: Double? = null,
    val price_per_kw_day: Double? = null,
    val billed_amount: Double? = null,
    val period_description: String? = null,
)

@Serializable
data class EnergyTermCondition(
    val price_per_kwh: Double? = null,
    val consumed_kwh: Double? = null,
    val billed_amount: Double? = null,
    val period_description: String? = null,
)

@Serializable
data class ExtraServiceCondition(
    val name: String,
    val unit_price: Double? = null,
    val unit_type: String? = null,
    val quantity: Double? = null,
    val total_billed: Double? = null,
    val is_meter_rental: Boolean = false,
)
