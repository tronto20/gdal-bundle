package dev.gdal4k
internal actual fun datasetOutputPath(name: String): String = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"gdal4k-$name").absolutePath
