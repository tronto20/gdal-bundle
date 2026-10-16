package dev.gdal4k

import androidx.test.platform.app.InstrumentationRegistry

internal actual suspend fun integrationOptions() =
    GdalAndroid.options(InstrumentationRegistry.getInstrumentation().targetContext)
