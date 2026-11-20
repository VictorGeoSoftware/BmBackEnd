package com.bm.backend.testing

internal fun supplierDraftWorkbook(): ByteArray = checkNotNull(
    object {}.javaClass.getResourceAsStream("/ensename/ensename-demo.xlsx"),
).use { it.readBytes() }

internal fun supplierDraftRevision(digest: String, a1: String): String = sha256(
    "bm.teach.supplier-draft/v1\ndemo-electricidad\n$digest\nfixed_price_column\nTarifas\n$a1".encodeToByteArray(),
)
