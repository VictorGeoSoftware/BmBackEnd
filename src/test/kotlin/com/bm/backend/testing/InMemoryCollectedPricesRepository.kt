package com.bm.backend.testing

import com.bm.backend.models.CollectedPrice
import com.bm.backend.repositories.ports.CollectedPricesRepositoryPort

/**
 * In-memory [CollectedPricesRepositoryPort] for service unit tests.
 *
 * Mirrors the real adapter's ordering contract (most recently collected first) so
 * pagination behaviour can be asserted without a database.
 */
class InMemoryCollectedPricesRepository : CollectedPricesRepositoryPort {

    val stored = mutableListOf<CollectedPrice>()
    private var nextId = 1

    override fun insert(collectedPrice: CollectedPrice): CollectedPrice {
        val persisted = collectedPrice.copy(id = nextId++)
        stored.add(persisted)
        return persisted
    }

    override fun findPage(
        limit: Int,
        offset: Int,
        tariffType: String?,
        companyNameNormalized: String?
    ): List<CollectedPrice> = filtered(tariffType, companyNameNormalized)
        .sortedByDescending { collectedPrice -> collectedPrice.collectedAt }
        .drop(offset)
        .take(limit)

    override fun count(tariffType: String?, companyNameNormalized: String?): Long =
        filtered(tariffType, companyNameNormalized).size.toLong()

    private fun filtered(
        tariffType: String?,
        companyNameNormalized: String?
    ): List<CollectedPrice> = stored.filter { collectedPrice ->
        (tariffType == null || collectedPrice.tariffType == tariffType) &&
            (companyNameNormalized == null ||
                collectedPrice.companyNameNormalized == companyNameNormalized)
    }
}
