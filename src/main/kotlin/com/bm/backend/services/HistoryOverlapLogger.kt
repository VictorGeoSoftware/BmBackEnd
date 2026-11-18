package com.bm.backend.services

import com.bm.backend.models.HistoryOverlapEffect
import com.bm.backend.models.N8nRunReference
import com.bm.backend.models.StudyHistory
import com.bm.backend.utils.HistoryCoverageCalculator
import com.bm.backend.utils.HistoryOverlapFindings
import net.logstash.logback.argument.StructuredArguments.kv
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Logs one line per completed study whose history has overlapping rows (TECH_DEBT #18), so the
 * frequency and the concrete cases can be reviewed in Grafana:
 * `{job="bm-backend"} |= "History overlaps found"`.
 *
 * Everything is in the message text (Promtail keeps only the message). Dates, row numbers and
 * effects only; no consumption values. The CUPS is included so the case can be discussed and the
 * source Excel found; the n8n link opens the execution that downloaded it.
 */
class HistoryOverlapLogger(
    private val n8nPublicUrl: String?,
    private val logger: Logger = LoggerFactory.getLogger(HistoryOverlapLogger::class.java)
) {
    fun report(jobId: String, requestId: String?, cups: String, history: StudyHistory, n8nRun: N8nRunReference?) {
        try {
            val findings = HistoryOverlapFindings.from(history, HistoryCoverageCalculator.calculate(history))
            if (findings.isEmpty()) return
            logger.info(
                "History overlaps found {} {} {} {} {} {} {} {}",
                kv("jobId", jobId),
                kv("requestId", requestId),
                kv("cups", cups),
                kv("overlaps", findings.size),
                kv("blocking", findings.count { it.effect == HistoryOverlapEffect.BLOCKS_TOTAL }),
                kv("n8nExecutionId", n8nRun?.executionId ?: "unknown"),
                kv("n8nRun", n8nRun?.url(n8nPublicUrl) ?: "unknown"),
                kv("details", findings.joinToString("; ") { HistoryOverlapFindings.describe(it) })
            )
        } catch (e: Exception) {
            // Diagnostics must never affect the study.
            logger.warn("History overlap logging failed {}", kv("jobId", jobId))
        }
    }

    companion object {
        const val ENV_N8N_PUBLIC_URL = "N8N_PUBLIC_URL"
        fun fromEnv(): HistoryOverlapLogger = HistoryOverlapLogger(System.getenv(ENV_N8N_PUBLIC_URL))
    }
}
