package com.bm.backend.testing

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Use the component's test config, no Vite .env files, or inherited credential/loader/proxy settings. */
internal fun runTeachPagesLoopback(testName: String, fixtureEnvironment: Map<String, String>, testFile: String = "clarifications") {
    val builder = ProcessBuilder("node", "--input-type=module", "-e", """
        import { startVitest } from 'vitest/node';
        const run = await startVitest('test', ['src/app/api/teach-me/' + process.argv[2] + '/route.test.ts'],
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
        put("NEXT_PUBLIC_APP_ENV", "qa")
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
        }
    }
}
