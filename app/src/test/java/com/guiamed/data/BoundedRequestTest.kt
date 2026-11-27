package com.guiamed.data

import com.guiamed.ocr.ReadBudget
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BoundedRequestTest {
    @Test fun failedRequestHasExactlyOneAttempt() = runBlocking {
        var calls = 0
        assertTrue(boundedRequest(ReadBudget(5_000)) { calls++; throw IOException("offline") }.isFailure)
        assertEquals(1, calls)
    }
    @Test fun insufficientRemainingTimeSkipsHttp() = runBlocking {
        var calls = 0
        assertTrue(boundedRequest(ReadBudget(ReadBudget.MIN_HTTP_MILLIS - 1)) { calls++; "response" }.isFailure)
        assertEquals(0, calls)
    }
    @Test fun parentCancellationReachesTheRequest() = runBlocking {
        var released = false
        assertNull(withTimeoutOrNull(100) {
            boundedRequest(ReadBudget(5_000)) {
                try { awaitCancellation() } finally { released = true }
            }
        })
        assertTrue(released)
    }
    @Test fun okhttpHasOneBoundedCallWithoutConnectionRetries() {
        val client = GuiaMedApi.httpClient()
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(ReadBudget.HTTP_MILLIS.toInt(), client.callTimeoutMillis)
        assertTrue(client.connectTimeoutMillis < client.callTimeoutMillis)
    }
}
