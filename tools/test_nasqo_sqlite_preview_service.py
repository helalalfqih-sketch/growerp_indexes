#!/usr/bin/env python3
"""Static regression checks for the NASQO SQLite preview service boundary."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UTILITY = ROOT / "backend/service/NasqoSqliteImportUtil.groovy"
SERVICE = ROOT / "backend/service/growerp/100/NasqoSqliteImportServices100.xml"

# Moqui ec.resource.script() returns the last script expression. The helper
# must yield its class; a class-only Groovy file yields no callable helper.
utility = UTILITY.read_text(encoding="utf-8")
assert "class NasqoSqliteImportUtil" in utility
assert utility.rstrip().endswith("return NasqoSqliteImportUtil"), (
    "SQLite helper must return its class to ec.resource.script()"
)

# A clean runtime may not have runtime/ownerData yet. Prepare it *before*
# Java's File.createTempFile, not afterwards.
root = ET.parse(SERVICE).getroot()
service = next(
    entry for entry in root.findall("service")
    if entry.get("verb") == "preview" and entry.get("noun") == "SqliteImport"
)
script = service.find("./actions/script")
assert script is not None and script.text
body = script.text
mkdir_pos = body.index("dir.mkdirs()")
temp_pos = body.index("File.createTempFile")
assert mkdir_pos < temp_pos, "Create the temp directory before the file"
assert "ec.resource.script('component://growerp/service/NasqoSqliteImportUtil.groovy', null)" in body
assert "preview = util.preview(temp)" in body
assert "temp.delete()" in body

print("PASS: NASQO SQLite preview source contract")
