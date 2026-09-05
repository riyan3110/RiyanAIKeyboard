from pathlib import Path

p = Path("app/src/main/java/com/riyan/aikeyboard/BraveBrowserPanel.kt")
text = p.read_text()

if "AIAdsBraveSettings" in text and "installBraveSearchSettingsBridge" in text:
    print("Brave Search select bridge already applied")
    raise SystemExit(0)


def replace_once(old: str, new: str, label: str):
    global text
    if old not in text:
        raise SystemExit(f"patch target missing: {label}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.webkit.CookieManager\n",
    "import android.webkit.CookieManager\nimport android.webkit.JavascriptInterface\n",
    "JavascriptInterface import",
)

replace_once(
    "            setLayerType(View.LAYER_TYPE_HARDWARE, null)\n            webViewClient = object : WebViewClient() {\n",
    "            setLayerType(View.LAYER_TYPE_HARDWARE, null)\n            addJavascriptInterface(BraveSearchSettingsBridge(), \"AIAdsBraveSettings\")\n            webViewClient = object : WebViewClient() {\n",
    "javascript bridge registration",
)

replace_once(
    "                        persistTabs()\n                    }\n                }\n            }\n            webChromeClient = object : WebChromeClient() {\n",
    "                        persistTabs()\n                        installBraveSearchSettingsBridge(view, clean)\n                    }\n                }\n            }\n            webChromeClient = object : WebChromeClient() {\n",
    "install bridge after page load",
)

marker = "    private fun showQuickSettings() {\n"
if marker not in text:
    raise SystemExit("patch target missing: showQuickSettings marker")

bridge = r'''    private inner class BraveSearchSettingsBridge {
        @JavascriptInterface
        fun onSettingChanged(kindRaw: String?, valueRaw: String?, checked: Boolean) {
            val kind = kindRaw.orEmpty().trim().lowercase()
            val value = valueRaw.orEmpty().trim().lowercase()
            post {
                if (!isCurrentBraveSearchPage()) return@post
                when (kind) {
                    "safe" -> {
                        val normalized = when (value) {
                            SAFE_OFF -> SAFE_OFF
                            SAFE_STRICT -> SAFE_STRICT
                            else -> SAFE_MODERATE
                        }
                        prefs.edit().putString(KEY_SAFE_SEARCH, normalized).apply()
                    }
                    "country" -> if (value.isNotBlank()) {
                        prefs.edit().putString(KEY_SEARCH_COUNTRY, value).apply()
                    }
                    "language" -> if (value.isNotBlank()) {
                        prefs.edit().putString(KEY_SEARCH_LANGUAGE, value).apply()
                    }
                    "ai" -> prefs.edit().putBoolean(KEY_AI_ANSWERS, checked).apply()
                }
                applyBraveSearchPreferences()
            }
        }
    }

    private fun isCurrentBraveSearchPage(): Boolean {
        val host = runCatching { Uri.parse(webView.url.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
        return host == "search.brave.com" || host.endsWith(".search.brave.com")
    }

    private fun installBraveSearchSettingsBridge(view: WebView?, rawUrl: String?) {
        view ?: return
        val host = runCatching { Uri.parse(rawUrl.orEmpty()).host.orEmpty().lowercase() }.getOrDefault("")
        if (host != "search.brave.com" && !host.endsWith(".search.brave.com")) return

        val script = """
            (function() {
              if (window.__aiAdsBraveSelectBridgeInstalled) return true;
              window.__aiAdsBraveSelectBridgeInstalled = true;

              var activeOverlay = null;

              function textOf(el) {
                try {
                  var p = el;
                  for (var i = 0; i < 5 && p; i++, p = p.parentElement) {
                    var t = (p.innerText || p.textContent || '').toLowerCase();
                    if (t.length > 0 && t.length < 1200) return t;
                  }
                } catch (_) {}
                return '';
              }

              function inferKind(select) {
                var ctx = textOf(select);
                var vals = Array.prototype.map.call(select.options || [], function(o) {
                  return String(o.value || '').toLowerCase();
                });
                if (ctx.indexOf('penelusuran yang aman') >= 0 || ctx.indexOf('safe search') >= 0 ||
                    ctx.indexOf('konten eksplisit') >= 0 ||
                    (vals.indexOf('off') >= 0 && vals.indexOf('moderate') >= 0 && vals.indexOf('strict') >= 0)) {
                  return 'safe';
                }
                if (ctx.indexOf('wilayah') >= 0 || ctx.indexOf('region') >= 0 ||
                    ctx.indexOf('negara') >= 0 || ctx.indexOf('country') >= 0) return 'country';
                if (ctx.indexOf('bahasa') >= 0 || ctx.indexOf('language') >= 0) return 'language';
                var localeCount = vals.filter(function(v) { return /^[a-z]{2}[-_][a-z]{2}$/i.test(v); }).length;
                if (localeCount >= 2) return 'language';
                var regionCount = vals.filter(function(v) { return v === 'all' || /^[a-z]{2}$/i.test(v); }).length;
                if (regionCount >= 5) return 'country';
                return 'select';
              }

              function notify(kind, value, checked) {
                try {
                  if (window.AIAdsBraveSettings && window.AIAdsBraveSettings.onSettingChanged) {
                    window.AIAdsBraveSettings.onSettingChanged(String(kind || ''), String(value || ''), !!checked);
                  }
                } catch (_) {}
              }

              function closeOverlay() {
                if (activeOverlay && activeOverlay.parentNode) activeOverlay.parentNode.removeChild(activeOverlay);
                activeOverlay = null;
              }

              function openSelect(select) {
                closeOverlay();
                var options = Array.prototype.slice.call(select.options || []);
                if (!options.length) return;

                var overlay = document.createElement('div');
                overlay.id = '__ai_ads_brave_select_overlay';
                overlay.style.cssText = [
                  'position:fixed','inset:0','z-index:2147483647','background:rgba(0,0,0,.36)',
                  'display:flex','align-items:center','justify-content:center','padding:16px','box-sizing:border-box'
                ].join(';');

                var card = document.createElement('div');
                card.style.cssText = [
                  'width:min(94vw,680px)','max-height:88vh','overflow:auto','background:#29292c',
                  'border-radius:22px','box-shadow:0 12px 36px rgba(0,0,0,.45)','color:#f2f2f5',
                  'font:500 18px/1.25 system-ui,-apple-system,sans-serif','overscroll-behavior:contain'
                ].join(';');

                options.forEach(function(opt, index) {
                  var row = document.createElement('button');
                  row.type = 'button';
                  row.style.cssText = [
                    'width:100%','min-height:72px','display:flex','align-items:center','gap:12px',
                    'padding:0 20px','border:0','border-bottom:1px solid #47474b','background:#29292c',
                    'color:#f2f2f5','font:500 18px/1.25 system-ui,-apple-system,sans-serif',
                    'text-align:left','box-sizing:border-box'
                  ].join(';');

                  var label = document.createElement('span');
                  label.textContent = opt.textContent || opt.label || opt.value || '';
                  label.style.cssText = 'flex:1;white-space:normal;';

                  var radio = document.createElement('span');
                  radio.textContent = (index === select.selectedIndex || opt.selected) ? '◉' : '○';
                  radio.style.cssText = 'font-size:30px;line-height:1;color:' +
                    ((index === select.selectedIndex || opt.selected) ? '#c8d1ff' : '#e6e6ea') + ';';

                  row.appendChild(label);
                  row.appendChild(radio);
                  row.addEventListener('click', function(ev) {
                    ev.preventDefault();
                    ev.stopPropagation();
                    select.selectedIndex = index;
                    options.forEach(function(o, i) { o.selected = (i === index); });
                    var kind = inferKind(select);
                    notify(kind, String(opt.value || ''), true);
                    try { select.dispatchEvent(new Event('input', { bubbles:true })); } catch (_) {}
                    try { select.dispatchEvent(new Event('change', { bubbles:true })); } catch (_) {}
                    closeOverlay();
                  }, true);
                  card.appendChild(row);
                });

                overlay.appendChild(card);
                overlay.addEventListener('click', function(ev) {
                  if (ev.target === overlay) closeOverlay();
                }, true);
                document.documentElement.appendChild(overlay);
                activeOverlay = overlay;
              }

              function intercept(ev) {
                var target = ev.target;
                if (!target || !target.closest) return;
                var select = target.closest('select');
                if (!select) return;
                ev.preventDefault();
                ev.stopPropagation();
                if (ev.stopImmediatePropagation) ev.stopImmediatePropagation();
                openSelect(select);
              }

              document.addEventListener('pointerdown', intercept, true);
              document.addEventListener('mousedown', intercept, true);
              document.addEventListener('click', intercept, true);

              document.addEventListener('change', function(ev) {
                var target = ev.target;
                if (!target) return;
                if (target.tagName === 'SELECT') {
                  var option = target.options && target.options[target.selectedIndex];
                  notify(inferKind(target), option ? String(option.value || '') : String(target.value || ''), true);
                  return;
                }
                if (target.type === 'checkbox' || target.type === 'radio') {
                  var ctx = textOf(target);
                  if (ctx.indexOf('jawaban dari ai') >= 0 || ctx.indexOf('answer with ai') >= 0 ||
                      (ctx.indexOf('ai') >= 0 && ctx.indexOf('answer') >= 0)) {
                    notify('ai', target.value || '', !!target.checked);
                  }
                }
              }, true);

              return true;
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

'''
text = text.replace(marker, bridge + marker, 1)

p.write_text(text)
print("Applied Brave Search in-WebView select bridge root fix")
