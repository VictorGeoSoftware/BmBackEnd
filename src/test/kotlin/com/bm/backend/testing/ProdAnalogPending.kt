package com.bm.backend.testing

import com.bm.backend.models.TeachDraftPreviewRow

internal data class ProdAnalogPending(val release: ProdAnalogRelease, val rows: List<TeachDraftPreviewRow>)
