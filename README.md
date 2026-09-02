# AI Ads Keyboard

Keyboard Android dengan OpenRouter/TabiAI, obrolan AI, akses teks layar sesuai permintaan pengguna, prediksi lokal, clipboard, emoji, simbol, Caps Lock dua ketukan, dan tinggi potret/lanskap terpisah.

## Fitur versi 0.11

- Nama aplikasi dan label bawah keyboard sudah seragam menjadi **AI Ads Keyboard**.
- Layanan **Akses Teks Layar** tersedia lagi. Isi layar dibaca saat pengguna menekan fungsi AI, bukan direkam terus-menerus; node sandi selalu dilewati.
- `Balas` memprioritaskan percakapan yang terlihat, mengabaikan tombol UI dan draf pengguna, kemudian menyesuaikan bahasa serta gaya pesan terbaru.
- `Terjemah` otomatis memakai teks yang disorot atau membaca postingan/chat layar saat tombol ditekan, tanpa perlu menempel teks. Semua bahasa sumber diterjemahkan ke Bahasa Indonesia; tombol dan label UI disaring.
- `Balas` memprioritaskan pesan masuk terakhir yang belum dibalas, dengan percakapan lain hanya sebagai konteks pendukung.
- `Perbaiki` dan `Ringkas` membaca ulang teks secara teliti agar hasilnya rapi, runtut, dan tidak mengubah fakta.
- `Perbaiki` dan `Ringkas` dapat memakai teks pilihan, tulisan panjang di editor, teks layar, teks yang dibagikan, clipboard, atau OCR sesuai konteks.
- Halaman pengaturan menerima hingga enam sumber URL HTTPS. URL pencarian dapat memakai placeholder `{query}`.
- Isi situs diperlakukan sebagai referensi tidak tepercaya dan tidak boleh mengubah instruksi AI.
- Saat prediksi aktif, bar koreksi mempertahankan ruangnya agar tombol tidak meloncat. Saat prediksi dimatikan dari pengaturan, bar beserta seluruh ruangnya dihapus.
- Enter membuat baris baru secara default. Aksi `Kirim/Selesai` dari aplikasi dapat diaktifkan sebagai opsi.
- Rentang tinggi potret `170–330 dp` dan lanskap `90–190 dp`, dengan migrasi otomatis dari batas minimum lama.
- Panel obrolan AI memakai lebar keyboard hingga ke tepi, area jawaban diperbesar, dan ruang kosong di bawah tombol tindakan dipotong.
- Tombol penutup keyboard di toolbar dihapus dan tombol pengaturan ditempatkan di ujung kanan.
- Obrolan AI dapat diperbesar menjadi layar penuh tanpa kehilangan keyboard untuk mengetik.
- Tema keyboard dapat memakai warna siap pakai, warna sendiri, atau foto dari galeri.
- Sensitivitas dapat diatur sampai `400%`; mulai `150%`, tombol merespons saat disentuh tanpa menunggu jari dilepas.
- Memori gaya lokal mempelajari pilihan kata, sapaan, singkatan, panjang kalimat, dan formalitas agar hasil AI mengikuti gaya pemakai. Fitur dapat dimatikan atau dihapus.

## Provider AI

- **OpenRouter** melalui Chat Completions, model bawaan `openrouter/free`.
- **TabiAI** melalui endpoint Anthropic Claude `/v1/messages`, model bawaan `claude-opus-5`.

API key, model, dan Base URL TabiAI disimpan terpisah. Fallback ke provider kedua tersedia dan mati secara default.

## Menjalankan

Setiap perubahan di cabang `main` dibangun oleh GitHub Actions. Buka tab **Actions**, pilih proses **Build APK**, lalu unduh artefak **AI-Ads-Keyboard-debug**.

Setelah APK dipasang:

1. Buka aplikasi lalu tekan **Aktifkan keyboard**.
2. Pilih **AI Ads Keyboard** sebagai keyboard utama.
3. Tekan **Aktifkan Akses Teks Layar** bila ingin `Balas` dan `Terjemah` membaca aplikasi yang sedang terbuka.
4. Pilih provider, masukkan API key dan model, lalu simpan.
5. Tambahkan URL sumber jika AI perlu mengambil referensi web.

## Catatan Aksesibilitas

Android dan aplikasi keamanan dapat mengetahui bahwa layanan Aksesibilitas aktif. Beberapa aplikasi bank mungkin menampilkan peringatan selama layanan ini menyala. Layanan tidak disamarkan; nonaktifkan sementara ketika memakai aplikasi yang menolaknya.

## Privasi

- Teks layar diambil saat fungsi AI ditekan dan dikirim hanya ke provider AI yang dipilih.
- Kolom sandi tidak dibaca, dipelajari, atau dimasukkan ke riwayat clipboard.
- Prediksi, email, frasa, memori gaya, konteks, dan API key disimpan lokal pada perangkat. Ringkasan gaya hanya dikirim saat fungsi AI digunakan.
- OCR dilakukan di perangkat memakai ML Kit.
- URL sumber dibatasi ke HTTPS publik; alamat lokal dan jaringan privat ditolak.
