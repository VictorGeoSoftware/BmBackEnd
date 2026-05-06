package com.bm.backend.repositories

import com.bm.backend.models.UserConsumption
import com.bm.backend.repositories.ports.UserConsumptionRepositoryPort
import kotlinx.coroutines.flow.MutableStateFlow

class UserConsumptionRepository : UserConsumptionRepositoryPort {
    private val _consumptionReport = MutableStateFlow<UserConsumption?>(null)

    override fun storeConsumptionData(consumptionReport: UserConsumption) {
        _consumptionReport.value = consumptionReport
    }

    override fun getConsumptionReport(): UserConsumption? {
        return _consumptionReport.value
    }
}
