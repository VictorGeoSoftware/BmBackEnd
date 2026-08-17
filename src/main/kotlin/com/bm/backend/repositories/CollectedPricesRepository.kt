package com.bm.backend.repositories

import com.bm.backend.database.CollectedPricesDb
import com.bm.backend.models.CollectedPrice
import com.bm.backend.repositories.ports.CollectedPricesRepositoryPort
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Exposed adapter for the `collected_prices` table.
 *
 * Rows are append-only: there is no update or delete path by design, so a broker
 * cannot retroactively change what was collected.
 */
class CollectedPricesRepository : CollectedPricesRepositoryPort {

    private val logger = LoggerFactory.getLogger(CollectedPricesRepository::class.java)

    private val powerColumns: List<Column<Double?>> = listOf(
        CollectedPricesDb.powerP1,
        CollectedPricesDb.powerP2,
        CollectedPricesDb.powerP3,
        CollectedPricesDb.powerP4,
        CollectedPricesDb.powerP5,
        CollectedPricesDb.powerP6
    )

    private val energyColumns: List<Column<Double?>> = listOf(
        CollectedPricesDb.energyP1,
        CollectedPricesDb.energyP2,
        CollectedPricesDb.energyP3,
        CollectedPricesDb.energyP4,
        CollectedPricesDb.energyP5,
        CollectedPricesDb.energyP6
    )

    override fun insert(collectedPrice: CollectedPrice): CollectedPrice = transaction {
        val generatedId = CollectedPricesDb.insertAndGetId { statement ->
            statement[companyName] = collectedPrice.companyName
            statement[companyNameNormalized] = collectedPrice.companyNameNormalized
            statement[tariffType] = collectedPrice.tariffType
            statement[extraServices] = collectedPrice.extraServices
            statement[collectedAt] = collectedPrice.collectedAt
            powerColumns.forEachIndexed { index, column ->
                statement[column] = collectedPrice.powerPrices[index]
            }
            energyColumns.forEachIndexed { index, column ->
                statement[column] = collectedPrice.energyPrices[index]
            }
        }.value

        logger.info(
            "AUDIT: Collected prices stored id={} tariff={} company={}",
            generatedId,
            collectedPrice.tariffType,
            collectedPrice.companyNameNormalized
        )

        collectedPrice.copy(id = generatedId)
    }

    override fun findPage(
        limit: Int,
        offset: Int,
        tariffType: String?,
        companyNameNormalized: String?
    ): List<CollectedPrice> = transaction {
        CollectedPricesDb
            .selectAll()
            .where { filterCondition(tariffType, companyNameNormalized) }
            .orderBy(
                CollectedPricesDb.collectedAt to SortOrder.DESC,
                CollectedPricesDb.id to SortOrder.DESC
            )
            .limit(limit, offset.toLong())
            .map { row -> row.toCollectedPrice() }
    }

    override fun count(
        tariffType: String?,
        companyNameNormalized: String?
    ): Long = transaction {
        CollectedPricesDb
            .selectAll()
            .where { filterCondition(tariffType, companyNameNormalized) }
            .count()
    }

    /**
     * Builds the shared filter used by both [findPage] and [count], so the reported
     * total can never disagree with the returned page.
     */
    private fun filterCondition(
        tariffType: String?,
        companyNameNormalized: String?
    ): Op<Boolean> {
        var condition: Op<Boolean> = Op.TRUE
        if (tariffType != null) {
            condition = condition and (CollectedPricesDb.tariffType eq tariffType)
        }
        if (companyNameNormalized != null) {
            condition = condition and
                (CollectedPricesDb.companyNameNormalized eq companyNameNormalized)
        }
        return condition
    }

    private fun ResultRow.toCollectedPrice(): CollectedPrice = CollectedPrice(
        id = this[CollectedPricesDb.id].value,
        companyName = this[CollectedPricesDb.companyName],
        companyNameNormalized = this[CollectedPricesDb.companyNameNormalized],
        tariffType = this[CollectedPricesDb.tariffType],
        powerPrices = powerColumns.map { column -> this[column] },
        energyPrices = energyColumns.map { column -> this[column] },
        extraServices = this[CollectedPricesDb.extraServices],
        collectedAt = this[CollectedPricesDb.collectedAt]
    )
}
