package com.bm.backend.testing

import com.bm.backend.models.TeachWorkbookAuthDecision
import com.bm.backend.models.TeachWorkbookPrincipal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One server-side audience per composition family. Tokens are never accepted from the QA verifier. */
internal class ProdAnalogAuthorization(val binding: ProdAnalogBinding, private val onVerify: () -> Unit = {}) : AutoCloseable {
    private val grants = ConcurrentHashMap<String, UUID>()
    private val suspended = ConcurrentHashMap.newKeySet<UUID>()

    fun grant(actor: UUID = UUID.randomUUID()) = opaque().also { grants[it] = actor }
    fun revoke(token: String) { suspended.add(grants.getValue(token)) }
    fun restore(token: String) { suspended.remove(grants.getValue(token)) }
    fun verify(token: String): TeachWorkbookAuthDecision { onVerify(); return current(token) }
    fun current(token: String): TeachWorkbookAuthDecision {
        val actor = grants[token] ?: return TeachWorkbookAuthDecision.InvalidToken
        return if (actor in suspended) TeachWorkbookAuthDecision.NotNominated else TeachWorkbookPrincipal(actor.toString())
    }
    override fun close() { grants.clear(); suspended.clear() }

    private fun opaque() = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")
}
