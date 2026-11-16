"""Offline release-note validation and mocked publication checks."""
import contextlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_notes


class ReleaseNotesTest(unittest.TestCase):
    def test_version_threshold_and_invalid_tags(self):
        for tag in ("v0.1.0", "v0.2.0", "v0.2.99"):
            self.assertFalse(release_notes.uses_versioned_notes(tag))
        for tag in ("v0.3.0", "v0.3.1", "v0.10.0", "v1.0.0", "v0.3.0-rc.1"):
            self.assertTrue(release_notes.uses_versioned_notes(tag))
        for tag in ("0.3.0", "v0.3", "v00.3.0", "v0.3.0\n", "vnext", "v0.3.0;echo x"):
            with self.assertRaises(ValueError):
                release_notes.uses_versioned_notes(tag)

    def test_exact_section_and_markdown_preserved(self):
        source = "# Notes\r\n\r\n## v0.3.0\r\n\r\n### Added\r\n\r\n- **New** feature.\r\n\r\n## v0.2.0\r\n\r\n- Old.\r\n"
        self.assertEqual("### Added\r\n\r\n- **New** feature.\n", release_notes.extract_notes(source, "v0.3.0"))
        self.assertEqual("- Old.\n", release_notes.extract_notes(source, "v0.2.0"))

    def test_fences_do_not_create_version_boundaries(self):
        for fence in ("```", "~~~~"):
            source = f"## v0.3.0\n\n- New.\n{fence}text\n## v0.2.0\n{fence}\n\n## v0.1.0\n- Old.\n"
            notes = release_notes.extract_notes(source, "v0.3.0")
            self.assertIn("## v0.2.0", notes)
            self.assertNotIn("- Old.", notes)

    def test_missing_duplicate_and_empty_sections_fail(self):
        for source in ("## v0.30.0\n- Wrong", "## v0.3.0\n- One\n## v0.3.0\n- Two", "## v0.3.0\n\n## v0.2.0\n- Old", "## v0.3.0\n### Added\n<!-- placeholder -->"):
            with self.assertRaises(ValueError):
                release_notes.extract_notes(source, "v0.3.0")

    def test_legacy_does_not_require_poms_or_notes(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertIsNone(release_notes.prepare("v0.2.0", Path(folder)))

    def test_both_module_versions_must_match(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            for module in ("simple-tunnel", "nio-tunnel"):
                (root / module).mkdir()
                (root / module / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.3.0</version></project>', encoding="utf-8")
            (root / "RELEASE_NOTES.md").write_text("## v0.3.0\n\n- New.\n\n## v0.2.0\n- Old.\n", encoding="utf-8")
            self.assertEqual("- New.\n", release_notes.prepare("v0.3.0", root))
            (root / "nio-tunnel" / "pom.xml").write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><version>0.2.0</version></project>', encoding="utf-8")
            with self.assertRaises(ValueError):
                release_notes.prepare("v0.3.0", root)

    def test_cli_outputs_note_flag_without_blocking_legacy_publication(self):
        for tag, notes, expected in (("v0.2.0", None, "false"), ("v0.3.0", "- New.\n", "true")):
            with self.subTest(tag=tag), tempfile.TemporaryDirectory() as folder:
                output, github = Path(folder) / "notes.md", Path(folder) / "github-output"
                with patch.object(release_notes, "prepare", return_value=notes), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, release_notes.main(["--tag", tag, "--output", str(output), "--github-output", str(github)]))
                self.assertEqual(f"notes_enabled={expected}\n", github.read_text())
                self.assertEqual(notes is not None, output.exists())
                if notes is not None:
                    self.assertEqual(notes, output.read_text())

    def test_failed_validation_does_not_write_publish_flag(self):
        with tempfile.TemporaryDirectory() as folder:
            output, github = Path(folder) / "notes.md", Path(folder) / "github-output"
            with patch.object(release_notes, "prepare", side_effect=ValueError("missing notes")), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(1, release_notes.main(["--tag", "v0.3.0", "--output", str(output), "--github-output", str(github)]))
            self.assertFalse(output.exists())
            self.assertFalse(github.exists())


@unittest.skipUnless(sys.platform == "linux", "Bash workflow execution is validated on Linux")
class PublishWorkflowTest(unittest.TestCase):
    def test_all_publication_paths_with_mock_github_cli(self):
        workflow = (release_notes.ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        publish = workflow.split("      - name: Publish ZIP files to GitHub Release", 1)[1]
        shell = textwrap.dedent(publish.split("        run: |\n", 1)[1])
        for notes_enabled in ("true", "false"):
            for exists in ("true", "false"):
                with self.subTest(notes=notes_enabled, exists=exists), tempfile.TemporaryDirectory() as folder:
                    root = Path(folder)
                    mock = root / "gh"
                    mock.write_text("#!/usr/bin/env python3\nimport json,os,sys\nwith open(os.environ['CALLS'],'a') as f: f.write(json.dumps(sys.argv[1:])+'\\n')\nif sys.argv[1:3]==['release','view']:sys.exit(0 if os.environ['EXISTS']=='true' else 1)\n", encoding="utf-8")
                    mock.chmod(0o755)
                    script = root / "publish.sh"
                    script.write_text(shell, encoding="utf-8")
                    calls = root / "calls"
                    env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ["PATH"], CALLS=str(calls), EXISTS=exists,
                               NOTES_ENABLED=notes_enabled, RELEASE_TAG="v0.3.0" if notes_enabled == "true" else "v0.2.0", RUNNER_TEMP=str(root))
                    subprocess.run(["bash", str(script)], env=env, check=True)
                    commands = [json.loads(line) for line in calls.read_text().splitlines()]
                    actions = [command[1] for command in commands]
                    if exists == "true":
                        self.assertEqual(["view", "edit", "upload"] if notes_enabled == "true" else ["view", "upload"], actions)
                    else:
                        self.assertEqual(["view", "create"], actions)
                        self.assertEqual(notes_enabled == "true", "--notes-file" in commands[-1])
                        self.assertEqual(notes_enabled == "false", "--generate-notes" in commands[-1])
                    for command in commands:
                        self.assertEqual(env["RELEASE_TAG"], command[2])


if __name__ == "__main__":
    unittest.main()
