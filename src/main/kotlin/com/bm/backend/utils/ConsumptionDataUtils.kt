package com.bm.backend.utils

import com.bm.backend.models.CleanedConsumptionData
import com.bm.backend.models.N8nConsumptionData

/**
 * Converts N8nConsumptionData to CleanedConsumptionData by cleaning all string values
 * and converting numeric strings to their appropriate types.
 */
fun N8nConsumptionData.toCleanedData(processedAt: String): CleanedConsumptionData {
    return CleanedConsumptionData(
        cups = this.cups.cleanAndConvert(),
        tarifa = this.tarifa.cleanAndConvert(),
        tarifaValue = this.tarifaValue.cleanAndConvert(),
        annualConsumption = this.annualConsumption.cleanAndConvertToDouble(),
        annualConsumptionP1 = this.annualConsumptionP1.cleanAndConvertToDouble(),
        annualConsumptionP2 = this.annualConsumptionP2.cleanAndConvertToDouble(),
        annualConsumptionP3 = this.annualConsumptionP3.cleanAndConvertToDouble(),
        annualConsumptionP4 = this.annualConsumptionP4.cleanAndConvertToDouble(),
        annualConsumptionP5 = this.annualConsumptionP5.cleanAndConvertToDouble(),
        annualConsumptionP6 = this.annualConsumptionP6.cleanAndConvertToDouble(),
        subscribedPowerP1 = this.subscribedPowerP1.cleanAndConvertToDouble(),
        subscribedPowerP2 = this.subscribedPowerP2.cleanAndConvertToDouble(),
        subscribedPowerP3 = this.subscribedPowerP3.cleanAndConvertToDouble(),
        subscribedPowerP4 = this.subscribedPowerP4.cleanAndConvertToDouble(),
        subscribedPowerP5 = this.subscribedPowerP5.cleanAndConvertToDouble(),
        subscribedPowerP6 = this.subscribedPowerP6.cleanAndConvertToDouble(),
        feeType = this.feeType.cleanAndConvert(),
        fileName = this.fileName.cleanAndConvert(),
        processedAt = processedAt
    )
}
