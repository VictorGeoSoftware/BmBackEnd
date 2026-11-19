package com.bm.backend.testing

import com.bm.backend.services.GESTERNOVA_PREVIEW_PROFILE
import com.bm.backend.services.GESTERNOVA_SUPPLIER
import com.bm.backend.services.gesternovaPreviewRule
import com.bm.backend.services.previewRevision
import com.bm.backend.services.sha256Hex
import java.security.MessageDigest

internal class TeachActivationWorkbook(val bytes: ByteArray) {
    val digest = sha256Hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    val revision = previewRevision(digest, gesternovaPreviewRule)
    fun preview() = clarificationFrame(
        """[1,"preview",${bytes.size},"$GESTERNOVA_PREVIEW_PROFILE","$GESTERNOVA_SUPPLIER","$digest","$revision"]""", bytes,
    )
}

internal fun activationWorkbooks(): List<TeachActivationWorkbook> {
    val a = previewWorkbook()
    fun alternate(replacement: String) = editPreviewWorkbook { xml ->
        val old = """r="K16" t="n"><v>0.12125</v>"""
        check(xml.indexOf(old) >= 0 && xml.indexOf(old) == xml.lastIndexOf(old))
        xml.replace(old, old.replace("0.12125", replacement))
    }
    return listOf(a, alternate("0.13125"), alternate("0.14125")).map(::TeachActivationWorkbook)
}
