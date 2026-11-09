package com.kernoeb.fff3.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the startup path for now. Add the colour drag and effect switching here once the
 * real control screen exists. Run with `./gradlew :app:generateBaselineProfile` and a phone attached.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(
        packageName = "com.kernoeb.fff3",
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
