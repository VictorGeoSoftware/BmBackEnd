package com.bm.backend.repositories.ports

import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.PriceTableResponse
import com.bm.backend.models.TaxSettingsResponse

/**
 * Port (Clean Architecture) for the price-table persistence boundary.
 *
 * Services depend on this interface, never on a concrete adapter, so that the
 * underlying engine (PostgreSQL) can be swapped without touching application logic.
 */
interface PriceTableRepositoryPort {

    fun getTaxSettings(): TaxSettingsResponse

    fun updateTaxSettings(iva: Double, impuestoElectrico: Double): TaxSettingsResponse

    fun storePriceTableResults(priceTableResponse: PriceTableResponse): Int

    fun getAllPriceTableResults(tarifaType: String? = null): PriceTableResponse

    fun getFilteredPriceTableResults(tarifaType: String? = null): FilteredPriceTableResponse

    fun clearAllData(): Int

    /**
     * Returns a pair of (deleted ids, not-found ids) preserving input order
     * after de-duplication.
     */
    fun deleteResultsByIds(ids: List<Int>): Pair<List<Int>, List<Int>>
}
