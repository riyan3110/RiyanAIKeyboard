from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')

# v11 keeps the existing camera/AI Vision pipeline intact and only replaces the
# embedded web search provider. Text searches use Bing Web Search; camera AI
# Vision queries use Bing Images. Direct URLs/barcodes continue to bypass search.

# Clean internal names left by the old Brave routing patch so future patches do
# not accidentally depend on Brave-specific state/helper names.
s = s.replace('scannerBraveVisualQuery', 'scannerBingVisualQuery')
s = s.replace('openBraveImageResults', 'openBingImageResults')

# Replace Brave runtime URLs with Microsoft Bing.
url_replacements = {
    'https://search.brave.com/images?q=': 'https://www.bing.com/images/search?q=',
    'https://search.brave.com/search?q=': 'https://www.bing.com/search?q=',
    'https://search.brave.com/': 'https://www.bing.com/',
}
for old, new in url_replacements.items():
    s = s.replace(old, new)

# Replace user-visible provider wording. This intentionally does not change
# unrelated camera/AI Vision behavior.
s = s.replace('Brave Images', 'Bing Images')
s = s.replace('Brave Search', 'Bing Search')
s = s.replace('Brave mode', 'Bing mode')
s = s.replace('Brave helper', 'Bing helper')
s = s.replace('Brave scanner search', 'Bing scanner search')
s = s.replace('Brave only', 'Bing only')
s = s.replace('Brave does not expose', 'Bing routing does not use')
s = s.replace('Brave needs the', 'Bing uses the')

# The AI Vision v10 path must land directly on Bing Images, while ordinary
# typed searches keep the normal Bing web results page.
if 'https://www.bing.com/images/search?q=${Uri.encode(query)}' not in s:
    raise RuntimeError('Bing Images camera route was not produced')
if 'https://www.bing.com/search?q=' not in s and 'https://www.bing.com/' not in s:
    raise RuntimeError('Bing web route was not produced')
if 'search.brave.com' in s:
    raise RuntimeError('A Brave runtime URL is still present after Bing migration')

p.write_text(s, encoding='utf-8')
print('Applied v0.18 v11: embedded web -> Microsoft Bing; camera AI Vision -> Bing Images; no Brave runtime URL remains.')
