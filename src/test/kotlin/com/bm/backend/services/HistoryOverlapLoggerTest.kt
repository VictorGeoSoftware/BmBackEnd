package com.bm.backend.services

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.bm.backend.models.N8nRunReference
import com.bm.backend.models.PeriodValues
import com.bm.backend.models.StudyHistory
import com.bm.backend.models.StudyHistoryInterval
import com.bm.backend.models.StudyHistoryStatus
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryOverlapLoggerTest {
    private val cups = "ES0000000000000000AA0F"

    private fun capture(block: (org.slf4j.Logger) -> Unit): List<String> {
        val logger = LoggerFactory.getLogger("HistoryOverlapLoggerTest-${System.nanoTime()}") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        block(logger)
        return appender.list.map { it.formattedMessage }
    }

    private fun row(start: String, end: String, p: Double) = StudyHistoryInterval(
        LocalDate.parse(start), LocalDate.parse(end),
        PeriodValues(p, p, p, p, p, p), PeriodValues(null, null, null, null, null, null), PeriodValues(null, null, null, null, null, null)
    )

    private val overlapping = StudyHistory(
        cups, StudyHistoryStatus.AVAILABLE, null,
        listOf(row("2025-01-01", "2025-03-01", 123.4), row("2025-01-15", "2025-02-15", 56.7))
    )

    @Test
    fun `logs one line with job, cups, counts, n8n link and details, but no consumption values`() {
        val lines = capture { log ->
            HistoryOverlapLogger("https://n8n.example.com/", log)
                .report("job-1", "req-1", cups, overlapping, N8nRunReference("170", "wf-9"))
        }
        val line = lines.single()
        listOf(
            "History overlaps found", "jobId=job-1", "requestId=req-1", "cups=$cups", "overlaps=1", "blocking=1",
            "n8nExecutionId=170", "n8nRun=https://n8n.example.com/workflow/wf-9/executions/170",
            "ACTIVE rows 1 [2025-01-01..2025-03-01) x 2 [2025-01-15..2025-02-15): 31d"
        ).forEach { assertTrue(line.contains(it), "missing '$it' in: $line") }
        assertFalse(line.contains("123.4") || line.contains("56.7"), "values leaked: $line")
    }

    @Test
    fun `nothing is logged without overlaps, and a missing n8n reference is reported as unknown`() {
        val clean = StudyHistory(cups, StudyHistoryStatus.AVAILABLE, null, listOf(row("2025-01-01", "2025-02-01", 1.0)))
        assertTrue(capture { HistoryOverlapLogger("https://n8n.example.com", it).report("j", null, cups, clean, null) }.isEmpty())
        val line = capture { HistoryOverlapLogger(null, it).report("j", null, cups, overlapping, null) }.single()
        assertTrue(line.contains("n8nExecutionId=unknown") && line.contains("n8nRun=unknown"), line)
    }

    @Test
    fun `execution URL needs both a public base and a workflow id`() {
        assertEquals(null, N8nRunReference("1", "wf").url(null))
        assertEquals(null, N8nRunReference("1", "wf").url(" "))
        assertEquals(null, N8nRunReference("1", null).url("https://n8n.example.com"))
        assertEquals("https://n8n.example.com/workflow/wf/executions/1", N8nRunReference("1", "wf").url("https://n8n.example.com//"))
    }
}
