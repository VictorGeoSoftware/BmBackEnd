package com.bm.backend.services

import com.bm.backend.models.CollectedPrice
import com.bm.backend.models.CollectedPriceListResponse
import com.bm.backend.models.CollectedPriceResponse
import com.bm.backend.models.CollectedPriceSubmission
import com.bm.backend.repositories.ports.CollectedPricesRepositoryPort
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * Business logic for collected prices: the customer's current electricity prices as
 * entered by a broker, captured when they move on to the price proposals.
 *
 * Two rules live here:
 * - 2.0TD supplies are never collected. The app is the primary filter; this is
 *   defence in depth so an older build cannot pollute the table.
 * - Supplier and tariff are stored in both a display form and a canonical form, so
 *   the dashboard can group free-text supplier names without losing what was typed.
 */
class CollectedPricesService(
    private val collectedPricesRepository: CollectedPricesRepositoryPort,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = LoggerFactory.getLogger(CollectedPricesService::class.java)

    sealed interface SubmitResult {
        data class Stored(val collectedPrice: CollectedPrice) : SubmitResult

        /** The tariff is 2.0TD, which is out of scope for collection. */
        data class ExcludedTariff(val tariffType: String) : SubmitResult
    }

    fun submit(submission: CollectedPriceSubmission): SubmitResult {
        val canonicalTariff = CollectedPriceNormalizer.canonicalTariff(submission.tariffType)
        if (canonicalTariff == CollectedPriceNormalizer.EXCLUDED_TARIFF_CANONICAL) {
            logger.info(
                "AUDIT: Collected prices rejected, excluded tariff tariff={}",
                submission.tariffType
            )
            return SubmitResult.ExcludedTariff(submission.tariffType)
        }

        val collectedPrice = CollectedPrice(
            companyName = CollectedPriceNormalizer.displayCompanyName(submission.companyName),
            companyNameNormalized =
                CollectedPriceNormalizer.normalizeCompanyName(submission.companyName),
            tariffType = CollectedPriceNormalizer.displayTariff(submission.tariffType),
            powerPrices = submission.powerPricesByPeriod.toPeriodList(),
            energyPrices = submission.energyPricesByPeriod.toPeriodList(),
            extraServices = submission.extraServices,
            collectedAt = Instant.now(clock)
        )

        return SubmitResult.Stored(collectedPricesRepository.insert(collectedPrice))
    }

    /**
     * Returns one page of collected prices for the dashboard, most recent first.
     *
     * [limit] is clamped to [MAX_PAGE_SIZE] and [offset] to zero, so a hand-crafted
     * query string cannot ask for the whole table at once.
     */
    fun list(
        limit: Int,
        offset: Int,
        tariffType: String?,
        companyName: String?
    ): CollectedPriceListResponse {
        val effectiveLimit = limit.coerceIn(1, MAX_PAGE_SIZE)
        val effectiveOffset = offset.coerceAtLeast(0)
        val tariffFilter = tariffType
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> CollectedPriceNormalizer.displayTariff(value) }
        val companyFilter = companyName
            ?.takeIf { value -> value.isNotBlank() }
            ?.let { value -> CollectedPriceNormalizer.normalizeCompanyName(value) }

        val items = collectedPricesRepository.findPage(
            limit = effectiveLimit,
            offset = effectiveOffset,
            tariffType = tariffFilter,
            companyNameNormalized = companyFilter
        )

        return CollectedPriceListResponse(
            success = true,
            items = items.map { collectedPrice -> collectedPrice.toResponse() },
            total = collectedPricesRepository.count(
                tariffType = tariffFilter,
                companyNameNormalized = companyFilter
            ),
            limit = effectiveLimit,
            offset = effectiveOffset
        )
    }

    /**
     * Expands a partial "P1".."P6" map into a dense 6-entry list, leaving nulls for
     * periods the tariff does not use.
     */
    private fun Map<String, Double>.toPeriodList(): List<Double?> =
        (1..CollectedPrice.PERIOD_COUNT).map { period -> this["P$period"] }

    private fun CollectedPrice.toResponse(): CollectedPriceResponse = CollectedPriceResponse(
        id = requireNotNull(id) { "Persisted collected price must have an id" },
        companyName = companyName,
        tariffType = tariffType,
        powerPrices = powerPrices,
        energyPrices = energyPrices,
        extraServices = extraServices,
        collectedAt = collectedAt.toEpochMilli()
    )

    companion object {
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
    }
}
