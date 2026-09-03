from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')


def rep(old, new, label):
    global s
    if new in s:
        return
    if old not in s:
        raise RuntimeError(f'{label}: target not found')
    s = s.replace(old, new, 1)


def between(start_marker, end_marker, new, label):
    global s
    if new.strip() in s:
        return
    a = s.find(start_marker)
    b = s.find(end_marker, a)
    if a < 0 or b < 0:
        raise RuntimeError(f'{label}: target not found')
    s = s[:a] + new.rstrip() + '\n\n' + s[b:]


rep(
    '    private var searchComposeActive = false\n',
    '    private var searchComposeActive = false\n    private var searchWebComposeActive = false\n',
    'web focus state',
)

rep(
    '''    private fun closeSearchSurface() {
        searchComposeActive = false
        searchInput?.clearFocus()''',
    '''    private fun closeSearchSurface() {
        searchComposeActive = false
        searchWebComposeActive = false
        searchInput?.clearFocus()''',
    'close surface focus',
)

rep(
    '''        stopEmbeddedScanner()
        destroySearchWebView()
        searchComposeActive = false
        searchInput = null
        if (resetCandidate) {''',
    '''        stopEmbeddedScanner()
        destroySearchWebView()
        searchComposeActive = false
        searchWebComposeActive = false
        searchInput = null
        if (resetCandidate) {''',
    'camera focus reset',
)

rep(
    '''    private fun showSearchWebPanel() {
        destroySearchWebView()
        scannerPreviewView = null''',
    '''    private fun showSearchWebPanel() {
        destroySearchWebView()
        searchWebComposeActive = false
        scannerPreviewView = null''',
    'web panel focus reset',
)

rep(
    '''            setOnClickListener {
                searchComposeActive = true
                aiComposeActive = false
            }
            setOnFocusChangeListener { _, focused -> searchComposeActive = focused }''',
    '''            setOnClickListener {
                searchWebComposeActive = false
                searchComposeActive = true
                aiComposeActive = false
            }
            setOnFocusChangeListener { _, focused ->
                searchComposeActive = focused
                if (focused) searchWebComposeActive = false
            }''',
    'header search focus',
)

rep(
    '''            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) userTouchedPage = true
                false
            }
            webViewClient = object : WebViewClient() {''',
    '''            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        userTouchedPage = true
                        searchComposeActive = false
                        searchWebComposeActive = true
                        searchInput?.clearFocus()
                    }
                    MotionEvent.ACTION_UP -> {
                        view.postDelayed({ probeFocusedWebInput() }, WEB_FOCUS_PROBE_DELAY_MS)
                        view.postDelayed({ userTouchedPage = false }, WEB_USER_GESTURE_TIMEOUT_MS)
                    }
                }
                false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.postDelayed({ probeFocusedWebInput() }, WEB_FOCUS_PROBE_DELAY_MS)
                }
''',
    'web touch focus bridge',
)

helpers = r'''    private fun probeFocusedWebInput() {
        val webView = searchWebView ?: run {
            searchWebComposeActive = false
            return
        }
        webView.evaluateJavascript(
            "(function(){var e=document.activeElement;if(!e)return false;var t=(e.tagName||'').toUpperCase();return t==='INPUT'||t==='TEXTAREA'||e.isContentEditable===true;})()"
        ) { raw ->
            if (searchWebView !== webView) return@evaluateJavascript
            val active = raw == "true"
            val changed = active != searchWebComposeActive
            searchWebComposeActive = active
            if (active) {
                searchComposeActive = false
                searchInput?.clearFocus()
            }
            if (changed && ::keyboardPanel.isInitialized && mode == KeyboardMode.LETTERS) renderKeyboard()
        }
    }

    private fun runFocusedWebEdit(action: String, payload: String = "") {
        val webView = searchWebView ?: return
        val actionJson = JSONObject.quote(action)
        val payloadJson = JSONObject.quote(payload)
        val script = """
            (function(){
              var e=document.activeElement;if(!e)return false;
              var tag=(e.tagName||'').toUpperCase();
              var editable=tag==='INPUT'||tag==='TEXTAREA';
              var content=e.isContentEditable===true;
              if(!editable&&!content)return false;
              var action=$actionJson,payload=$payloadJson;
              function setValue(next,cursor,inputType,data){
                var proto=tag==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;
                var d=Object.getOwnPropertyDescriptor(proto,'value');
                if(d&&d.set)d.set.call(e,next);else e.value=next;
                try{e.setSelectionRange(cursor,cursor);}catch(_){}
                try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:inputType,data:data}));}
                catch(_){e.dispatchEvent(new Event('input',{bubbles:true}));}
              }
              if(action==='insert'){
                if(content){e.focus();document.execCommand('insertText',false,payload);e.dispatchEvent(new Event('input',{bubbles:true}));return true;}
                var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a;
                setValue(v.slice(0,a)+payload+v.slice(b),a+payload.length,'insertText',payload);return true;
              }
              if(action==='delete'&&editable){
                var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a,from=a;
                if(a===b){if(a<=0)return true;if(payload==='word'){while(from>0&&/\\s/.test(v.charAt(from-1)))from--;while(from>0&&!/\\s/.test(v.charAt(from-1)))from--;}
                else{var cps=Array.from(v.slice(0,a));cps.pop();from=cps.join('').length;}}
                setValue(v.slice(0,from)+v.slice(b),from,'deleteContentBackward',null);return true;
              }
              if(action==='cursor'&&editable){
                var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a;
                var pos=a!==b?((payload==='left'||payload==='up')?a:b):b;
                if(payload==='left')pos=Math.max(0,pos-1);if(payload==='right')pos=Math.min(v.length,pos+1);
                if(payload==='up'||payload==='down'){
                  var ls=v.lastIndexOf('\\n',Math.max(0,pos-1))+1,col=pos-ls;
                  if(payload==='up'&&ls>0){var pe=ls-1,ps=v.lastIndexOf('\\n',Math.max(0,pe-1))+1;pos=Math.min(ps+col,pe);}
                  if(payload==='down'){var ce=v.indexOf('\\n',pos);if(ce>=0){var ns=ce+1,ne=v.indexOf('\\n',ns);if(ne<0)ne=v.length;pos=Math.min(ns+col,ne);}}
                }
                try{e.setSelectionRange(pos,pos);}catch(_){}return true;
              }
              if(action==='enter'){
                var opt={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
                try{e.dispatchEvent(new KeyboardEvent('keydown',opt));}catch(_){}
                if(e.form){try{if(typeof e.form.requestSubmit==='function')e.form.requestSubmit();else e.form.submit();}catch(_){}return true;}
                if(tag==='TEXTAREA'&&editable){var v=e.value||'',a=typeof e.selectionStart==='number'?e.selectionStart:v.length,b=typeof e.selectionEnd==='number'?e.selectionEnd:a;setValue(v.slice(0,a)+'\\n'+v.slice(b),a+1,'insertLineBreak','\\n');return true;}
                try{e.dispatchEvent(new KeyboardEvent('keyup',opt));}catch(_){}return true;
              }
              return false;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { result -> if (result != "true") probeFocusedWebInput() }
    }

    private fun commitToFocusedWebInput(value: String) = runFocusedWebEdit("insert", value)
    private fun deleteFromFocusedWebInput(word: Boolean = false) = runFocusedWebEdit("delete", if (word) "word" else "char")
    private fun pressEnterInFocusedWebInput() = runFocusedWebEdit("enter")
    private fun moveFocusedWebInputCursor(keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            KeyEvent.KEYCODE_DPAD_DOWN -> "down"
            else -> return
        }
        runFocusedWebEdit("cursor", direction)
    }'''

if '    private fun probeFocusedWebInput() {' not in s:
    marker = '    private fun performSearchFromInput() {'
    if marker not in s:
        raise RuntimeError('web helper insertion target not found')
    s = s.replace(marker, helpers + '\n\n' + marker, 1)

rep(
    '''    private fun destroySearchWebView() {
        searchWebView?.let { webView ->''',
    '''    private fun destroySearchWebView() {
        searchWebComposeActive = false
        searchWebView?.let { webView ->''',
    'destroy web focus',
)

new_delete_one = '''    private fun deleteOne() {
        if (searchWebComposeActive) {
            deleteFromFocusedWebInput()
            refreshSuggestionsSoon()
            return
        }
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceAtLeast(0)
            val end = internalInput.selectionEnd.coerceAtLeast(0)
            when {
                start != end -> editable.delete(minOf(start, end), maxOf(start, end))
                start > 0 -> editable.delete(start - 1, start)
            }
        } else {
            val ic = currentInputConnection ?: return
            if (!deleteSelectedText(ic)) deletePreviousCharacterCompat(ic)
        }
        refreshSuggestionsSoon()
    }'''
between('    private fun deleteOne() {', '    private fun deleteWord() {', new_delete_one, 'delete routing')

new_delete_word = '''    private fun deleteWord() {
        if (searchWebComposeActive) {
            deleteFromFocusedWebInput(word = true)
            refreshSuggestionsSoon()
            return
        }
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceAtLeast(0)
            val end = internalInput.selectionEnd.coerceAtLeast(0)
            if (start != end) editable.delete(minOf(start, end), maxOf(start, end))
            else if (start > 0) {
                val before = editable.substring(0, start)
                val trailing = before.takeLastWhile(Char::isWhitespace).length
                val body = before.dropLast(trailing)
                val count = (trailing + body.takeLastWhile { !it.isWhitespace() }.length).coerceAtLeast(1)
                editable.delete((start - count).coerceAtLeast(0), start)
            }
            refreshSuggestionsSoon()
            return
        }
        val ic = currentInputConnection ?: return
        if (deleteSelectedText(ic)) { refreshSuggestionsSoon(); return }
        val before = runCatching { ic.getTextBeforeCursor(100, 0)?.toString().orEmpty() }.getOrDefault("")
        val count = before.takeLastWhile { !it.isWhitespace() }.length.coerceAtLeast(1)
        repeat(count.coerceAtMost(100)) { deletePreviousCharacterCompat(ic) }
        refreshSuggestionsSoon()
    }'''
between('    private fun deleteWord() {', '    private fun deletePreviousCharacterCompat(ic: InputConnection): Boolean {', new_delete_word, 'delete word routing')

new_delete_instant = '''    private fun deleteInstantCommittedCharacter(): Boolean {
        if (searchWebComposeActive) {
            deleteFromFocusedWebInput()
            return true
        }
        val internalInput = activeInternalInput()
        if (internalInput != null) {
            val editable = internalInput.text
            val start = internalInput.selectionStart.coerceAtLeast(0)
            val end = internalInput.selectionEnd.coerceAtLeast(0)
            return when {
                start != end -> { editable.delete(minOf(start, end), maxOf(start, end)); true }
                start > 0 -> { editable.delete(start - 1, start); true }
                else -> false
            }
        }
        val ic = currentInputConnection ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) ic.deleteSurroundingTextInCodePoints(1, 0)
            else ic.deleteSurroundingText(1, 0)
        }.getOrDefault(false)
    }'''
between('    private fun deleteInstantCommittedCharacter(): Boolean {', '    private fun deleteSelectedText(ic: InputConnection): Boolean {', new_delete_instant, 'instant delete routing')

rep(
    '''    private fun enterKeyLabel(): String {
        if (searchComposeActive) return "🔍"''',
    '''    private fun enterKeyLabel(): String {
        if (searchComposeActive || searchWebComposeActive) return "🔍"''',
    'search enter icon',
)
rep(
    '''    private fun pressEnter() {
        if (searchComposeActive) {''',
    '''    private fun pressEnter() {
        if (searchWebComposeActive) {
            pressEnterInFocusedWebInput()
            return
        }
        if (searchComposeActive) {''',
    'web enter',
)
rep(
    '''    private fun commit(text: String) {
        val internalInput = activeInternalInput()''',
    '''    private fun commit(text: String) {
        if (searchWebComposeActive) {
            commitToFocusedWebInput(text)
            refreshSuggestionsSoon()
            return
        }
        val internalInput = activeInternalInput()''',
    'web commit',
)
rep(
    '''    private fun commitSpace() {
        if (activeInternalInput() != null) {''',
    '''    private fun commitSpace() {
        if (searchWebComposeActive) {
            commitToFocusedWebInput(" ")
            return
        }
        if (activeInternalInput() != null) {''',
    'web space',
)
rep(
    '''    private fun moveCursor(keyCode: Int) {
        val internalInput = activeInternalInput()''',
    '''    private fun moveCursor(keyCode: Int) {
        if (searchWebComposeActive) {
            moveFocusedWebInputCursor(keyCode)
            return
        }
        val internalInput = activeInternalInput()''',
    'web cursor',
)
rep(
    '        if (!suggestionsEnabled || isSensitiveEditor() || (mode != KeyboardMode.LETTERS && activeInternalInput() == null)) {',
    '        if (!suggestionsEnabled || searchWebComposeActive || isSensitiveEditor() || (mode != KeyboardMode.LETTERS && activeInternalInput() == null)) {',
    'web suggestions guard',
)
rep(
    '''        private const val SCANNER_CANDIDATE_HOLD_MS = 1_250L
''',
    '''        private const val SCANNER_CANDIDATE_HOLD_MS = 1_250L
        private const val WEB_FOCUS_PROBE_DELAY_MS = 55L
        private const val WEB_USER_GESTURE_TIMEOUT_MS = 240L
''',
    'web timing constants',
)

p.write_text(s, encoding='utf-8')
print('Applied v0.18 direct embedded-web typing fix.')
