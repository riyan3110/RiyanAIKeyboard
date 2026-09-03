from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')

start = s.find('    private fun maybeRunPendingLensUpload(')
end = s.find('    private fun performSearchFromInput() {', start)
if start < 0 or end < 0:
    raise RuntimeError('Lens helper block not found after v6 patch')

helper = r'''    private fun maybeRunPendingLensUpload(webView: WebView, pageUrl: String) {
        if (pendingLensUploadRunning || pendingLensImageBase64.isBlank()) return
        val host = runCatching { Uri.parse(pageUrl).host.orEmpty().lowercase() }.getOrDefault("")
        if (host != "lens.google.com") return

        val image = pendingLensImageBase64
        val width = pendingLensImageWidth
        val height = pendingLensImageHeight
        if (width <= 0 || height <= 0) return

        pendingLensUploadRunning = true
        val imageJson = JSONObject.quote(image)

        // Follow Chromium's current Lens upload contract exactly: encoded_image is the only
        // multipart field. The upload metadata belongs in the v3/upload query string.
        // Using the old ep=ccm / empty-s request makes Lens fall back to its landing page.
        val script = """
            (function(){
              try {
                var b64=$imageJson;
                var raw=atob(b64);
                var bytes=new Uint8Array(raw.length);
                for(var i=0;i<raw.length;i++) bytes[i]=raw.charCodeAt(i);

                var blob=new Blob([bytes],{type:'image/jpeg'});
                var file=new File([blob],'camera.jpg',{type:'image/jpeg',lastModified:Date.now()});
                var transfer=new DataTransfer();
                transfer.items.add(file);

                var form=document.createElement('form');
                form.method='POST';
                form.enctype='multipart/form-data';
                form.style.display='none';

                var language=(navigator.language||'id').replace('_','-');
                var params=new URLSearchParams();
                params.set('ep','cntpubb');
                params.set('hl',language);
                params.set('st',String(Date.now()));
                params.set('cd','');
                params.set('re','df');
                params.set('s','4');
                params.set('vpw',String($width));
                params.set('vph',String($height));
                form.action='https://lens.google.com/v3/upload?'+params.toString();

                var input=document.createElement('input');
                input.type='file';
                input.name='encoded_image';
                input.accept='image/jpeg,image/png,image/webp';
                input.files=transfer.files;
                form.appendChild(input);

                document.body.appendChild(form);
                form.submit();
                return 'submitted:'+form.action;
              } catch(e) {
                return 'error:'+(e && e.message ? e.message : String(e));
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { rawResult ->
            val result = rawResult.orEmpty().trim('"').replace("\\u003A", ":")
            if (result.startsWith("error:")) {
                pendingLensUploadRunning = false
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
                val safe = android.text.TextUtils.htmlEncode(result.removePrefix("error:").take(180))
                webView.loadDataWithBaseURL(
                    "https://lens.google.com/",
                    """
                    <html><body style='font-family:sans-serif;padding:22px;color:#202124'>
                    <b>Upload gambar ke Google Lens gagal.</b><br><br>
                    <span style='color:#5f6368'>$safe</span><br><br>
                    Tekan kembali lalu Cari lagi.
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "utf-8",
                    null
                )
            } else if (result.startsWith("submitted:")) {
                // The form now owns the JPEG and the navigation. Keep the one-shot flag set so
                // the Lens result page cannot accidentally submit the same frame a second time.
                pendingLensImageBase64 = ""
                pendingLensImageWidth = 0
                pendingLensImageHeight = 0
            } else {
                // If WebView returned an unexpected result, allow a clean retry instead of leaving
                // the Cari button stuck.
                pendingLensUploadRunning = false
                scannerSearchButton?.text = "Cari"
                scannerSearchButton?.isEnabled = true
            }
        }
    }
'''

s = s[:start] + helper.rstrip() + '\n\n' + s[end:]
p.write_text(s, encoding='utf-8')
print('Applied v0.18 Lens v7: official Chromium Lens v3 upload parameters.')
