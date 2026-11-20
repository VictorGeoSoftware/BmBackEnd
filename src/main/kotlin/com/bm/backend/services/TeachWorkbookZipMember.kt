package com.bm.backend.services

/** A member independently verified by the bounded preflight, before opening the ZIP reader. */
internal data class TeachWorkbookZipMember(
    val localOffset: Int,
    val dataOffset: Int,
    val compressedSize: Int,
    val size: Long,
    val crc: Long,
    val method: Int,
    val flags: Int,
)
