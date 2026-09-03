from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')
s = s.replace(r'Regex("\s+")', r'Regex("\\s+")')
p.write_text(s, encoding='utf-8')
print('Fixed Kotlin regex escaping after v0.18 camera patch.')
