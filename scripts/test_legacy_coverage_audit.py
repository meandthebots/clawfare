"""Hermetic tests for the unfiltered Kover audit parser and gate checks."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from audit_legacy_coverage import (
    Counts,
    audit,
    excluded_legacy_classes,
    parse_xml_report,
)


def report(classes: list[tuple[str, int, int, int, int]]) -> str:
    details = []
    for name, line_missed, line_covered, branch_missed, branch_covered in classes:
        details.append(
            f'<class name="{name.replace(".", "/")}" sourcefilename="Commands.kt">'
            f'<counter type="LINE" missed="{line_missed}" covered="{line_covered}"/>'
            f'<counter type="BRANCH" missed="{branch_missed}" covered="{branch_covered}"/>'
            '</class>'
        )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<report name="clawfare"><package name="com/clawfare/cli">'
        + "".join(details) + "</package></report>"
    )


class LegacyCoverageAuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)

    def parse(self, data: str):
        path = self.base / "report.xml"
        path.write_text(data, encoding="utf-8")
        return parse_xml_report(path)

    def test_line_and_branch_counters_are_independent(self) -> None:
        coverage = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 7, 3, 5, 1),
            ("com.clawfare.cli.FlightListCommand", 0, 10, 0, 0),
        ]))
        self.assertEqual(coverage.classes["com.clawfare.cli.FlightValidateCommand"].lines.percentage, "30.0%")
        self.assertEqual(coverage.classes["com.clawfare.cli.FlightValidateCommand"].branches.percentage, "16.7%")
        self.assertEqual(coverage.classes["com.clawfare.cli.FlightListCommand"].branches.percentage, "n/a")
        self.assertEqual((Counts(1, 2) + Counts(3, 4)).total, 10)

    def test_unfiltered_audit_measures_legacy_without_weakening_gate(self) -> None:
        exclusions = ("com.clawfare.cli.FlightValidateCommand",)
        unfiltered = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 7, 3, 5, 1),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
        ]))
        filtered = self.parse(report([("com.clawfare.cli.SafeCommand", 0, 10, 0, 0)]))
        metrics, markdown = audit(unfiltered, filtered, exclusions)
        self.assertEqual(metrics["unfiltered"]["lines"]["percent"], 65.0)
        self.assertEqual(metrics["enforced_gate"]["lines"]["percent"], 100.0)
        self.assertEqual(metrics["excluded_legacy"]["lines"]["percent"], 30.0)
        self.assertIn("7", markdown)
        self.assertIn("16.7%", markdown)
        self.assertIn("**80% gate remains unchanged**", markdown)

    def test_audit_fails_if_gate_accidentally_includes_excluded_class(self) -> None:
        full = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 4, 6, 0, 0),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
        ]))
        with self.assertRaisesRegex(ValueError, "unexpectedly includes"):
            audit(full, full, ("com.clawfare.cli.FlightValidateCommand",))

    def test_audit_fails_when_unfiltered_report_omits_all_exclusions(self) -> None:
        coverage = self.parse(report([("com.clawfare.cli.SafeCommand", 0, 10, 0, 0)]))
        with self.assertRaisesRegex(ValueError, "none of the legacy-excluded"):
            audit(coverage, coverage, ("com.clawfare.cli.FlightValidateCommand",))

    def test_missing_exclusion_is_explicit_and_not_a_silent_pass(self) -> None:
        full = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 4, 6, 0, 0),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
        ]))
        gate = self.parse(report([("com.clawfare.cli.SafeCommand", 0, 10, 0, 0)]))
        metrics, markdown = audit(full, gate, (
            "com.clawfare.cli.FlightValidateCommand",
            "com.clawfare.cli.Output",
        ))
        self.assertEqual(metrics["missing_legacy_classes"], ["com.clawfare.cli.Output"])
        self.assertIn("absent", markdown)

    def test_duplicate_classes_and_invalid_counters_fail_closed(self) -> None:
        duplicated = self.parse(report([
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
        ]))
        self.assertEqual(len(duplicated.classes), 1)
        with self.assertRaisesRegex(ValueError, "Duplicate coverage class"):
            self.parse(report([
                ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
                ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
            ]))
        with self.assertRaisesRegex(ValueError, "Negative LINE"):
            self.parse(report([("com.clawfare.cli.SafeCommand", -1, 10, 0, 0)]))

    def test_reads_configured_exemption_names_instead_of_duplicating_list(self) -> None:
        path = self.base / "build.gradle.kts"
        path.write_text(
            'kover {\n reports { total { filters { excludes {'
            ' classes("com.clawfare.cli.Output", "com.clawfare.cli.InvConfigCommand")'
            ' } } verify { rule { minBound(80) } } } '
            'variant("jvm") { filters { } } } }\napplication { }',
            encoding="utf-8",
        )
        self.assertEqual(
            excluded_legacy_classes(path),
            ("com.clawfare.cli.InvConfigCommand", "com.clawfare.cli.Output"),
        )
        path.write_text(path.read_text().replace("minBound(80)", "minBound(30)"))
        with self.assertRaisesRegex(ValueError, "80% gate missing"):
            excluded_legacy_classes(path)


if __name__ == "__main__":
    unittest.main()
