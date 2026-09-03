from pathlib import Path

path = Path('app/src/main/java/com/riyan/aikeyboard/AiClient.kt')
s = path.read_text(encoding='utf-8')

marker = '    private const val MAX_AUTOMATIC_SOURCES = 7\n'
if marker not in s:
    raise RuntimeError('MAX_AUTOMATIC_SOURCES v20 marker not found')

insert = marker
if 'private const val MIN_PREFERRED_SOURCES' not in s:
    insert += '    private const val MIN_PREFERRED_SOURCES = 5\n'
if 'private const val MAX_FETCH_CANDIDATES' not in s:
    insert += '    private const val MAX_FETCH_CANDIDATES = 14\n'

if insert != marker:
    s = s.replace(marker, insert, 1)

path.write_text(s, encoding='utf-8')
print('Restored v20 research constants.')
