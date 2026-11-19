package com.bm.backend.services

import com.bm.backend.models.TeachWorkbookAuthDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** No queued tasks: an admitted token is handed directly to one of four private workers. */
internal class TeachWorkbookAuthWorkers(
    private val decideValidation: ((String) -> TeachWorkbookAuthDecision)? = null,
    private val decide: (String) -> TeachWorkbookAuthDecision,
) : AutoCloseable {
    private val workerId = AtomicInteger()
    private val executor = ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, SynchronousQueue(), { task ->
        Thread(task, "teach-workbook-auth-${workerId.incrementAndGet()}").apply { isDaemon = true }
    })

    fun submit(token: String, validationWire: Boolean = false): Deferred<Result<TeachWorkbookAuthDecision>>? {
        val result = CompletableDeferred<Result<TeachWorkbookAuthDecision>>()
        try {
            executor.execute {
                // This worker owns only the token and a decision. Cancelling the result cannot
                // free the worker: it remains unavailable until decide() actually returns/throws.
                result.complete(runCatching { (if (validationWire) decideValidation ?: decide else decide)(token) })
            }
        } catch (_: RejectedExecutionException) {
            return null
        }
        return result
    }

    // Stop taking work without waiting on uninterruptible dependencies during Ktor shutdown.
    // Existing tasks exit normally; daemon workers do not hold the process open.
    override fun close() = executor.shutdown()
}
