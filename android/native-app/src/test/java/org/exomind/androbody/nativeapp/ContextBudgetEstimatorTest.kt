package org.exomind.androbody.nativeapp

import org.exomind.androbody.nativeapp.model.ContextBudgetEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetEstimatorTest {
    @Test fun reportsExactUtf8BytesAndOnlyARoughTokenRange() {
        val serialized = "{\"content\":\"你好 assistant\"}"
        val prepared = ContextBudgetEstimator.preparedRequest(serialized)
        assertEquals(serialized.toByteArray(Charsets.UTF_8).size, prepared.serializedBytes)
        assertTrue(prepared.tokenEstimate.minimum >= 0)
        assertTrue(prepared.tokenEstimate.maximum >= prepared.tokenEstimate.minimum)
    }

    @Test fun estimatesAsciiAndUnicodeWithBroadOrderedRanges() {
        assertEquals(1L, ContextBudgetEstimator.estimate("abcd").minimum)
        assertEquals(2L, ContextBudgetEstimator.estimate("abcd").maximum)
        val chinese = ContextBudgetEstimator.estimate("你好")
        assertEquals(2L, chinese.minimum)
        assertEquals(4L, chinese.maximum)
        assertEquals(0L, ContextBudgetEstimator.estimate(" \n\t").maximum)
    }
}
