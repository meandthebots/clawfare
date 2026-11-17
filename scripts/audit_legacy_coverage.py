#!/usr/bin/env python3
"""Audit Kover's unfiltered JVM coverage without changing the enforced gate.

The Gradle total report retains the legacy CLI exclusions and 80% threshold.
The JVM variant report intentionally has NO exclusions. Both reports come from
the same instrumented tests, so this tool compares like-for-like coverage.

All inputs are generated locally; nothing is fetched or uploaded by this tool.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


@dataclass(frozen=True)
class Counts:
    missed: int = 0
    covered: int = 0

    @property
    def total(self) -> int:
        return self.missed + self.covered

    @property
    def percentage(self) -> str:
        if self.total == 0:
            return "n/a"
        return f"{100 * self.covered / self.total:.1f}%"

    def __add__(self, other: Counts) -> Counts:
        return Counts(self.missed + other.missed, self.covered + other.covered)

    def as_json(self) -> dict[str, int | float | None]:
        return {
            "covered": self.covered,
            "missed": self.missed,
            "total": self.total,
            "percent": round(100 * self.covered / self.total, 2) if self.total else None,
        }


@dataclass(frozen=True)
class ClassCoverage:
    name: str
    lines: Counts
    branches: Counts


@dataclass(frozen=True)
class Report:
    classes: dict[str, ClassCoverage]

    def sum_counters(self, selected: set[str]) -> tuple[Counts, Counts]:
        lines, branches = Counts(), Counts()
        for name in sorted(selected):
            entry = self.classes[name]
            lines += entry.lines
            branches += entry.branches
        return lines, branches


def excluded_legacy_classes(gradle_file: Path) -> tuple[str, ...]:
    """Read the checked-in exclusions, not a second manually maintained list."""
    contents = gradle_file.read_text(encoding="utf-8")
    if "kover {" not in contents or 'variant("jvm")' not in contents:
        raise ValueError("Expected Kover total gate and unfiltered JVM variant")
    config = contents.split("kover {", 1)[1].split("\napplication {", 1)[0]
    if "total {" not in config or "minBound(80)" not in config:
        raise ValueError("Enforced total-report 80% gate missing")
    names = re.findall(r'"(com\.clawfare\.cli\.[A-Za-z][A-Za-z0-9_]*)"', config)
    if not names or len(set(names)) != len(names):
        raise ValueError("Expected distinct, explicitly named legacy CLI exemptions")
    return tuple(sorted(names))


def parse_xml_report(source: Path) -> Report:
    """Parse JaCoCo-compatible class-level LINE/BRANCH counts from Kover XML."""
    root = ET.parse(source).getroot()
    if root.tag != "report":
        raise ValueError(f"Not a Kover/JaCoCo report: {source}")
    classes: dict[str, ClassCoverage] = {}
    for item in root.findall("./package/class"):
        raw_name = item.get("name")
        if not raw_name:
            raise ValueError("Kover class without a name")
        name = raw_name.replace("/", ".")
        if name in classes:
            raise ValueError(f"Duplicate coverage class: {name}")
        counters: dict[str, Counts] = {}
        for counter in item.findall("counter"):
            kind = counter.get("type")
            if kind not in ("LINE", "BRANCH"):
                continue
            if kind in counters:
                raise ValueError(f"Duplicate {kind} coverage counter: {name}")
            try:
                missed = int(counter.attrib["missed"])
                covered = int(counter.attrib["covered"])
            except (KeyError, ValueError) as exc:
                raise ValueError(f"Invalid {kind} coverage counter: {name}") from exc
            if missed < 0 or covered < 0:
                raise ValueError(f"Negative {kind} coverage counter: {name}")
            counters[kind] = Counts(missed, covered)
        classes[name] = ClassCoverage(
            name, counters.get("LINE", Counts()), counters.get("BRANCH", Counts())
        )
    if not classes:
        raise ValueError(f"No classes found in Kover report: {source}")
    return Report(classes)


def audit(
    unfiltered: Report, filtered: Report, excluded: tuple[str, ...]
) -> tuple[dict[str, object], str]:
    excluded_set = set(excluded)
    visible = excluded_set & unfiltered.classes.keys()
    missing = sorted(excluded_set - unfiltered.classes.keys())
    leaked = sorted(excluded_set & filtered.classes.keys())
    if not visible:
        raise ValueError(
            "Unfiltered JVM report contains none of the legacy-excluded classes; "
            "it may still be filtered"
        )
    if leaked:
        raise ValueError(
            "The enforced total report unexpectedly includes excluded classes: "
            + ", ".join(leaked)
        )
    if len(filtered.classes) >= len(unfiltered.classes) and not missing:
        raise ValueError("Unfiltered report did not add any classes")
    all_lines, all_branches = unfiltered.sum_counters(set(unfiltered.classes))
    gate_lines, gate_branches = filtered.sum_counters(set(filtered.classes))
    legacy_lines, legacy_branches = unfiltered.sum_counters(visible)
    metrics: dict[str, object] = {
        "scope": "JVM classes and instrumented JVM tests; excludes JS/Wasm",
        "coverage_unit": "Kover LINE and BRANCH counters; not instruction coverage",
        "unfiltered": {"classes": len(unfiltered.classes), "lines": all_lines.as_json(),
                       "branches": all_branches.as_json()},
        "enforced_gate": {"classes": len(filtered.classes), "lines": gate_lines.as_json(),
                          "branches": gate_branches.as_json()},
        "excluded_legacy": {"configured": len(excluded), "reported": len(visible),
                            "lines": legacy_lines.as_json(),
                            "branches": legacy_branches.as_json()},
        "missing_legacy_classes": missing,
    }
    table = [
        "# Legacy CLI — unfiltered Kover coverage",
        "",
        "Generated from the **same test run** as the enforced filtered report.",
        "The normal Kover **80% gate remains unchanged**. This audit is informational.",
        "It covers the JVM application only; JS/Wasm targets have independent tests.",
        "",
        "| Scope | Classes | Line coverage | Branch coverage |",
        "| --- | ---: | ---: | ---: |",
        f"| All JVM classes (unfiltered) | {len(unfiltered.classes)} | "
        f"{all_lines.percentage} | {all_branches.percentage} |",
        f"| Existing enforced gate (filtered) | {len(filtered.classes)} | "
        f"{gate_lines.percentage} | {gate_branches.percentage} |",
        f"| Legacy exclusions (measured) | {len(visible)} / {len(excluded)} | "
        f"{legacy_lines.percentage} | {legacy_branches.percentage} |",
        "",
        "## Excluded CLI class baseline",
        "",
        "| Class | Covered / total lines | Line % | Covered / total branches | Branch % |",
        "| --- | ---: | ---: | ---: | ---: |",
    ]
    for name in excluded:
        entry = unfiltered.classes.get(name)
        if entry is None:
            table.append(f"| `{name}` | absent | n/a | absent | n/a |")
            continue
        table.append(
            f"| `{name}` | {entry.lines.covered} / {entry.lines.total} | "
            f"{entry.lines.percentage} | "
            f"{entry.branches.covered} / {entry.branches.total} | "
            f"{entry.branches.percentage} |"
        )
    if missing:
        table.extend([
            "",
            "**Missing from the unfiltered report:** " + ", ".join(f"`{name}`" for name in missing),
            "These names remain in the configured exemption list; investigate before retiring them.",
        ])
    table.extend([
        "",
        "## Interpretation",
        "",
        "- A branch percentage is **n/a** when a class has no branch counters.",
        "- JVM class coverage is not a proxy for end-to-end provider behaviour.",
        "- Removing a class from the exemptions requires tests, coverage review,",
        "  and a separate PR proving that the enforced 80% gate still passes.",
        "- Raw JaCoCo-compatible XML and this Markdown summary are CI artifacts.",
        "",
    ])
    return metrics, "\n".join(table)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--unfiltered", type=Path, required=True)
    parser.add_argument("--filtered", type=Path, required=True)
    parser.add_argument("--gradle", type=Path, default=Path("build.gradle.kts"))
    parser.add_argument("--markdown", type=Path, required=True)
    parser.add_argument("--json", type=Path, required=True)
    args = parser.parse_args()
    try:
        exclusions = excluded_legacy_classes(args.gradle)
        unfiltered = parse_xml_report(args.unfiltered)
        filtered = parse_xml_report(args.filtered)
        metrics, markdown = audit(unfiltered, filtered, exclusions)
    except (ValueError, ET.ParseError, OSError) as exc:
        print(f"Coverage audit failed: {exc}", file=sys.stderr)
        return 1
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    args.json.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.write_text(markdown, encoding="utf-8")
    args.json.write_text(json.dumps(metrics, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(markdown)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
