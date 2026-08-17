package com.bm.backend.repositories.ports

import com.bm.backend.models.CollectedPrice

/**
 * Port (Clean Architecture) for the collected-prices store.
 *
 * Implementations must guarantee:
 * - Rows are immutable once written; there is no update or delete path.
 * - [findPage] returns rows most recently collected first.
 * - Filters are applied identically by [findPage] and [count], so that the returned
 *   total always matches the filtered page.
 */
interface CollectedPricesRepositoryPort {

    /**
     * Persists [collectedPrice] and returns it with the generated id populated.
     */
    fun insert(collectedPrice: CollectedPrice): CollectedPrice

    /**
     * Returns one page of rows, most recently collected first.
     *
     * @param tariffType when non-null, restricts to rows with this exact (already
     * normalized) tariff.
     * @param companyNameNormalized when non-null, restricts to rows with this exact
     * canonical supplier form.
     */
    fun findPage(
        limit: Int,
        offset: Int,
        tariffType: String? = null,
        companyNameNormalized: String? = null
    ): List<CollectedPrice>

    /**
     * Returns the number of rows matching the same filters as [findPage], ignoring
     * pagination.
     */
    fun count(
        tariffType: String? = null,
        companyNameNormalized: String? = null
    ): Long
}
