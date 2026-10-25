package com.dokar.quickjs.test

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsInterruptedException
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EvaluationInterruptTest {
    @Test
    fun withTimeoutInterruptsInfiniteLoop() = runTest {
        withContext(Dispatchers.Default) {
            quickJs {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(500) { evaluate<Unit>("while(true){}", "test.js") }
                }
                assertEquals(2, evaluate<Int>("1 + 1"))
            }
        }
    }

    @Test
    fun evaluationTimeoutInterruptsInfiniteLoop() = runTest {
        withContext(Dispatchers.Default) {
            quickJs {
                evaluationTimeoutMillis = 500
                // Fast scripts still complete
                assertEquals(
                    499500,
                    evaluate<Int>("let s = 0; for (let i = 0; i < 1000; i++) s += i; s")
                )
                assertFailsWith<QuickJsInterruptedException> {
                    evaluate<Unit>("while(true){}", "test.js")
                }
                assertEquals(2, evaluate<Int>("1 + 1"))
            }
        }
    }

    @Test
    fun cancellingCoroutineInterruptsInfiniteLoop() = runTest {
        withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            try {
                val started = CompletableDeferred<Unit>()
                quickJs.function("started") { started.complete(Unit) }
                val evalJob = launch(Dispatchers.Default) {
                    quickJs.evaluate<Unit>("started(); while(true){}", "test.js")
                }
                started.await()
                evalJob.cancel()
                // Fails if cancellation didn't stop the native evaluation
                withTimeout(5_000) { evalJob.join() }
                assertTrue(evalJob.isCancelled)
            } finally {
                quickJs.close()
            }
        }
    }

    @Test
    fun interruptEvaluationStopsInfiniteLoop() = runTest {
        withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            try {
                val started = CompletableDeferred<Unit>()
                quickJs.function("started") { started.complete(Unit) }
                val evalJob = launch(Dispatchers.Default) {
                    assertFailsWith<QuickJsInterruptedException> {
                        quickJs.evaluate<Unit>("started(); while(true){}", "test.js")
                    }
                }
                started.await()
                quickJs.interruptEvaluation()
                withTimeout(5_000) { evalJob.join() }
                assertEquals(2, quickJs.evaluate<Int>("1 + 1"))
            } finally {
                quickJs.close()
            }
        }
    }

    @Test
    fun evaluationTimeoutStopsJobsThatQueueTheNextJob() = runTest {
        withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            quickJs.evaluationTimeoutMillis = 500
            assertStopsAndCloses(quickJs) {
                assertFailsWith<QuickJsInterruptedException> {
                    quickJs.evaluate<Unit>(SELF_QUEUING_SPIN, "test.js")
                }
            }
        }
    }

    @Test
    fun interruptEvaluationStopsJobsThatQueueTheNextJob() = runTest {
        withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            val started = CompletableDeferred<Unit>()
            quickJs.function("started") { started.complete(Unit) }
            assertStopsAndCloses(
                quickJs,
                afterLaunch = {
                    started.await()
                    quickJs.interruptEvaluation()
                },
            ) {
                assertFailsWith<QuickJsInterruptedException> {
                    quickJs.evaluate<Unit>("started();\n$SELF_QUEUING_SPIN", "test.js")
                }
            }
        }
    }

    @Test
    fun cancellationStopsJobsThatQueueTheNextJob() = runTest {
        withContext(Dispatchers.Default) {
            val quickJs = QuickJs.create(Dispatchers.Default)
            assertStopsAndCloses(quickJs) {
                assertFailsWith<TimeoutCancellationException> {
                    withTimeout(500) { quickJs.evaluate<Unit>(SELF_QUEUING_SPIN, "test.js") }
                }
            }
        }
    }

    /**
     * Runs [evaluation] outside the test's scope, so a hung evaluation fails the
     * test instead of blocking it, then checks that close() returns as well.
     */
    private suspend fun assertStopsAndCloses(
        quickJs: QuickJs,
        afterLaunch: suspend () -> Unit = {},
        evaluation: suspend () -> Unit,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val evaluated = scope.async { evaluation() }
        afterLaunch()
        withTimeout(5_000) { evaluated.await() }
        val closeJob = scope.launch { quickJs.close() }
        withTimeout(5_000) { closeJob.join() }
    }

    private companion object {
        // dokar3/quickjs-kt#240: every job queues the next one before it spins
        const val SELF_QUEUING_SPIN =
            "function f() { Promise.resolve().then(f); while (true) {} }\nf();"
    }
}
