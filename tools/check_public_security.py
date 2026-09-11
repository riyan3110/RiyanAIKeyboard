#!/usr/bin/env python3
"""Fail the PUBLIC build if high-risk Android capabilities are reintroduced.

This validates the public keyboard as a normal IME with a small exported surface. It does
not attempt to disguise the app or bypass security checks performed by other apps/sites.
"""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
NETWORK = ROOT / "app/src/main/res/xml/network_security_config.xml"
EXTRACTION = ROOT / "app/src/main/res/xml/data_extraction_rules.xml"
ANDROID = "{http://schemas.android.com/apk/res/android}"
errors: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


manifest_text = MANIFEST.read_text(encoding="utf-8")
root = ET.fromstring(manifest_text)
app = root.find("application")
if app is None:
    fail("AndroidManifest.xml tidak memiliki <application>.")
else:
    if app.get(ANDROID + "usesCleartextTraffic") != "false":
        fail("usesCleartextTraffic harus false pada PUBLIC.")
    if app.get(ANDROID + "allowBackup") != "false":
        fail("allowBackup harus false pada PUBLIC.")
    if app.get(ANDROID + "fullBackupContent") != "false":
        fail("fullBackupContent harus false pada PUBLIC.")
    if app.get(ANDROID + "networkSecurityConfig") != "@xml/network_security_config":
        fail("networkSecurityConfig PUBLIC harus aktif.")
    if app.get(ANDROID + "dataExtractionRules") != "@xml/data_extraction_rules":
        fail("dataExtractionRules PUBLIC harus aktif.")
    if app.get(ANDROID + "debuggable") == "true":
        fail("Aplikasi PUBLIC tidak boleh memaksa debuggable=true.")

    safe_browsing = False
    for meta in app.findall("meta-data"):
        if meta.get(ANDROID + "name") == "android.webkit.WebView.EnableSafeBrowsing":
            safe_browsing = meta.get(ANDROID + "value") == "true"
    if not safe_browsing:
        fail("WebView Safe Browsing harus diaktifkan.")

forbidden_permissions = {
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.PACKAGE_USAGE_STATS",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.SEND_SMS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
}
for node in root.findall("uses-permission"):
    name = node.get(ANDROID + "name", "")
    if name in forbidden_permissions:
        fail(f"Izin berisiko tidak boleh ada di PUBLIC: {name}")

if "android.permission.BIND_ACCESSIBILITY_SERVICE" in manifest_text:
    fail("BIND_ACCESSIBILITY_SERVICE tidak boleh ada di manifest PUBLIC.")
if "android.accessibilityservice.AccessibilityService" in manifest_text:
    fail("AccessibilityService tidak boleh didaftarkan di manifest PUBLIC.")

if app is not None:
    allowed_exported_activities = {
        ".KeyboardLauncherActivity",
        ".TextImportActivity",
    }
    for activity in app.findall("activity"):
        if activity.get(ANDROID + "exported") == "true":
            name = activity.get(ANDROID + "name", "")
            if name not in allowed_exported_activities:
                fail(f"Activity exported yang tidak diizinkan: {name}")

    allowed_exported_services = {".RiyanKeyboardService"}
    for service in app.findall("service"):
        if service.get(ANDROID + "exported") == "true":
            name = service.get(ANDROID + "name", "")
            if name not in allowed_exported_services:
                fail(f"Service exported yang tidak diizinkan: {name}")
            if name == ".RiyanKeyboardService" and service.get(ANDROID + "permission") != "android.permission.BIND_INPUT_METHOD":
                fail("RiyanKeyboardService harus dilindungi BIND_INPUT_METHOD.")

if not NETWORK.exists():
    fail("network_security_config.xml tidak ditemukan.")
else:
    try:
        network_root = ET.fromstring(NETWORK.read_text(encoding="utf-8"))
        base = network_root.find("base-config")
        if base is None or base.get("cleartextTrafficPermitted") != "false":
            fail("network_security_config harus menolak cleartext.")
        cert_sources = {node.get("src") for node in network_root.findall(".//certificates")}
        if cert_sources != {"system"}:
            fail("Trust anchor PUBLIC hanya boleh memakai sertifikat sistem.")
        if network_root.findall("domain-config") or network_root.findall("debug-overrides"):
            fail("PUBLIC tidak boleh memiliki pengecualian domain/debug pada network security config.")
    except ET.ParseError as exc:
        fail(f"network_security_config.xml tidak valid: {exc}")

if not EXTRACTION.exists():
    fail("data_extraction_rules.xml tidak ditemukan.")

if errors:
    print("PUBLIC security gate: GAGAL")
    for item in errors:
        print(f"- {item}")
    sys.exit(1)

print("PUBLIC security gate: OK")
print("- tidak ada AccessibilityService/overlay capability di manifest")
print("- cleartext HTTP diblokir dan hanya system trust anchors yang dipakai")
print("- backup/data extraction diblokir")
print("- exported component dibatasi ke launcher/share/IME yang diperlukan")
