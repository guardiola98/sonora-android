#!/usr/bin/env python3
"""Check translated resources, format arguments and explicit app-owned templates.

English resources are the source of truth. Run with --write after adding a resource
or changing its English template to regenerate the Kotlin lookup table.
"""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CODE = ROOT / 'app/src/main/java/org/sonora/music'
CATALOG = CODE / 'i18n/StringCatalog.kt'


def decode_android(value):
    if value.startswith('"') and value.endswith('"'):
        value = value[1:-1]
    return re.sub(r'\\([nrt\\\'"@?])', lambda m: {'n': '\n', 'r': '\r', 't': '\t'}.get(m[1], m[1]), value)


def read(locale):
    path = ROOT / f'app/src/main/res/values{locale}/strings.xml'
    rows = [(n.attrib['name'], decode_android(''.join(n.itertext()))) for n in ET.parse(path).getroot().findall('string')]
    assert len(dict(rows)) == len(rows), f'Duplicate resource in {path}'
    return dict(rows)


def literal(value):
    return json.dumps(value, ensure_ascii=False).replace('$', '\\$')


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--write', action='store_true')
    args = args.parse_args()
    english = read('')
    assert len(set(english.values())) == len(english), 'Duplicate English template'
    for locale in ('-es', '-ca'):
        translated = read(locale)
        assert english.keys() == translated.keys(), f'Resource keys differ for {locale}'
        for name, source in english.items():
            value = translated[name]
            assert value.strip(), f'Empty translation: {locale}/{name}'
            formats = lambda text: sorted(re.findall(r'%(?:\d+\$)?[sdfox]|%%', text))
            assert formats(source) == formats(value), f'Format arguments differ: {locale}/{name}'
    generated = ('package org.sonora.music.i18n\n\nimport org.sonora.music.R\n\n'
                 '// Generated from values/strings.xml by tools/verify-translations.py --write.\n'
                 'internal val stringIds: Map<String, Int> = mapOf(\n' +
                 ''.join(f'    {literal(source)} to R.string.{name},\n' for name, source in english.items()) + ')\n')
    if args.write:
        CATALOG.write_text(generated)
    assert CATALOG.read_text() == generated, 'Stale string catalog; run tools/verify-translations.py --write'
    sources = set(english.values())
    calls = 0
    for path in CODE.rglob('*.kt'):
        for match in re.finditer(r'\btr\(\s*("(?:\\.|[^"\\])*")', path.read_text()):
            source = json.loads(match[1].replace('\\$', '$'))
            assert source in sources, f'Missing resource for {path.relative_to(ROOT)}: {source}'
            calls += 1
    print(f'OK: {len(english)} resources in English, Spanish and Catalan; {calls} explicit translated templates.')


if __name__ == '__main__':
    main()
