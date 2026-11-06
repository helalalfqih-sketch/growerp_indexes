#!/usr/bin/env python3
"""Check Arabic coverage and ICU argument names for the accounting app modules.

Flutter gen-l10n remains responsible for validating ICU syntax and argument
types. This check prevents its automatic English fallback from hiding omissions
in the modules that we explicitly advertise as translated.
"""

import json
import re
from pathlib import Path


PACKAGES = (
    "growerp_core",
    "growerp_order_accounting",
    "growerp_user_company",
    "growerp_catalog",
    "growerp_inventory",
    "growerp_pos",
    "growerp_activity",
)
ARGUMENT = re.compile(r"\{([A-Za-z_]\w*)\s*[,}]")


def main():
    root = Path(__file__).resolve().parents[1] / "packages"
    errors = []
    total = 0
    for package in PACKAGES:
        prefix = "core" if package == "growerp_core" else "intl"
        directory = root / package / "lib" / "l10n"
        english = json.loads((directory / f"{prefix}_en.arb").read_text())
        arabic = json.loads((directory / f"{prefix}_ar.arb").read_text())
        if arabic.get("@@locale") != "ar":
            errors.append(f"{package}: @@locale must be ar")
        source = {k: v for k, v in english.items() if not k.startswith("@")}
        target = {k: v for k, v in arabic.items() if not k.startswith("@")}
        for key in sorted(source.keys() - target.keys()):
            errors.append(f"{package}.{key}: missing Arabic message")
        for key in sorted(target.keys() - source.keys()):
            errors.append(f"{package}.{key}: unknown Arabic message")
        for key in source.keys() & target.keys():
            translation = target[key]
            if not isinstance(translation, str) or not translation.strip():
                errors.append(f"{package}.{key}: empty or non-string message")
            elif set(ARGUMENT.findall(source[key])) != set(
                ARGUMENT.findall(translation)
            ):
                errors.append(f"{package}.{key}: ICU argument names differ")
        total += len(source)
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"Arabic coverage: {total} messages across {len(PACKAGES)} packages")


if __name__ == "__main__":
    main()
