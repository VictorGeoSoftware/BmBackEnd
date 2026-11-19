package com.bm.backend.testing

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A wire response can finish before the handler's finally returns its upload permit. */
internal class TeachCallCompletion {
    private val finished = LinkedBlockingQueue<Unit>()

    fun install(app: Application, tracked: (ApplicationCall) -> Boolean = { true }) {
        app.intercept(ApplicationCallPipeline.Monitoring) {
            if (tracked(call)) {
                try { proceed() } finally { finished.add(Unit) }
            } else proceed()
        }
    }

    /** One signal per ordered assertion. Not used to wait for admission on deliberately concurrent calls. */
    fun await() {
        check(finished.poll(4, TimeUnit.SECONDS) != null) { "Previous Enséñame call did not finish its application pipeline" }
    }
}
