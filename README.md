# Riyan AI Keyboard

Keyboard Android ringan dengan AI untuk memperbaiki tulisan, membuat balasan, mengubah gaya bahasa, meringkas, menerjemahkan, serta membaca konteks dari screenshot memakai OCR lokal. Hasil AI ditampilkan sebagai pratinjau singkat dan baru dimasukkan setelah bar hasil diketuk.

## Provider AI

Versi 0.2 mendukung dua provider yang dapat dipilih dari aplikasi:

- **OpenRouter** melalui endpoint Chat Completions, dengan model bawaan `openrouter/free`.
- **TabiAI** melalui endpoint Anthropic Claude `https://tabitoken.com/v1/messages`, dengan model bawaan `claude-opus-5`.

API key dan nama model disimpan terpisah. Base URL TabiAI dapat diubah. Fallback ke provider kedua tersedia sebagai pilihan dan mati secara default agar aplikasi tidak memakai layanan lain tanpa disadari saat terjadi timeout atau error.

## Menjalankan

Setiap perubahan di cabang `main` otomatis dibangun oleh GitHub Actions. Buka tab **Actions**, pilih proses **Build APK**, lalu unduh artefak **RiyanAIKeyboard-debug**.

Setelah APK dipasang:

1. Buka aplikasi lalu tekan **Aktifkan keyboard**.
2. Pilih **Riyan AI Keyboard** sebagai keyboard utama.
3. Pilih OpenRouter atau TabiAI sebagai provider AI.
4. Masukkan API key dan nama model, lalu tekan **Simpan pengaturan AI**.

## Format TabiAI

Integrasi TabiAI menggunakan:

- `POST /v1/messages`
- header `x-api-key`
- header `anthropic-version: 2023-06-01`
- respons teks dari array `content`

Kolom Base URL menerima `https://tabitoken.com`, alamat berakhiran `/v1`, atau endpoint lengkap berakhiran `/v1/messages`.

## Privasi

- Keyboard tidak mengambil screenshot atau membaca aplikasi lain diam-diam.
- Teks yang dipilih atau diketik hanya dikirim ketika tombol AI ditekan.
- OCR dilakukan di perangkat dengan ML Kit.
- API key disimpan lokal pada perangkat dan tidak dimasukkan ke source code.
- Untuk distribusi publik, sebaiknya gunakan backend proxy dan penyimpanan key terenkripsi.

## Keyboard versi 0.3

- Tata letak lima baris yang lebih besar dengan baris angka permanen.
- Simbol alternatif terlihat kecil pada tombol huruf dan dapat diketik dengan tekan lama.
- Panel simbol dan empat halaman emoji.
- Tombol hapus berada tepat di atas tombol Enter.
- Toolbar cepat untuk huruf, emoji, clipboard, dan pengaturan.
- Pengaturan tinggi keyboard, ukuran huruf, sensitivitas sentuhan, dan durasi tekan lama.

GitHub Actions menghasilkan APK debug yang bisa langsung dipasang untuk pengujian. Sebelum dipublikasikan ke Play Store, tambahkan pengujian berbagai aplikasi chat, enkripsi penyimpanan API key, kebijakan privasi, saran kata, dan autocorrect lokal.
