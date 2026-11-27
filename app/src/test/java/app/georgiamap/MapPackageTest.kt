package app.georgiamap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    @Test
    fun installsAndResolvesAnAdditionalCountryPackage() {
        val root = Files.createTempDirectory("map-packages").toFile()
        try {
            val bytes = archive(
                """id=armenia
title=Армения
countryCode=AM
south=38.8
west=43.4
north=41.3
east=46.7
tiles=tiles.mbtiles
places=places.db
routing=routing.bin
""",
                mapOf(
                    "tiles.mbtiles" to sqliteHeader(),
                    "places.db" to sqliteHeader(),
                    "routing.bin" to "GRT1-data".toByteArray()
                )
            )
            val installed = MapPackageInstaller.install(ByteArrayInputStream(bytes), root)
            assertEquals("armenia", installed.id)
            assertEquals("Армения", installed.title)
            assertTrue(installed.file(root, "tiles.mbtiles").isFile)
            assertEquals("armenia", MapPackages.resolve("armenia", root).id)
            assertEquals(2, MapPackages.all(root).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlipAndInvalidAssetHeaders() {
        val root = Files.createTempDirectory("map-packages-invalid").toFile()
        try {
            val zipSlip = archive("id=armenia\n", mapOf("../escape.txt" to byteArrayOf(1)))
            try {
                MapPackageInstaller.install(ByteArrayInputStream(zipSlip), root)
                throw AssertionError("path traversal entry should be rejected")
            } catch (_: IllegalArgumentException) { }

            val invalid = archive(
                """id=armenia
title=Армения
countryCode=AM
south=38.8
west=43.4
north=41.3
east=46.7
tiles=tiles.mbtiles
""",
                mapOf("tiles.mbtiles" to "not sqlite".toByteArray())
            )
            try {
                MapPackageInstaller.install(ByteArrayInputStream(invalid), root)
                throw AssertionError("invalid SQLite asset should be rejected")
            } catch (_: IllegalArgumentException) { }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun archive(manifest: String, assets: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MapPackageInstaller.MANIFEST))
            zip.write(manifest.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for ((name, bytes) in assets) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sqliteHeader() = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(32)
}
