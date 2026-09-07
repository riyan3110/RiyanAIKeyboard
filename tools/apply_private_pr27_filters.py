#!/usr/bin/env python3
from pathlib import Path

AICLIENT = Path("app/src/main/java/com/riyan/aikeyboard/AiClient.kt")
HORDE = Path("app/src/main/java/com/riyan/aikeyboard/AiHordeAlchemyVision.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    if old not in text:
        raise RuntimeError(f"private PR27 filter anchor not found: {label}")
    return text.replace(old, new, 1)


ai = AICLIENT.read_text(encoding="utf-8")

# Keep the newest v0.21.23+ Vision/Search capabilities (long multilingual prompts,
# product/book OCR, provider routing, Google default, etc). Only restore the adult
# filtering semantics used at the PR #27 baseline.
new_filter = (
    "FILTER PR27 (18+): untuk subjek yang jelas dewasa, istilah tubuh, pakaian, bentuk tubuh, atau gaya dewasa boleh disebut secara lugas bila memang terlihat dan relevan. "
    "Jika subjek tampak di bawah 18 tahun atau usia dewasanya tidak dapat dipastikan, gunakan deskripsi netral dan jangan membuat deskripsi seksual, sensual, atau eksplisit. "
    "Jangan menebak anatomi di balik pakaian: bila area tubuh tertutup pakaian, deskripsikan pakaian yang terlihat, bukan bagian tubuh seolah terbuka. "
    "Kata seperti terbuka atau telanjang hanya boleh dipakai bila kulit/bagian tersebut memang terlihat langsung tanpa tertutup pakaian. "
    "Ukuran atau bentuk tubuh boleh disebut dengan bahasa lugas bila benar-benar tampak jelas dan didukung evidence visual. "
    "Jangan mengarang tindakan seksual dari pose/ekspresi, jangan mengarang pose seksual, dan jangan mengarang bagian tubuh yang tidak terlihat. "
)
old_filter = (
    "FILTER 18+ WAJIB: istilah dewasa atau seksual hanya boleh dipakai bila subjek jelas orang dewasa dan cirinya memang tampak langsung. "
    "Jika subjek tampak di bawah 18 tahun atau usia dewasanya tidak bisa dipastikan, gunakan deskripsi netral dan jangan membuat deskripsi seksual, sensual, atau eksplisit. "
    "Jangan menebak anatomi di balik pakaian, jangan mengarang tindakan seksual dari pose/ekspresi, dan jangan mengarang bagian tubuh yang tidak terlihat. "
)
ai = replace_once(ai, old_filter, new_filter, "Vision adult-filter wording")

# PR #27 normalizer only hard-gated exposure/genital claims. Keep that behavior,
# extended to the current multilingual output so the newest language support remains.
old_claims = '''        val claimsThatNeedEvidence = setOf(
            "terbuka", "telanjang", "puting", "vulva", "vagina", "penis", "skrotum", "anus", "klitoris",
            "nude", "naked", "topless", "nipple", "nipples", "breast", "breasts", "scrotum", "clitoris",
            "wuda"
        )'''
new_claims = '''        val claimsThatNeedEvidence = setOf(
            "terbuka", "telanjang", "puting", "vulva", "vagina", "penis", "skrotum", "anus", "klitoris",
            "nude", "naked", "topless", "nipple", "nipples", "vulva", "vagina", "penis", "scrotum", "anus", "clitoris",
            "wuda"
        )'''
ai = replace_once(ai, old_claims, new_claims, "PR27 evidence-gated claims")

# Chat filtering at the PR #27 baseline is already present in the current source.
# Assert it explicitly so a future patch cannot silently replace it in this private build.
chat_markers = [
    "Untuk konteks dewasa (18+), gunakan bahasa Indonesia sehari-hari yang lugas, santai, dan apa adanya",
    "Untuk orang yang jelas dewasa, jangan menyamarkan kata tubuh biasa",
    "jangan membuat deskripsi seksual tentang anak atau orang yang usianya tidak jelas",
]
for marker in chat_markers:
    if marker not in ai:
        raise RuntimeError(f"PR27 chat filter marker missing: {marker}")

# Verify the newest non-filter behavior is still present after the filter-only override.
latest_markers = [
    "Untuk PRODUCT, dukung SEMUA jenis produk",
    "Untuk TEXT/DOCUMENT/BOOK, baca teks yang terlihat seteliti mungkin",
    "PROMPT PENCARIAN harus PANJANG, RAPI, natural, dan informatif",
    "BAHASA PROMPT wajib mengikuti bahasa utama yang terlihat pada teks di gambar/OCR",
]
for marker in latest_markers:
    if marker not in ai:
        raise RuntimeError(f"latest Vision/Search feature marker missing after PR27 filter restore: {marker}")

AICLIENT.write_text(ai, encoding="utf-8")

# AI Horde still carries PR #27's age-unclear and clothing-grounding checks. Verify
# those remain intact after the newer long-prompt expansion patch.
horde = HORDE.read_text(encoding="utf-8")
for marker in [
    'if (ageUnclear) return ""',
    'val hasCoveringClothing = clothing.contains("celana")',
    'if (!ageUnclear && !hasCoveringClothing)',
]:
    if marker not in horde:
        raise RuntimeError(f"PR27 AI Horde filter marker missing: {marker}")

print("Applied PR27-style chat/vision filters only; newest non-filter features preserved")
