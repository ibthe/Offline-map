package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapPackageTest {
    @Test
    fun defaultPackageDefinesAllGeorgiaAssetsAndBounds() {
        val pack = MapPackages.resolve(MapPackages.DEFAULT_ID)
        assertEquals("georgia", pack.id)
        assertEquals("GE", pack.countryCode)
        assertTrue(pack.bounds.contains(41.7151, 44.8271))
        assertFalse(pack.bounds.contains(50.0, 44.0))
        assertTrue(pack.assetFiles.contains(pack.tilesAsset))
        assertEquals(pack.assetFiles.size, pack.assetFiles.distinct().size)
    }

    @Test
    fun unknownPackageFallsBackToDefaultWithoutNullAssets() {
        val pack = MapPackages.resolve("missing-region")
        assertEquals(MapPackages.GEORGIA, pack)
        assertTrue(pack.tilesAsset.isNotBlank())
    }
}
