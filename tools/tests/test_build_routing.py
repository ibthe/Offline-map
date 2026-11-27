import os
import sys
import unittest

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))
import build_routing


def flags(tags):
    result = build_routing.way_props({"highway": "residential", **tags})
    return result[0] if result else 0


def bit(value, index):
    return bool(value & (1 << index))


class WayAccessTests(unittest.TestCase):
    def test_motorcar_specific_access_overrides_general_access(self):
        denied = flags({"access": "yes", "motorcar": "no"})
        self.assertFalse(bit(denied, 0))
        self.assertFalse(bit(denied, 3))
        allowed = flags({"access": "no", "motorcar": "yes"})
        self.assertTrue(bit(allowed, 0))
        self.assertTrue(bit(allowed, 3))

    def test_motorcar_oneway_override_is_independent_of_general_oneway(self):
        flags_value = flags({"oneway": "yes", "oneway:motorcar": "no"})
        self.assertTrue(bit(flags_value, 0))
        self.assertTrue(bit(flags_value, 3))
        reverse = flags({"oneway": "no", "oneway:motor_vehicle": "-1"})
        self.assertFalse(bit(reverse, 0))
        self.assertTrue(bit(reverse, 3))

    def test_bicycle_oneway_override_and_contraflow_cycleway(self):
        both = flags({"oneway": "yes", "oneway:bicycle": "no"})
        self.assertTrue(bit(both, 2))
        self.assertTrue(bit(both, 5))
        one_way = flags({"oneway": "no", "oneway:bicycle": "yes"})
        self.assertTrue(bit(one_way, 2))
        self.assertFalse(bit(one_way, 5))
        contraflow = flags({"oneway": "yes", "cycleway": "opposite_lane"})
        self.assertTrue(bit(contraflow, 2))
        self.assertTrue(bit(contraflow, 5))

    def test_foot_oneway_is_separate_and_roundabout_defaults_forward(self):
        foot = flags({"oneway:foot": "yes"})
        self.assertTrue(bit(foot, 1))
        self.assertFalse(bit(foot, 4))
        roundabout = build_routing.way_props({"highway": "residential", "junction": "roundabout"})[0]
        self.assertTrue(bit(roundabout, 0))
        self.assertFalse(bit(roundabout, 3))
        explicit_two_way = build_routing.way_props({
            "highway": "residential", "junction": "roundabout", "oneway": "no"
        })[0]
        self.assertTrue(bit(explicit_two_way, 0))
        self.assertTrue(bit(explicit_two_way, 3))

    def test_toll_road_tag_is_encoded(self):
        toll = flags({"toll": "yes"})
        self.assertTrue(bit(toll, 26))
        free = flags({"toll": "no"})
        self.assertFalse(bit(free, 26))

    def test_tracktype_and_smoothness_are_encoded_for_terrain_avoidance(self):
        rough = flags({"surface": "gravel", "tracktype": "grade5", "smoothness": "bad"})
        self.assertTrue(bit(rough, 19))
        self.assertEqual(5, (rough >> 20) & 7)
        self.assertEqual(4, (rough >> 23) & 7)
        smooth = flags({"surface": "paved", "tracktype": "grade1", "smoothness": "good"})
        self.assertFalse(bit(smooth, 19))
        self.assertEqual(1, (smooth >> 20) & 7)
        self.assertEqual(2, (smooth >> 23) & 7)

    def test_restricted_access_values_are_not_treated_as_public(self):
        for value in ("no", "private", "customers", "delivery", "destination", "agricultural", "forestry"):
            with self.subTest(value=value):
                flags_value = flags({"access": value})
                self.assertFalse(bit(flags_value, 0))
                self.assertFalse(bit(flags_value, 1))
                self.assertFalse(bit(flags_value, 2))


if __name__ == "__main__":
    unittest.main()
