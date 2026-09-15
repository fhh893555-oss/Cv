package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.domain.model.TrimRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Video Editor", appName)
  }

  @Test
  fun `trim range validation logic`() {
    val validRange = TrimRange(startMs = 1000L, endMs = 5000L)
    assertTrue(validRange.isValid)
    assertEquals(4000L, validRange.durationMs)
    assertTrue(validRange.validate(totalDurationMs = 10000L).isSuccess)

    val invalidStartGreaterThanEnd = TrimRange(startMs = 6000L, endMs = 2000L)
    assertFalse(invalidStartGreaterThanEnd.isValid)
    assertTrue(invalidStartGreaterThanEnd.validate(totalDurationMs = 10000L).isFailure)

    val tooShortRange = TrimRange(startMs = 1000L, endMs = 1200L)
    assertTrue(tooShortRange.validate(totalDurationMs = 10000L, minDurationMs = 500L).isFailure)
  }
}

