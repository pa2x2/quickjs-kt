package com.dokar.quickjs.test

import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AsyncErrorHandlingTest {
    @Test
    fun uncaughtWithAwait() = runTest {
        quickJs {
            asyncFunction("fetch") { error("Error occurred") }

            assertFails { evaluate("await fetch()") }.also {
                assertContains(it.message!!, "Error occurred")
            }
        }
    }

    @Test
    fun uncaughtWithNoAwait() = runTest {
        quickJs {
            asyncFunction("fetch") { error("Error occurred") }

            assertFails { evaluate("fetch()") }.also {
                assertContains(it.message!!, "Error occurred")
            }
        }
    }

    @Test
    fun withTryCatch() = runTest {
        quickJs {
            asyncFunction("fetch") { error("Error occurred") }

            val result = evaluate<String>(
                """
                    let result = null;
                    try {
                        await fetch()
                    } catch(e) {
                        result = "Caught";
                    }
                    result;
                """.trimIndent()
            )
            assertEquals("Caught", result)
        }
    }

    @Test
    fun withPromiseCatchNoAwait() = runTest {
        quickJs {
            asyncFunction("fetch") { error("Error occurred") }

            val result = evaluate<String>("fetch().catch((e) => {}); 'Caught';")
            assertEquals("Caught", result)
        }
    }

    @Test
    fun withPromiseCatch() = runTest {
        quickJs {
            asyncFunction("fetch") { error("Error occurred") }

            val result = evaluate<String>("await fetch().catch((e) => {}); 'Caught';")
            assertEquals("Caught", result)
        }
    }

    @Test
    fun jobCancelingWithUncaughtNoAwait() = runTest {
        quickJs {
            var isDelayed = false
            asyncFunction("fetch") { error("Error occurred") }

            asyncFunction("delay") {
                delay(500)
                isDelayed = true
            }

            assertFails {
                evaluate("delay(); fetch();")
            }.also {
                assertContains(it.message!!, "Error occurred")
            }
            assertFalse(isDelayed)
        }
    }

    @Test
    fun jobCancelingWithUncaughtAndAwait() = runTest {
        quickJs {
            var isDelayed = false
            asyncFunction("fetch") { error("Error occurred") }

            asyncFunction("delay") {
                delay(500)
                isDelayed = true
            }

            assertFails {
                evaluate("delay(); await fetch();")
            }.also {
                assertContains(it.message!!, "Error occurred")
            }
            assertFalse(isDelayed)
        }
    }

    @Test
    fun jobCancelingWithUncaughtAndPromiseAll() = runTest {
        quickJs {
            var isDelayed = false
            asyncFunction("fetch") { error("Error occurred") }

            asyncFunction("delay") {
                delay(500)
                isDelayed = true
            }

            assertFails {
                evaluate("await Promise.all([delay(), fetch()]);")
            }.also {
                assertContains(it.message!!, "Error occurred")
            }
            assertFalse(isDelayed)
        }
    }

    @Test
    fun jobExecutingWithCaught() = runTest {
        quickJs {
            var isDelayed = false
            asyncFunction("fetch") { error("Error occurred") }

            asyncFunction("delay") {
                delay(500)
                isDelayed = true
            }

            evaluate<Any?>("delay(); fetch().catch((e) => {});")
            assertTrue(isDelayed)
        }
    }

    @Test
    fun typeMappingErrorInAsyncFunc() = runTest {
        quickJs {
            var delayed = false

            asyncFunction("fetch") { Any() }

            asyncFunction("delay") {
                delay(100)
                delayed = true
            }

            assertFails {
                evaluate("fetch(); delay();")
            }.also {
                assertContains(it.message!!, "Cannot find the kotlin type of object")
            }
            assertFalse(delayed)
        }
    }

    @Test
    fun failedEvaluationDoesNotFailTheNextOne() = runTest {
        withContext(Dispatchers.Default) {
            quickJs {
                asyncFunction("delay") { delay((it[0] as Number).toLong()) }
                // The stray rejection fails the evaluation while 'delay(50)' is still
                // pending; cancelling that job must not reject into the next evaluation.
                repeat(20) {
                    assertFailsWith<QuickJsException> {
                        evaluate<Any?>(
                            "delay(0).then(() => { Promise.reject(new Error('stray')) }); await delay(50); 1"
                        )
                    }
                    assertEquals(2, evaluate<Int>("1 + 1"))
                }
            }
        }
    }

    @Test
    fun unhandledRejectionHandlerReportsInsteadOfFailing() = runTest {
        withContext(Dispatchers.Default) {
            quickJs {
                asyncFunction("delay") { delay((it[0] as Number).toLong()) }
                val reported = mutableListOf<Throwable>()
                unhandledRejectionHandler = { reported += it }

                // Reported, and the evaluation still returns its result
                assertEquals(
                    42,
                    evaluate<Int>("Promise.reject(new Error('stray')); await delay(10); 42"),
                )
                // Handled after awaiting a binding: neither reported nor failing
                assertEquals(
                    1,
                    evaluate<Int>(
                        "const late = Promise.reject(new Error('late')); await delay(10); late.catch(() => {}); 1"
                    ),
                )
                // The evaluated code's own error still fails the evaluation
                assertFailsWith<QuickJsException> {
                    evaluate<Any?>("await delay(1); throw new Error('own')")
                }
                assertEquals(1, reported.size)
                assertContains(reported.single().message!!, "stray")
            }
        }
    }
}
