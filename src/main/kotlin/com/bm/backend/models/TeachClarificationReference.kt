package com.bm.backend.models

import java.nio.ByteBuffer

internal class TeachClarificationReference(val kind: Kind, bytes: ByteArray) {
    enum class Kind { PDF, PNG }

    private val data = ByteBuffer.wrap(bytes).asReadOnlyBuffer()
    val bytes: ByteBuffer get() = data.asReadOnlyBuffer()
}
