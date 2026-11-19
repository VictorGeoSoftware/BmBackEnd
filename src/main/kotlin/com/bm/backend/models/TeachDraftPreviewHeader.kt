package com.bm.backend.models

import kotlinx.serialization.Serializable

@Serializable
internal data class TeachDraftPreviewHeader(val a1: String, val label: String) {
    fun at(row: Int): String = a1.dropLastWhile(Char::isDigit) + row
}
