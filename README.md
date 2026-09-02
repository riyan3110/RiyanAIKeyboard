# Riyan AI Keyboard

Keyboard Android dengan OpenRouter/TabiAI, panel obrolan AI, prediksi kata dan frasa, clipboard, emoji, simbol, Caps Lock dua ketukan, serta tinggi potret dan lanskap yang dapat diatur terpisah.

## Balasan otomatis

Tombol **Balas** memakai pesan terbaru yang disalin ke clipboard. Pengguna cukup menyalin pesan, membuka kolom balasan, lalu menekan **Balas** tanpa menempelkan teks. Versi 0.6 tidak mendaftarkan layanan Aksesibilitas, sehingga tidak memicu peringatan aplikasi bank yang disebabkan oleh layanan tersebut.

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

- Aplikasi tidak mendaftarkan atau meminta layanan Aksesibilitas.
- Clipboard hanya dibaca ketika pengguna membuka clipboard atau menekan tombol **Balas**.
- Teks yang dipilih atau diketik hanya dikirim ketika tombol AI ditekan.
- OCR dilakukan di perangkat dengan ML Kit.
- API key disimpan lokal pada perangkat dan tidak dimasukkan ke source code.
- Prediksi, email, dan frasa yang dipelajari disimpan lokal; kolom sandi serta editor yang melarang pembelajaran tidak direkam.
- Untuk distribusi publik, sebaiknya gunakan backend proxy dan penyimpanan key terenkripsi.

## Keyboard versi 0.6

- Tinggi potret dan lanskap disimpan terpisah dan dapat diubah langsung lewat tombol `↕`; rentang lanskap lebih rendah, 135–220 dp.
- Tata letak lima baris dengan baris angka opsional, simbol tekan lama, panel simbol, dan empat halaman emoji.
- Tombol hapus berada tepat di atas tombol Enter.
- Tombol hapus menghapus seluruh teks yang sedang disorot.
- Tombol tutup keyboard `⌄` berdiri sendiri pada toolbar.
- Panel clipboard menyimpan riwayat lokal, menyematkan klip, menempelkan klip, dan menghapus klip.
- Tombol AI membuka composer bergaya ChatGPT dengan area jawaban dan tombol memasukkan hasil.
- Prediksi dan koreksi otomatis muncul hanya saat relevan setelah 2–3 huruf, lalu tersembunyi otomatis.
- Email, kata, dan frasa yang sering dipakai dipelajari secara lokal dengan peringkat frekuensi.
- Email atau kalimat tertentu dapat disimpan manual dari halaman pengaturan.
- Shift dua ketukan mengaktifkan Caps Lock.
- Tombol emoji di samping spasi dihapus; emoji tetap tersedia dari toolbar.
- Tombol Balas membaca pesan yang disalin tanpa perlu menempelkannya dan tanpa layanan Aksesibilitas.
- Pengaturan tambahan untuk suara, getaran, kapitalisasi, spasi tanda baca, titik spasi ganda, dan riwayat clipboard.

GitHub Actions menghasilkan APK debug yang bisa langsung dipasang untuk pengujian. Sebelum dipublikasikan ke Play Store, tambahkan pengujian berbagai aplikasi chat, enkripsi penyimpanan API key, kebijakan privasi, dan autocorrect lokal.
