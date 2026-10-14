import json
import re
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PAGES = ROOT / ".github/workflows/pages.yml"
MANIFEST = ROOT / "docs/.vuepress/showcase-video.json"


class ShowcaseVideoTests(unittest.TestCase):
    def assert_media_for_site_line(self, release, release_line):
        if release_line == "1":
            self.assertEqual(release, "showcase-video")
        else:
            expected = re.escape(f"showcase-video-v{release_line}")
            self.assertRegex(release, rf"^(?:showcase-video|{expected}(?:-r[1-9][0-9]*)?)$")

    def select_release(self, manifest):
        workflow = PAGES.read_text()
        marker = 'SHOWCASE_RELEASE="$(node --input-type=module -e \''
        self.assertEqual(workflow.count(marker), 1)
        script = workflow.split(marker, 1)[1].split('\')"', 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "docs/.vuepress/showcase-video.json"
            config.parent.mkdir(parents=True)
            if manifest is not None:
                config.write_text(json.dumps(manifest))
            return subprocess.run(
                ["node", "--input-type=module", "-e", script],
                cwd=directory,
                capture_output=True,
                text=True,
                timeout=10,
            )

    def test_each_site_line_can_select_its_own_media_release(self):
        for release in ["showcase-video", "showcase-video-v2", "showcase-video-v2-r2", "showcase-video-v2-r3", "showcase-video-v2-r4"]:
            with self.subTest(release=release):
                result = self.select_release({"release": release})
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.strip(), release)

    def test_product_tags_and_invalid_media_refs_are_refused(self):
        for release in [
            "v2.0.0",
            "",
            "--latest",
            "showcase-video-v2; echo changed",
            "showcase-video-v2-r0",
            "showcase-video-v2-r02",
            None,
            2,
        ]:
            with self.subTest(release=release):
                result = self.select_release({"release": release})
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Invalid showcase media release", result.stderr)

    def test_missing_configuration_fails_instead_of_selecting_a_stale_video(self):
        result = self.select_release(None)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("showcase-video.json", result.stderr)

    def test_site_line_can_retain_v1_or_select_its_own_media(self):
        for release_line, release in [
            ("1", "showcase-video"),
            ("2", "showcase-video"),
            ("2", "showcase-video-v2"),
            ("2", "showcase-video-v2-r2"),
            ("2", "showcase-video-v2-r3"),
            ("2", "showcase-video-v2-r4"),
        ]:
            with self.subTest(release_line=release_line, release=release):
                self.assert_media_for_site_line(release, release_line)
        for release_line, release in [
            ("1", "showcase-video-v2"),
            ("2", "showcase-video-v1"),
            ("2", "showcase-video-v3"),
            ("2", "showcase-video-v2-r0"),
            ("2", "showcase-video-v2-r02"),
            ("2", "v2.0.0"),
        ]:
            with self.subTest(release_line=release_line, release=release):
                with self.assertRaises(AssertionError):
                    self.assert_media_for_site_line(release, release_line)

    def test_selected_download_keeps_the_same_origin_player_assets(self):
        lines = [
            line.split("#", 1)[0].strip()
            for line in (ROOT / ".github/release-line").read_text().splitlines()
        ]
        release_line, = [line for line in lines if line]
        selection = json.loads(MANIFEST.read_text())
        self.assertEqual(set(selection), {"release"})
        self.assert_media_for_site_line(selection["release"], release_line)
        workflow = PAGES.read_text()
        self.assertIn('gh release download "$SHOWCASE_RELEASE"', workflow)
        self.assertIn("--pattern bootui-showcase.mp4", workflow)
        self.assertIn("--dir docs/.vuepress/public/videos", workflow)
        self.assertIn("bootui-showcase.mp4 bootui-showcase-poster.jpg bootui-showcase.en.vtt", workflow)
        self.assertIn("needs.build.outputs.publish == 'true'", workflow)


if __name__ == "__main__":
    unittest.main()
