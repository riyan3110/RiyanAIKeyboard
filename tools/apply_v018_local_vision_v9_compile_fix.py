from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')

if '    private fun scannerTargetRect(width: Int, height: Int): Rect' not in s:
    marker = '    private fun scannerObjectRelevance(bounds: Rect, target: Rect): Float {'
    if marker not in s:
        raise RuntimeError('local vision object relevance marker not found')
    helpers = r'''    private fun scannerTargetRect(width: Int, height: Int): Rect = Rect(
        (width.coerceAtLeast(1) * 0.07f).toInt(),
        (height.coerceAtLeast(1) * 0.24f).toInt(),
        (width.coerceAtLeast(1) * 0.93f).toInt(),
        (height.coerceAtLeast(1) * 0.76f).toInt()
    )

    private fun scannerLineInsideTarget(bounds: Rect?, target: Rect): Boolean {
        bounds ?: return false
        if (!Rect.intersects(bounds, target)) return false
        if (target.contains(bounds.centerX(), bounds.centerY())) return true
        val left = maxOf(bounds.left, target.left)
        val top = maxOf(bounds.top, target.top)
        val right = minOf(bounds.right, target.right)
        val bottom = minOf(bounds.bottom, target.bottom)
        val overlap = (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
        val area = bounds.width().coerceAtLeast(1).toLong() * bounds.height().coerceAtLeast(1).toLong()
        return overlap.toDouble() / area.toDouble() >= 0.60
    }

    private fun scannerProminentLineScore(line: ScannerTextLine, target: Rect, frameHeight: Int): Int {
        var score = scannerTextLineScore(line.text)
        line.bounds?.let { box ->
            score += ((box.height().toFloat() / frameHeight.coerceAtLeast(1)) * 360f).toInt().coerceIn(0, 42)
            val dx = kotlin.math.abs(box.centerX() - target.centerX()).toFloat() / target.width().coerceAtLeast(1)
            val dy = kotlin.math.abs(box.centerY() - target.centerY()).toFloat() / target.height().coerceAtLeast(1)
            score += ((1f - (dx + dy).coerceIn(0f, 1f)) * 18f).toInt()
        }
        if (OCR_PRICE_REGEX.containsMatchIn(line.text) && !line.text.any { ch ->
                ch.isLetter() && ch.uppercaseChar() !in "RPIDRUSD"
            }) score -= 18
        val compact = line.text.filterNot(Char::isWhitespace)
        if (compact.length <= 7 && compact.any(Char::isLetter) && compact.any(Char::isDigit) &&
            !OCR_PRICE_REGEX.containsMatchIn(line.text)) score -= 10
        return score
    }

'''
    s = s.replace(marker, helpers + marker, 1)

p.write_text(s, encoding='utf-8')
print('Restored scanner ROI/ranking helpers removed by local vision replacement.')
