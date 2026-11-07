#!/usr/bin/env python3
"""Regression checks for Arabic fiscal-year date parsing in Moqui."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
from tempfile import TemporaryDirectory

from patch_time_period_locale import main, patch_source, REPLACEMENTS, RELATIVE_PATH

old_from = next(iter(REPLACEMENTS))
old_thru = list(REPLACEMENTS)[1]
sample = (
    '<entity-facade-xml>\n'
    f'  <set field="fromLocalDate" from="{old_from}"/>\n'
    f'  <set field="thruLocalDate" from="{old_thru}"/>\n'
    '</entity-facade-xml>\n'
)
patched = patch_source(sample)
ET.fromstring(patched)
assert 'ec.l10n.format' not in patched
assert 'LocalDate.parse(' not in patched
assert 'Instant.ofEpochMilli(fromDate.time)' in patched
assert 'Instant.ofEpochMilli(thruDate.time)' in patched
assert patch_source(patched) == patched, "Patch must be idempotent"
for broken in (sample.replace(old_from, ""), sample.replace(old_thru, "")):
    try:
        patch_source(broken)
    except ValueError:
        pass
    else:
        raise AssertionError("Upstream source drift must abort the image build")
with TemporaryDirectory() as temp:
    source = Path(temp) / RELATIVE_PATH
    source.parent.mkdir(parents=True)
    source.write_text(sample)
    main(Path(temp))
    assert source.read_text() == patched

dockerfile = (Path(__file__).resolve().parent / 'Dockerfile.moqui').read_text()
assert 'python3 /src/nasqo/deploy/nasqo/patch_time_period_locale.py /src/nasqo' in dockerfile
print('PASS: Arabic digits no longer reach ISO LocalDate.parse during TimePeriod creation')
