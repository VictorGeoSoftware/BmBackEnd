package com.bm.backend.models

internal sealed interface TeachWorkbookAuthDecision {
    data object Allowed : TeachWorkbookAuthDecision
    data object InvalidToken : TeachWorkbookAuthDecision
    data object NotAdmin : TeachWorkbookAuthDecision
    data object NotNominated : TeachWorkbookAuthDecision
}
