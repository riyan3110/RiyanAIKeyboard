from pathlib import Path

p = Path('app/src/main/java/com/riyan/aikeyboard/RiyanKeyboardService.kt')
s = p.read_text(encoding='utf-8')

start = s.find('    private fun maybeRunPendingLensUpload(')
end = s.find('    private fun performSearchFromInput() {', start)
if start < 0 or end < 0:
    raise RuntimeError('embedded Lens helper block not found after v5 patch')

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

        // Do not use fetch() here. Lens' upload response is a navigation/redirect flow and WebView
        // may leave fetch on the Lens landing page because of CORS/redirect handling. Build a real
        // multipart HTML form, attach the camera JPEG as an actual File, and submit the form. This
        // is the same browser navigation used by Lens upload clients, so the uploaded pixels,
        // cookies, redirect and final Visual Matches result all stay inside this one WebView.
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
                form.action='https://lens.google.com/v3/upload?ep=ccm&s=&hl=id&st='+Date.now();
                form.enctype='multipart/form-data';
                form.style.display='none';

                var fileInput=document.createElement('input');
                fileInput.type='file';
                fileInput.name='encoded_image';
                fileInput.files=transfer.files;
                form.appendChild(fileInput);

                var dimensions=document.createElement('input');
                dimensions.type='hidden';
                dimensions.name='processed_image_dimensions';
                dimensions.value='$width,$height';
                form.appendChild(dimensions);

                var source=document.createElement('input');
                source.type='hidden';
                source.name='sbisrc';
                source.value='browser';
                form.appendChild(source);

                document.body.appendChild(form);
                form.submit();
                return 'submitted';
              } catch(e) {
                return 'error:'+(e && e.message ? e.message : String(e));
              }
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { rawResult ->
            val result = rawResult.orEmpty().trim('"').replace("\\u003A", ":")
            if (result.startsWith("error:")) {
                pendingLensUploadRunning = false
                val safe = android.text.TextUtils.htmlEncode(result.removePrefix("error:").take(160))
                webView.loadDataWithBaseURL(
                    "https://lens.google.com/",
                    """
                    <html><body style='font-family:sans-serif;padding:22px;color:#202124'>
                    <b>Gambar belum berhasil dikirim ke Google Lens.</b><br><br>
                    <span style='color:#5f6368'>$safe</span><br><br>
                    Tekan tombol kembali lalu Cari lagi.
                    </body></html>
                    """.trimIndent(),
                    "text/html",
                    "utf-8",
                    null
                )
            } else {
                // The browser now owns the multipart POST. Free the large Base64 copy; the form
                // already contains its own File/Blob and will continue through Lens redirects.
                pendingLensImageBase64 = ""
                pendingLensImageWidth = 0
                pendingLensImageHeight = 0
            }
        }
    }
'''

s = s[:start] + helper.rstrip() + '\n\n' + s[end:]
p.write_text(s, encoding='utf-8')
print('Applied v0.18 Lens v6: real multipart form submit from embedded WebView.')
