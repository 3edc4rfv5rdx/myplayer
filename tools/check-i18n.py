#!/usr/bin/env python3
"""i18n consistency check: Kotlin sources <-> app/src/main/assets/i18n.json.

The app uses the English text itself as the translation key (see AppLocalizer.kt), so nothing
ties code and i18n.json together at compile time: editing a UI string in code silently orphans
its translations. Run this after changing any lw()/loc() string or i18n.json.

Checks:
  1. every direct lw("...")/loc("...") key in the sources exists in i18n.json;
  2. every i18n.json key appears as a "..." literal somewhere in the sources
     (catches drift: the English text was edited in code, the old key remained);
  3. every key is translated into every language declared in the "_lang" block,
     and carries no unknown language codes;
  4. values are clean: no leading/trailing whitespace, no trailing punctuation
     (punctuation is added in code, per convention);
  5. every "_alias" entry (system ISO code -> internal code) maps to a declared language.

Known limitation: a brand-new string passed to lw() through a when/if branch and never added to
i18n.json is not detected here (it just renders as untranslated English in the app); check 2
still catches the reverse direction once the key lands in the file.

Usage: python3 tools/check-i18n.py   (exit 0 = consistent, 1 = problems printed)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JSON_PATH = ROOT / "app/src/main/assets/i18n.json"
SRC_DIR = ROOT / "app/src/main/java"


def strip_comments(src: str) -> str:
    """Drops /* */ and // comments so lw("...") examples in KDoc aren't taken for real calls."""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def main() -> int:
    data = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    special = {"_lang", "_alias"}
    langs = set(data["_lang"])
    keys = {k for k in data if k not in special}
    sources = {p: p.read_text(encoding="utf-8") for p in sorted(SRC_DIR.rglob("*.kt"))}
    all_src = "\n".join(sources.values())
    problems = []

    # 1. Direct lw("...")/loc("...") calls must have a key in i18n.json.
    for path, src in sources.items():
        for key in re.findall(r'\b(?:lw|loc)\(\s*"([^"]+)"\s*\)', strip_comments(src)):
            if key not in keys:
                problems.append(f'{path.name}: lw/loc key not in i18n.json: "{key}"')

    # 2. Every i18n.json key must appear as a string literal somewhere in the sources.
    for key in sorted(keys):
        if f'"{key}"' not in all_src:
            problems.append(f'i18n.json key not found in code (edited or removed?): "{key}"')

    # 3. Full translation coverage, no unknown language codes.
    for key in sorted(keys):
        missing = (langs - {"en"}) - set(data[key])
        if missing:
            problems.append(f'"{key}": missing translations: {", ".join(sorted(missing))}')
        extra = set(data[key]) - langs
        if extra:
            problems.append(f'"{key}": unknown language codes: {", ".join(sorted(extra))}')

    # 4. Clean values: no stray whitespace, no trailing punctuation.
    for key in sorted(keys):
        for lang, value in sorted(data[key].items()):
            if value != value.strip():
                problems.append(f'"{key}" [{lang}]: leading/trailing whitespace')
            elif value and value[-1] in ".!?:;,":
                problems.append(f'"{key}" [{lang}]: trailing punctuation: "{value}"')

    # 5. Alias targets must be declared languages (and not alias a declared code away).
    for iso, code in data.get("_alias", {}).items():
        if code not in langs:
            problems.append(f'_alias: "{iso}" maps to undeclared language "{code}"')
        if iso in langs:
            problems.append(f'_alias: "{iso}" is itself a declared language; alias would shadow it')

    if problems:
        print(f"{len(problems)} problem(s):")
        for p in problems:
            print("  " + p)
        return 1
    print(f"OK: {len(keys)} keys, languages: {', '.join(sorted(langs))}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
