package com.bm.backend.observability

/**
 * Deployment identity of this process, resolved once from the environment.
 *
 * The same artifact runs in local / QA / PROD; these values are what let a
 * single Grafana instance tell those environments apart when querying metrics
 * (as Prometheus common tags) and logs (as fields emitted by `logback.xml`).
 */
object DeploymentInfo {

    /** `local` | `qa` | `prod`. */
    val environment: String = System.getenv("BM_ENV")?.takeIf { it.isNotBlank() } ?: "local"

    /** Logical service name, so multiple BM services can share one Grafana. */
    val serviceName: String = System.getenv("SERVICE_NAME")?.takeIf { it.isNotBlank() } ?: "bm-backend"
}
