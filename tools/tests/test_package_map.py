import tempfile
import unittest
import zipfile
from pathlib import Path
from types import SimpleNamespace

from tools.package_map import create_package


class MapPackageBuilderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.tiles = self.root / "tiles.mbtiles"
        self.tiles.write_bytes(b"SQLite format 3\x00" + bytes(32))
        self.routing = self.root / "routing.bin"
        self.routing.write_bytes(b"GRT1" + bytes(32))

    def tearDown(self):
        self.temp.cleanup()

    def args(self, **overrides):
        values = dict(
            id="armenia", title="Армения", country_code="AM",
            bounds=[38.8, 43.4, 41.3, 46.7], tiles=str(self.tiles),
            places=None, relief=None, contours=None, routing=str(self.routing),
            output=str(self.root / "armenia.gmap")
        )
        values.update(overrides)
        return SimpleNamespace(**values)

    def test_creates_archive_with_manifest_and_expected_asset_names(self):
        output = create_package(self.args())
        with zipfile.ZipFile(output) as archive:
            self.assertEqual({"package.properties", "tiles.mbtiles", "routing.bin"}, set(archive.namelist()))
            manifest = archive.read("package.properties").decode("utf-8")
            self.assertIn("id=armenia", manifest)
            self.assertIn("countryCode=AM", manifest)
            self.assertIn("tiles=tiles.mbtiles", manifest)
            self.assertIn("routing=routing.bin", manifest)

    def test_rejects_invalid_bounds_and_ids(self):
        with self.assertRaises(ValueError):
            create_package(self.args(bounds=[41, 44, 40, 45]))
        with self.assertRaises(ValueError):
            create_package(self.args(id="../escape"))
        with self.assertRaises(ValueError):
            create_package(self.args(title="Armenia\\nInjected=value"))

    def test_rejects_invalid_asset_header(self):
        self.tiles.write_bytes(b"not a database")
        with self.assertRaises(ValueError):
            create_package(self.args())


if __name__ == "__main__":
    unittest.main()
