package app.georgiamap

import org.maplibre.android.geometry.LatLngBounds
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Properties
import java.util.zip.ZipInputStream

data class MapBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    init {
        require(south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite())
        require(south in -90.0..90.0 && north in -90.0..90.0 && south < north)
        require(west in -180.0..180.0 && east in -180.0..180.0 && west < east)
    }

    fun contains(lat: Double, lon: Double) =
        lat in south..north && lon in west..east

    fun toLatLngBounds() = LatLngBounds.from(north, east, south, west)
}

/** Asset contract for one offline region. Optional files may be absent without disabling the base map. */
data class MapPackage(
    val id: String,
    val title: String,
    val countryCode: String,
    val bounds: MapBounds,
    val tilesAsset: String,
    val placesAsset: String?,
    val reliefAsset: String?,
    val contoursAsset: String?,
    val routingAsset: String?,
    /** Null for the built-in Georgia package; otherwise a child of filesDir/map-packages. */
    val directory: String? = null
) {
    val assetFiles: List<String> get() =
        listOfNotNull(tilesAsset, placesAsset, reliefAsset, contoursAsset, routingAsset).distinct()

    fun file(filesRoot: File, name: String): File {
        require(name in assetFiles) { "Asset $name is not part of package $id" }
        val base = directory?.let { File(File(filesRoot, "map-packages"), it) } ?: filesRoot
        return File(base, name)
    }
}

object MapPackages {
    const val DEFAULT_ID = "georgia"

    val GEORGIA = MapPackage(
        id = DEFAULT_ID,
        title = "Грузия",
        countryCode = "GE",
        bounds = MapBounds(south = 41.0, west = 40.0, north = 43.65, east = 46.8),
        tilesAsset = "georgia.mbtiles",
        placesAsset = "places.db",
        reliefAsset = "relief.mbtiles",
        contoursAsset = "contours.mbtiles",
        routingAsset = "routing.bin"
    )

    val available: List<MapPackage> = listOf(GEORGIA)

    fun installed(filesRoot: File): List<MapPackage> {
        val root = File(filesRoot, "map-packages")
        return root.listFiles()?.asSequence()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.mapNotNull { dir -> runCatching { readManifest(File(dir, MapPackageInstaller.MANIFEST), dir.name) }.getOrNull() }
            ?.sortedBy { it.title.lowercase() }
            ?.toList() ?: emptyList()
    }

    fun all(filesRoot: File): List<MapPackage> = listOf(GEORGIA) + installed(filesRoot)

    fun resolve(id: String): MapPackage = if (id == DEFAULT_ID) GEORGIA else GEORGIA

    fun resolve(id: String, filesRoot: File): MapPackage =
        if (id == DEFAULT_ID) GEORGIA else installed(filesRoot).firstOrNull { it.id == id } ?: GEORGIA

    internal fun readManifest(file: File, directory: String): MapPackage {
        val properties = Properties()
        InputStreamReader(FileInputStream(file), Charsets.UTF_8).use { properties.load(it) }
        val id = properties.getProperty("id")?.trim() ?: error("Missing package id")
        require(id == directory && id.matches(Regex("[a-z0-9][a-z0-9_-]{1,31}")) && id != DEFAULT_ID) {
            "Invalid package id"
        }
        val title = properties.getProperty("title")?.trim()?.takeIf {
            it.isNotEmpty() && it.length <= 80 && !it.contains('\\n') && !it.contains('\\r') && !it.contains('=')
        } ?: error("Missing or invalid package title")
        val country = properties.getProperty("countryCode")?.trim() ?: error("Missing country code")
        require(country.matches(Regex("[A-Z]{2}"))) { "Invalid country code" }
        fun number(key: String) = properties.getProperty(key)?.trim()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() } ?: error("Invalid $key")
        fun optionalAsset(key: String, expected: String): String? =
            properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }?.also {
                require(it == expected) { "Invalid asset name for $key" }
            }
        val tiles = properties.getProperty("tiles")?.trim() ?: error("Missing tiles asset")
        require(tiles == "tiles.mbtiles") { "Invalid tiles asset name" }
        return MapPackage(
            id, title, country,
            MapBounds(number("south"), number("west"), number("north"), number("east")),
            tiles,
            optionalAsset("places", "places.db"),
            optionalAsset("relief", "relief.mbtiles"),
            optionalAsset("contours", "contours.mbtiles"),
            optionalAsset("routing", "routing.bin"),
            directory
        )
    }
}

/** Installs a .gmap ZIP without allowing path traversal or unbounded extraction. */
object MapPackageInstaller {
    const val MANIFEST = "package.properties"
    private const val MAX_MANIFEST_BYTES = 32 * 1024
    private const val MAX_TOTAL_BYTES = 4L * 1024L * 1024L * 1024L
    private val allowedFiles = setOf(MANIFEST, "tiles.mbtiles", "places.db", "relief.mbtiles", "contours.mbtiles", "routing.bin")
    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    fun install(input: InputStream, filesRoot: File): MapPackage {
        val packagesRoot = File(filesRoot, "map-packages")
        require(packagesRoot.exists() || packagesRoot.mkdirs()) { "Cannot create package directory" }
        val temp = File(packagesRoot, ".install-${System.nanoTime()}")
        require(temp.mkdirs()) { "Cannot create temporary package directory" }
        try {
            val seen = HashSet<String>()
            var totalBytes = 0L
            var manifestBytes: ByteArray? = null
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory && entry.name in allowedFiles) { "Unsupported package entry: ${entry.name}" }
                    require(seen.add(entry.name)) { "Duplicate package entry: ${entry.name}" }
                    if (entry.name == MANIFEST) {
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = zip.read(buffer)
                            if (n < 0) break
                            require(out.size() + n <= MAX_MANIFEST_BYTES) { "Package manifest is too large" }
                            out.write(buffer, 0, n)
                        }
                        manifestBytes = out.toByteArray()
                    } else {
                        FileOutputStream(File(temp, entry.name)).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                totalBytes += n
                                require(totalBytes <= MAX_TOTAL_BYTES) { "Map package exceeds the 4 GiB limit" }
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val bytes = manifestBytes ?: error("Package manifest is missing")
            val manifest = File(temp, MANIFEST)
            manifest.writeBytes(bytes)
            val pack = MapPackages.readManifest(manifest, manifestId(bytes))
            val expected = pack.assetFiles.toSet()
            require(seen - setOf(MANIFEST) == expected) { "Package assets do not match its manifest" }
            validateAssets(pack, temp)
            val destination = File(packagesRoot, pack.id)
            require(!destination.exists()) { "A package with id ${pack.id} is already installed" }
            require(temp.renameTo(destination)) { "Could not finalize map package installation" }
            return pack.copy(directory = pack.id)
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
    }

    fun validateInstalled(pack: MapPackage, filesRoot: File): Boolean {
        if (pack.directory == null) return true
        return runCatching {
            validateAssets(pack, File(File(filesRoot, "map-packages"), pack.directory))
            true
        }.getOrDefault(false)
    }

    private fun manifestId(bytes: ByteArray): String {
        val p = Properties()
        InputStreamReader(ByteArrayInputStream(bytes), Charsets.UTF_8).use { p.load(it) }
        return p.getProperty("id")?.trim() ?: error("Missing package id")
    }

    private fun validateAssets(pack: MapPackage, directory: File) {
        for (name in listOfNotNull(pack.tilesAsset, pack.placesAsset, pack.reliefAsset, pack.contoursAsset)) {
            val file = File(directory, name)
            require(file.isFile && hasHeader(file, sqliteHeader)) { "Invalid or missing SQLite asset: $name" }
        }
        pack.routingAsset?.let { name ->
            require(hasHeader(File(directory, name), "GRT1".toByteArray(Charsets.US_ASCII))) {
                "Invalid or missing routing asset: $name"
            }
        }
    }

    private fun hasHeader(file: File, expected: ByteArray): Boolean {
        if (!file.isFile || file.length() < expected.size) return false
        val actual = ByteArray(expected.size)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < actual.size) {
                val n = input.read(actual, offset, actual.size - offset)
                if (n < 0) return false
                offset += n
            }
        }
        return actual.contentEquals(expected)
    }
}
