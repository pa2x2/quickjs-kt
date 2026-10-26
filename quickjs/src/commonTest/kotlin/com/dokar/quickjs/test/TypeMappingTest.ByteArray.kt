package com.dokar.quickjs.test

import com.dokar.quickjs.binding.function
import com.dokar.quickjs.quickJs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals

class UInt8ArrayMapping {
    @Test
    fun byteArrays() = runTest {
        val array = byteArrayOf(0, 1, 10, 127)
        quickJs {
            function("getBuffer") { array }

            assertContentEquals(array, evaluate<ByteArray>("getBuffer()"))
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun uByteArrays() = runTest {
        val array = ubyteArrayOf(0u, 10u, 100u, 255u)
        quickJs {
            function("getBuffer") { array }

            assertContentEquals(array, evaluate<UByteArray>("getBuffer()"))
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    @Test
    fun viewsOnPartOfABuffer() = runTest {
        quickJs {
            var received: List<UByte>? = null
            function("receive") { received = (it[0] as UByteArray).toList() }

            evaluate<Any?>("receive(new Uint8Array([1, 2, 3, 4, 5]).subarray(1, 3))")
            assertContentEquals(listOf<UByte>(2u, 3u), received)
            assertContentEquals(
                byteArrayOf(4, 5),
                evaluate<ByteArray>("new Int8Array([1, 2, 3, 4, 5]).subarray(3)"),
            )
        }
    }
}
