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

    /**
     * Upserts each result by its natural key (fileName, companyName).
     *
     * When an existing row is overwritten and [sourceSha256] is non-null and differs
     * from the stored hash, the row's version is incremented. A null hash (n8n batch
     * path) overwrites without bumping the version.
     */
    fun storePriceTableResults(priceTableResponse: PriceTableResponse, sourceSha256: String? = null): Int

    /** True when at least one stored result was extracted from a PDF with this hash. */
    fun existsBySourceSha256(sourceSha256: String): Boolean

    fun getAllPriceTableResults(tarifaType: String? = null): PriceTableResponse

    fun getFilteredPriceTableResults(tarifaType: String? = null): FilteredPriceTableResponse

    fun clearAllData(): Int

    /**
     * Returns a pair of (deleted ids, not-found ids) preserving input order
     * after de-duplication.
     */
    fun deleteResultsByIds(ids: List<Int>): Pair<List<Int>, List<Int>>
}
