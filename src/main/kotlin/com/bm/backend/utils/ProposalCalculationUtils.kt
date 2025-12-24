package com.bm.backend.utils

import com.bm.backend.models.CleanedConsumptionData
import com.bm.backend.models.FilteredPriceTableResponse
import com.bm.backend.models.ProposalPriceModel

/**
 * Calculates proposal prices from consumption data and filtered price table results
 */
fun calculateProposals(
    consumptionData: CleanedConsumptionData,
    filteredPrices: FilteredPriceTableResponse
): List<ProposalPriceModel> {
    val proposals = mutableListOf<ProposalPriceModel>()
    
    // Get subscribed power values (non-zero)
    val subscribedPowers = listOf(
        consumptionData.subscribedPowerP1,
        consumptionData.subscribedPowerP2,
        consumptionData.subscribedPowerP3,
        consumptionData.subscribedPowerP4,
        consumptionData.subscribedPowerP5,
        consumptionData.subscribedPowerP6
    )
    
    // Get annual consumption values (non-zero)
    val annualConsumptions = listOf(
        consumptionData.annualConsumptionP1,
        consumptionData.annualConsumptionP2,
        consumptionData.annualConsumptionP3,
        consumptionData.annualConsumptionP4,
        consumptionData.annualConsumptionP5,
        consumptionData.annualConsumptionP6
    )
    
    // Process each price table result
    for (result in filteredPrices.results) {
        val extractedTables = result.extracted_tables
        
        // Get power term prices
        val powerPrices = extractedTables.termino_de_potencia.tabla_precio_potencia.tarifa
        val powerPricesList = listOf(
            powerPrices.P1, powerPrices.P2, powerPrices.P3,
            powerPrices.P4, powerPrices.P5, powerPrices.P6
        )
        
        // Process energy price table (base)
        val baseTable = extractedTables.termino_de_energia.tabla_precio_clasica_base
        val energyPrices = baseTable.tarifa
        val energyPricesList: List<Double?> = listOf(
            energyPrices.P1, energyPrices.P2, energyPrices.P3,
            energyPrices.P4, energyPrices.P5, energyPrices.P6
        )
        
        // Get power term prices from table (only for non-zero subscribed powers)
        val powerTermItems = subscribedPowers.zip(powerPricesList)
            .filter { (power, price) -> power > 0.0 && price != null }
            .map { (_, price) -> price!! }
        
        // Calculate annual power term cost
        val annualPowerTermCost = subscribedPowers.zip(powerPricesList)
            .sumOf { (power, price) ->
                if (price != null && power > 0.0) {
                    power * price * 365
                } else {
                    0.0
                }
            }
        
        // Get energy prices from table (only for non-zero consumptions)
        val consumedEnergyItems = annualConsumptions.zip(energyPricesList)
            .filter { (consumption, price) -> consumption > 0.0 && price != null }
            .map { (_, price) -> price!! / 100.0 }  // Convert c€/kWh to €/kWh
        
        // Calculate annual energy cost (convert from c€/kWh to €/kWh by dividing by 100)
        val annualEnergyCost: Double = annualConsumptions.zip(energyPricesList)
            .sumOf { (consumption: Double, price: Double?) ->
                if (price != null && consumption > 0.0) {
                    consumption * (price / 100.0)  // Convert c€/kWh to €/kWh
                } else {
                    0.0
                }
            }
        
        // Extra services (currently 0.0)
        val extraServices = 0.0
        
        // Calculate IVA multiplier
        val ivaAmount = (annualPowerTermCost + annualEnergyCost) * (filteredPrices.iva.toDouble() / 100.0)

        // Calculate electrical tax
        val electricalTax = (annualPowerTermCost + annualEnergyCost) * (filteredPrices.impuestoElectrico / 100.0)

        // Calculate total annual price
        val totalAnnualPrice = annualPowerTermCost + annualEnergyCost + extraServices + ivaAmount + electricalTax

        val cleanedTitle = baseTable.titulo
            .replace("PRECIO", "", ignoreCase = true)
            .replace("(c€/kWh)", "")
            .trim()

        proposals.add(
            ProposalPriceModel(
                proposalTitle = cleanedTitle,
                powerTermItems = powerTermItems,
                annualPowerTermCost = annualPowerTermCost,
                consumedEnergyItems = consumedEnergyItems,
                annualEnergyCost = annualEnergyCost,
                extraServices = extraServices,
                iva = ivaAmount,
                electricalTax = electricalTax,
                totalAnnualPrice = totalAnnualPrice
            )
        )
    }
    
    return proposals
}
