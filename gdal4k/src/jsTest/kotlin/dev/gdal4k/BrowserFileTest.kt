package dev.gdal4k
import dev.gdal4k.runtime.*
import kotlinx.coroutines.test.runTest
import org.w3c.files.File
import kotlin.test.*
private fun fixtureFile(): File = js("new File([Uint8Array.from(atob('SUkqAAgAAAAOAAABBAABAAAAAwAAAAEBBAABAAAAAgAAAAIBAwABAAAACAAAAAMBAwABAAAAAQAAAAYBAwABAAAAAQAAABEBBAABAAAAHgEAABUBAwABAAAAAQAAABYBBAABAAAAAgAAABcBBAABAAAABgAAAFMBAwABAAAAAQAAAA6DDAADAAAAtgAAAIKEDAAGAAAAzgAAAK+HAwAQAAAA/gAAAIGkAgACAAAAMAAAAAAAAAAAAAAAAADgPwAAAAAAAOA/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABZQAAAAAAAAElAAAAAAAAAAAABAAEAAAADAAAEAAABAAIAAQQAAAEAAQAACAAAAQDmEAABAgME/w=='), c => c.charCodeAt(0))], 'selected.tif')")
private fun inBrowser(): Boolean = js("typeof window !== 'undefined'")
class BrowserFileTest {
    @Test fun selectedFileUsesOriginalDatasetApi() = runTest {
        if (!inBrowser()) return@runTest
        try {
            val runtime = Gdal4k.runtime()
            runtime.openDataset(fixtureFile()).use { dataset ->
                assertEquals(3, dataset.getRasterXSize())
                assertEquals(2, dataset.getRasterYSize())
                val pixels = ByteArray(6)
                requireNotNull(dataset.getRasterBand(1)).use { band -> assertEquals(0, band.readRaster(pixels)) }
                assertContentEquals(byteArrayOf(0,1,2,3,4,-1), pixels)
            }
            assertFailsWith<IllegalArgumentException> {
                runtime.openDataset(fixtureFile(), DatasetOpenOptions(accessMode = DatasetAccessMode.Update))
            }
        } finally { Gdal4k.close() }
    }
}
