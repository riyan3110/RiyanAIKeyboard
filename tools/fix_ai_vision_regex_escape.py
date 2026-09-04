from pathlib import Path

path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
text = path.read_text()
old = 'Regex("\\s+")'
new = 'Regex("\\\\s+")'
count = text.count(old)
if count != 1:
    raise SystemExit(f'expected one bad regex escape, got {count}')
path.write_text(text.replace(old, new, 1))
print('fixed AiClient regex escape')
