#!/usr/bin/env python3
"""Static regression checks for the NASQO SQLite preview service boundary."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UTILITY = ROOT / "backend/service/NasqoSqliteImportUtil.groovy"
SERVICE = ROOT / "backend/service/growerp/100/NasqoSqliteImportServices100.xml"

# The source filename is NasqoSqliteImportUtil.groovy, therefore the helper
# class MUST have a different name: Groovy generates a script class matching
# the filename, and the same class name would fail compilation.
utility = UTILITY.read_text(encoding="utf-8")
assert "class NasqoSqliteImportPreviewHelper" in utility
assert "class NasqoSqliteImportUtil" not in utility
assert utility.rstrip().endswith("return NasqoSqliteImportPreviewHelper"), (
    "Groovy script must return a distinct callable preview helper class"
)
# Groovy 5 runtime in the deployed Moqui image has no groovy-sql module.
# Use standard JDBC instead; never attempt to write to the uploaded SQLite.
assert "import groovy.sql.Sql" not in utility
assert "import java.sql.DriverManager" in utility
assert "PRAGMA query_only=ON" in utility
assert "'jdbc:sqlite:' + uri + '?mode=ro'" in utility
assert "'PRAGMA integrity_check'" in utility
assert "PRAGMA integrity_check()" not in utility
assert "conn.close()" in utility
assert "postAccounting: false" in utility

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
