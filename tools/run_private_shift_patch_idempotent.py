#!/usr/bin/env python3
"""Run the existing PRIVATE v034 patch safely after newer PRIVATE generators."""
from pathlib import Path

script = Path(__file__).resolve().parent / "apply_private_shift_ai_actions_patch.py"
source = script.read_text(encoding="utf-8")
old = '''def replace(s, old, new):
    assert old in s, 'PRIVATE v034 missing marker: ' + old[:100]
    return s.replace(old, new, 1)
'''
new = '''def replace(s, old, new):
    if new in s:
        return s
    assert old in s, 'PRIVATE v034 missing marker: ' + old[:100]
    return s.replace(old, new, 1)
'''
if old not in source and new not in source:
    raise RuntimeError("PRIVATE v034 replace helper was not found")
if old in source:
    source = source.replace(old, new, 1)
namespace = {
    "__file__": str(script),
    "__name__": "__main__",
}
exec(compile(source, str(script), "exec"), namespace)
