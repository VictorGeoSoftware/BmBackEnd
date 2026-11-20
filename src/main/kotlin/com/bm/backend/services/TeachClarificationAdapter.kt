package com.bm.backend.services

import com.bm.backend.models.TeachClarificationContext
import com.bm.backend.models.TeachClarificationReference.Kind

/** Local, effect-free port. It cannot return text, coordinates or an import decision. */
internal fun interface TeachClarificationAdapter {
    enum class Decision { ASK_CANDIDATES, ACKNOWLEDGE_REPLY }

    fun respond(context: TeachClarificationContext): Decision
}

internal val fixedTeachClarificationAdapter = TeachClarificationAdapter { context ->
    val kinds = context.references.map { it.kind }
    if (context.answer == null) {
        check(kinds.isEmpty())
        TeachClarificationAdapter.Decision.ASK_CANDIDATES
    } else {
        check(kinds.size <= 1 || kinds == listOf(Kind.PDF, Kind.PNG))
        TeachClarificationAdapter.Decision.ACKNOWLEDGE_REPLY
    }
}
