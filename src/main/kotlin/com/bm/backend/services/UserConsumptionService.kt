package com.bm.backend.services

import com.bm.backend.models.UserConsumption
import com.bm.backend.repositories.UserConsumptionRepository

class UserConsumptionService(private val userConsumptionRepository: UserConsumptionRepository) {
    
    fun processConsumptionReport(consumptionReport: UserConsumption) {
        // Store the consumption data
        userConsumptionRepository.storeConsumptionData(consumptionReport)
    }
    
    fun getConsumptionReport(): UserConsumption? {
        return userConsumptionRepository.getConsumptionReport()
    }
}
