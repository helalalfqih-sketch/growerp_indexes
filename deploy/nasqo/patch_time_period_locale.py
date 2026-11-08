#!/usr/bin/env python3
"""Fix Moqui fiscal-period date conversion for Arabic and other digit locales.

Mantle-USL currently formats a java.sql.Date with ec.l10n.format, then calls
LocalDate.parse on the localized digits. Java's ISO parser rejects Arabic-Indic
digits (e.g. ٢٠٢٦-٠١-٠١). Convert directly using the same JVM timezone as the
adjacent period-number calculation; this changes no account or financial data.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

RELATIVE_PATH = Path(
    "moqui/runtime/component/mantle-usl/service/mantle/party/TimeServices.xml"
)

REPLACEMENTS = {
    "java.time.LocalDate.parse(ec.l10n.format(fromDate, 'yyyy-MM-dd'))":
        "java.time.Instant.ofEpochMilli(fromDate.time).atZone(java.time.ZoneId.systemDefault()).toLocalDate()",
    "java.time.LocalDate.parse(ec.l10n.format(thruDate, 'yyyy-MM-dd'))":
        "java.time.Instant.ofEpochMilli(thruDate.time).atZone(java.time.ZoneId.systemDefault()).toLocalDate()",
}


def patch_source(content: str) -> str:
    if all(new in content and old not in content for old, new in REPLACEMENTS.items()):
        return content
    for old, new in REPLACEMENTS.items():
        occurrences = content.count(old)
        if occurrences != 1:
            raise ValueError(
                f"Expected exactly one Mantle date expression; found {occurrences}: {old}"
            )
        content = content.replace(old, new, 1)
    ET.fromstring(content)
    return content


def main(repo: Path) -> None:
    source = repo / RELATIVE_PATH
    original = source.read_text(encoding="utf-8")
    patched = patch_source(original)
    source.write_text(patched, encoding="utf-8")
    print("Locale-independent Moqui TimePeriod conversion verified:", source)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: patch_time_period_locale.py REPO_PATH")
    main(Path(sys.argv[1]))
