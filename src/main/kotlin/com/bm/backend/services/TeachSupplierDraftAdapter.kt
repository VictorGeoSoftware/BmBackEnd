package com.bm.backend.services

import com.bm.backend.models.TeachClarificationCandidate
import com.bm.backend.models.TeachSupplierColumnChoice

internal fun interface TeachSupplierDraftAdapter {
    fun select(candidates: List<TeachClarificationCandidate>, choice: TeachSupplierColumnChoice): Int
}

internal val fixedTeachSupplierDraftAdapter = TeachSupplierDraftAdapter { candidates, choice ->
    candidates.indexOfFirst { it.a1 == choice.a1 }
}
