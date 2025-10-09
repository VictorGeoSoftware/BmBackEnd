package com.bm.backend.repositories

import com.bm.backend.models.UserConsumption
import kotlinx.coroutines.flow.MutableStateFlow

class UserConsumptionRepository {
    private val _consumptionReport = MutableStateFlow<UserConsumption?>(null)
    
    fun storeConsumptionData(consumptionReport: UserConsumption) {
        _consumptionReport.value = consumptionReport
    }
    
    fun getConsumptionReport(): UserConsumption? {
        return _consumptionReport.value
    }
}
