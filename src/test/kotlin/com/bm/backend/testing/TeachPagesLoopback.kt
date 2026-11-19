package com.bm.backend.testing

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Use the component's test config, no Vite .env files, or inherited credential/loader/proxy settings. */
internal fun runTeachPagesLoopback(testName: String, fixtureEnvironment: Map<String, String>, testFile: String = "clarifications") =
    runPagesLoopback(testName, fixtureEnvironment, "src/app/api/teach-me/$testFile/route.test.ts", qa = true)

internal fun runProdAnalogPagesLoopback(fixtureEnvironment: Map<String, String>) {
    check(fixtureEnvironment.keys == setOf("BM_PROD_ANALOG_TEST_PORT", "BM_PROD_ANALOG_TEST_PIN", "BM_PROD_ANALOG_TEST_ACTOR",
        "BM_PROD_ANALOG_TEST_OTHER", "BM_PROD_ANALOG_TEST_INPUT", "BM_PROD_ANALOG_TEST_PHASE", "BM_PROD_ANALOG_TEST_EXPECT"))
    runPagesLoopback("chains exact released import Pages and Ktor over loopback", fixtureEnvironment + ("BM_PROD_ANALOG_JOINT" to "1"),
        "src/app/api/prod-analog/proposal-import/route.test.ts", qa = false)
}

private fun runPagesLoopback(testName: String, fixtureEnvironment: Map<String, String>, testFile: String, qa: Boolean) {
    val builder = ProcessBuilder("node", "--input-type=module", "-e", """
        import { startVitest } from 'vitest/node';
        const run = await startVitest('test', [process.argv[2]],
          { run: true, config: './vitest.config.ts', testNamePattern: process.argv[1] }, { envFile: false });
        const tests = run?.state.getFiles().flatMap(file => file.tasks) ?? [];
        if (tests.filter(test => test.result?.state === 'pass').length !== 1) process.exitCode = 1;
    """.trimIndent(), testName, testFile)
        .directory(File(checkNotNull(System.getenv("BM_TEACH_CLARIFY_TEST_WEB"))))
        .inheritIO()
    builder.environment().apply {
        val nodePath = requireNotNull(get("PATH"))
        clear()
        put("PATH", nodePath)
        if (qa) put("NEXT_PUBLIC_APP_ENV", "qa")
        putAll(fixtureEnvironment)
    }
    val child = builder.start()
    try {
        assertTrue(child.waitFor(40, TimeUnit.SECONDS), "Node proxy test timed out")
        assertEquals(0, child.exitValue(), "Node proxy test failed")
    } finally {
        if (child.isAlive) {
            child.descendants().use { descendants -> descendants.forEach { it.destroyForcibly() } }
            child.destroyForcibly()
            check(child.waitFor(5, TimeUnit.SECONDS)) { "Node proxy test survived teardown" }
        }
    }
}
