package com.bm.backend.utils

/** Upstream CUPS rule: equal after trim/upper-case, or equal 20-char core when exactly one side has the suffix. */
object CupsMatch {
    fun same(reference: String, candidate: String): Boolean {
        val a = reference.trim().uppercase()
        val b = candidate.trim().uppercase()
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        return shorter.length == 20 && longer.length == 22 && longer.startsWith(shorter)
    }
}
