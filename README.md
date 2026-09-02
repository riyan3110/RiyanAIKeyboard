# Riyan AI Keyboard

Keyboard Android dengan OpenRouter/TabiAI, panel obrolan AI, prediksi kata dan frasa, clipboard, emoji, simbol, Caps Lock dua ketukan, serta tinggi potret dan lanskap yang dapat diatur terpisah.

## Balasan otomatis

Tombol **Balas** dapat membaca teks percakapan yang terlihat tanpa menempel manual setelah **Akses Balasan Otomatis** diaktifkan dari pengaturan Android. Isi layar hanya dibaca saat tombol Balas ditekan, tidak disimpan di latar belakang, lalu dikirim ke provider AI yang dipilih pengguna.

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

- Layanan Akses Balasan Otomatis hanya membaca teks layar saat tombol **Balas** ditekan dan harus diaktifkan sendiri oleh pengguna.
- Teks yang dipilih atau diketik hanya dikirim ketika tombol AI ditekan.
- OCR dilakukan di perangkat dengan ML Kit.
- API key disimpan lokal pada perangkat dan tidak dimasukkan ke source code.
- Untuk distribusi publik, sebaiknya gunakan backend proxy dan penyimpanan key terenkripsi.

## Keyboard versi 0.5

- Tinggi potret dan lanskap disimpan terpisah dan dapat diubah langsung lewat tombol `↕`.
- Tata letak lima baris dengan baris angka opsional, simbol tekan lama, panel simbol, dan empat halaman emoji.
- Tombol hapus berada tepat di atas tombol Enter.
- Tombol hapus menghapus seluruh teks yang sedang disorot.
- Tombol tutup keyboard `⌄` berdiri sendiri pada toolbar.
- Panel clipboard menyimpan riwayat lokal, menyematkan klip, menempelkan klip, dan menghapus klip.
- Tombol AI membuka composer bergaya ChatGPT dengan area jawaban dan tombol memasukkan hasil.
- Prediksi kata/frasa muncul setelah 2 huruf dan mempelajari kata yang dipilih pengguna.
- Shift dua ketukan mengaktifkan Caps Lock.
- Tombol emoji di samping spasi dihapus; emoji tetap tersedia dari toolbar.
- Tombol Balas membaca percakapan yang terlihat melalui layanan aksesibilitas opsional.
- Pengaturan tambahan untuk suara, getaran, kapitalisasi, spasi tanda baca, titik spasi ganda, dan riwayat clipboard.

GitHub Actions menghasilkan APK debug yang bisa langsung dipasang untuk pengujian. Sebelum dipublikasikan ke Play Store, tambahkan pengujian berbagai aplikasi chat, enkripsi penyimpanan API key, kebijakan privasi, dan autocorrect lokal.
