package com.bm.backend.testing

import com.bm.backend.models.TeachPromotionReceipt
import com.bm.backend.services.GESTERNOVA_SUPPLIER
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Separately provisioned in-process authorities. An opened PR provides no deployment capability. */
internal class ProdAnalogMergeGate(private val provider: TeachPromotionFake) : AutoCloseable {
    private val merged = mutableSetOf<TeachPromotionReceipt>()
    private val closed = mutableSetOf<TeachPromotionReceipt>()
    @Synchronized fun simulateMerge(receipt: TeachPromotionReceipt) {
        check(receipt in provider.inspect() && receipt !in closed && receipt.proposal.base == "protected-fixture")
        merged.add(receipt)
    }
    @Synchronized fun closeUnmerged(receipt: TeachPromotionReceipt) {
        check(receipt in provider.inspect() && receipt !in merged)
        closed.add(receipt)
    }
    @Synchronized fun reopen(receipt: TeachPromotionReceipt) { check(closed.remove(receipt)) }
    @Synchronized fun permits(receipt: TeachPromotionReceipt) = receipt in merged
    @Synchronized override fun close() { merged.clear(); closed.clear() }
}

/** No external I/O. Only executor-owned outcomes for the identical in-memory attempt count. */
internal class ProdAnalogFakeExecutor : AutoCloseable {
    enum class Outcome { SUCCESS, FAILURE, PARTIAL }
    private val completed = mutableMapOf<ProdAnalogDeployment, Outcome>()
    @Synchronized fun run(attempt: ProdAnalogDeployment, outcome: Outcome) {
        check(attempt.target == PROD_ANALOG && completed.putIfAbsent(attempt, outcome) == null)
    }
    @Synchronized fun succeeded(attempt: ProdAnalogDeployment) = completed[attempt] == Outcome.SUCCESS
    @Synchronized override fun close() { completed.clear() }
}

internal class ProdAnalogDeploymentClock : AutoCloseable {
    private val ordinal = AtomicLong()
    private val issued = mutableSetOf<ProdAnalogDeployment>()
    @Synchronized fun issue(binding: ProdAnalogBinding, candidate: ProdAnalogCandidate) =
        ProdAnalogDeployment(UUID.randomUUID(), ordinal.incrementAndGet(), binding, PROD_ANALOG, candidate).also { issued.add(it) }
    @Synchronized fun issued(attempt: ProdAnalogDeployment) = attempt in issued
    @Synchronized override fun close() { issued.clear() }
}

/** In-memory ordinal source belongs to the lifetime of the disposable binding, not to Netty. */
internal class ProdAnalogDeploymentAuthority(
    private val binding: ProdAnalogBinding,
    private val catalog: ProdAnalogCatalog,
    private val merge: ProdAnalogMergeGate,
    private val executor: ProdAnalogFakeExecutor,
    private val store: ProdAnalogReleaseWriter,
    private val clock: ProdAnalogDeploymentClock,
) {
    init {
        if (store.binding != binding) throw ProdAnalogUnavailable()
        store.verify()
    }

    fun begin(receipt: TeachPromotionReceipt): ProdAnalogDeployment {
        if (!merge.permits(receipt)) throw ProdAnalogUnavailable()
        val proposal = receipt.proposal
        val candidate = catalog.candidate(GESTERNOVA_SUPPLIER, proposal.diff.revision)
        if (proposal.base != "protected-fixture" || proposal.destination != "bm-pilot/skills-fixture" ||
            proposal.diff != candidate.canonical) throw ProdAnalogUnavailable()
        return clock.issue(binding, candidate)
    }

    fun finish(attempt: ProdAnalogDeployment, bytes: ByteArray = attempt.candidate.canonical.artifact.encodeToByteArray(),
               digest: String = attempt.candidate.canonical.digest): Boolean {
        if (attempt.binding != binding || attempt.target != PROD_ANALOG || !clock.issued(attempt) || !executor.succeeded(attempt))
            throw ProdAnalogUnavailable()
        val exact = attempt.candidate.exact
        val release = ProdAnalogRelease(RELEASE_SCHEMA, attempt.target, binding, exact.supplierKey, attempt.id, attempt.ordinal,
            exact.revisionId, exact.semantics, exact.manifest, digest, bytes)
        catalog.verify(binding, release)
        return store.commit(release)
    }
}
