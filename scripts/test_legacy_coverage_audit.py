"""Hermetic tests for the unfiltered Kover audit parser and gate checks."""

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from audit_legacy_coverage import (
    RETIRED_CLI_EXEMPTIONS,
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

    def test_audit_flags_existing_gate_inclusion_without_hiding_coverage(self) -> None:
        full = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 4, 6, 0, 0),
            ("com.clawfare.cli.FlightListCommand", 7, 3, 1, 1),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
        ]))
        gated = self.parse(report([
            ("com.clawfare.cli.FlightListCommand", 7, 3, 1, 1),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 0),
        ]))
        metrics, markdown = audit(
            full, gated,
            ("com.clawfare.cli.FlightValidateCommand", "com.clawfare.cli.FlightListCommand"),
        )
        self.assertEqual(
            metrics["configured_exclusions_present_in_gate"],
            ["com.clawfare.cli.FlightListCommand"],
        )
        self.assertIn("Existing Kover filter discrepancy", markdown)
        self.assertEqual(metrics["enforced_gate"]["lines"]["percent"], 65.0)

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

    def test_retired_commands_are_in_both_reports_and_above_80_percent(self) -> None:
        excluded = "com.clawfare.cli.FlightValidateCommand"
        retired = "com.clawfare.cli.FlightImportCommand"
        full = self.parse(report([
            (excluded, 8, 2, 7, 3),
            (retired, 9, 91, 19, 81),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 2),
        ]))
        gated = self.parse(report([
            (retired, 9, 91, 19, 81),
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 2),
        ]))
        metrics, markdown = audit(full, gated, (excluded,), (retired,))
        self.assertEqual(metrics["retired_legacy"][retired]["lines"]["percent"], 91.0)
        self.assertEqual(metrics["retired_legacy"][retired]["branches"]["percent"], 81.0)
        self.assertIn("Retired CLI exemptions", markdown)
        self.assertIn(retired, markdown)

    def test_retired_command_cannot_remain_exempted(self) -> None:
        full = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 9, 1, 9, 1),
            ("com.clawfare.cli.FlightImportCommand", 1, 9, 1, 9),
        ]))
        with self.assertRaisesRegex(ValueError, "still exempted"):
            audit(full, full,
                  ("com.clawfare.cli.FlightImportCommand",),
                  ("com.clawfare.cli.FlightImportCommand",))

    def test_retired_command_must_appear_in_filtered_report(self) -> None:
        full = self.parse(report([
            ("com.clawfare.cli.FlightValidateCommand", 9, 1, 9, 1),
            ("com.clawfare.cli.FlightImportCommand", 1, 9, 1, 9),
        ]))
        gated = self.parse(report([
            ("com.clawfare.cli.SafeCommand", 0, 10, 0, 2),
        ]))
        with self.assertRaisesRegex(ValueError, "missing from coverage reports"):
            audit(full, gated,
                  ("com.clawfare.cli.FlightValidateCommand",),
                  ("com.clawfare.cli.FlightImportCommand",))

    def test_retired_command_cannot_drop_below_line_or_branch_80_percent(self) -> None:
        exempt = ("com.clawfare.cli.FlightValidateCommand",)
        retired = ("com.clawfare.cli.FlightImportCommand",)
        for name, missed_lines, covered_lines, missed_branches, covered_branches in [
            ("line", 21, 79, 1, 9),
            ("branch", 1, 9, 21, 79),
            ("zero branches", 1, 9, 0, 0),
        ]:
            with self.subTest(case=name):
                full = self.parse(report([
                    (exempt[0], 8, 2, 7, 3),
                    (retired[0], missed_lines, covered_lines, missed_branches, covered_branches),
                ]))
                gated = self.parse(report([
                    (retired[0], missed_lines, covered_lines, missed_branches, covered_branches),
                ]))
                with self.assertRaisesRegex(ValueError, "fell below 80%"):
                    audit(full, gated, exempt, retired)

    def test_retired_exemptions_are_removed_from_actual_gradle_config(self) -> None:
        gradle = Path(__file__).resolve().parent.parent / "build.gradle.kts"
        configured = set(excluded_legacy_classes(gradle))
        self.assertFalse(configured.intersection(RETIRED_CLI_EXEMPTIONS))

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
        with self.assertRaisesRegex(ValueError, "80% gate"):
            excluded_legacy_classes(path)


if __name__ == "__main__":
    unittest.main()
