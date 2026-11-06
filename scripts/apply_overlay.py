#!/usr/bin/env python3
"""Applies the Ultraman Fight Evolution 0 overlay onto a PPSSPP checkout.

    python3 scripts/apply_overlay.py <ppsspp_dir> [--app-id ID] [--abis a,b]

Every patch asserts its anchor text. If the upstream layout ever changes, the script
stops with a clear message instead of silently building something half-patched.
"""
import argparse
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OVERLAY = ROOT / "overlay"


def die(msg):
    print(f"[overlay] ERROR: {msg}", file=sys.stderr)
    sys.exit(1)


def log(msg):
    print(f"[overlay] {msg}")


def sub_exact(text, old, new, expected, label):
    n = text.count(old)
    if n != expected:
        die(f"{label}: expected {expected} match(es) of {old!r}, found {n}. "
            "The PPSSPP layout differs from what this overlay was written for.")
    return text.replace(old, new)


def sub_regex(text, pattern, repl, expected, label):
    new, n = re.subn(pattern, repl, text, flags=re.S)
    if expected is None:
        if n < 1:
            die(f"{label}: pattern not found")
    elif n != expected:
        die(f"{label}: expected {expected} match(es), found {n}")
    return new


def patch_gradle(android, app_id, abis):
    path = android / "build.gradle.kts"
    text = path.read_text(encoding="utf-8")

    # Own application id so it installs next to the regular PPSSPP. The Java namespace stays
    # org.ppsspp.ppsspp because the native code binds to those class names.
    text = sub_exact(text, 'applicationId = "org.ppsspp.ppsspp"',
                     f'applicationId = "{app_id}"', 2, "applicationId")

    # Adaptive-icon-only branding and a 2026-appropriate floor (Android 8.0+, Vulkan era).
    text = sub_regex(text, r"minSdk = 21", "minSdk = 26", None, "minSdk")

    abi_list = ", ".join(f'"{a}"' for a in abis)
    text = sub_regex(text, r"abiFilters\.addAll\(listOf\([^)]*\)\)",
                     f"abiFilters.addAll(listOf({abi_list}))", None, "abiFilters")

    # AGP 9 built-in Kotlin only compiles Kotlin from kotlin.directories (not java.directories).
    text = sub_exact(text, 'java.directories.add("src")',
                     'java.directories.add("src")\n\t\t\tkotlin.directories.add("kotlin")',
                     1, "java.directories")

    # Optional user art (assets/ui) and texture packs (assets/textures) ride along in the APK.
    text = sub_exact(text, 'assets.directories.add("../assets")',
                     'assets.directories.add("../assets")\n\t\t\tassets.directories.add("hero_assets")',
                     1, "assets.directories")

    path.write_text(text, encoding="utf-8")
    log(f"build.gradle.kts: applicationId={app_id}, minSdk=26, abis={abi_list}, Kotlin dir added")


def patch_manifest(android):
    path = android / "AndroidManifest.xml"
    text = path.read_text(encoding="utf-8")

    # The emulator activity stops being the app's front door; our launcher takes over.
    text = sub_regex(
        text,
        r'<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"\s*/>\s*'
        r'<category android:name="android\.intent\.category\.LAUNCHER"\s*/>\s*'
        r'<category android:name="android\.intent\.category\.LEANBACK_LAUNCHER"\s*/>\s*'
        r'</intent-filter>',
        "", 1, "MAIN/LAUNCHER intent-filter")

    # Independence from the PSP emulator: this app must not offer itself as an ISO/CSO opener,
    # must not be exported to other apps, and must not offer PPSSPP game shortcuts.
    m = re.search(r'<activity\s+android:name="\.PpssppActivity".*?</activity>', text, re.S)
    if not m:
        die("PpssppActivity block not found in AndroidManifest.xml")
    block = m.group(0)
    block2 = re.sub(r'<intent-filter>(?:(?!</intent-filter>).)*?android\.intent\.action\.VIEW.*?</intent-filter>',
                    "", block, count=1, flags=re.S)
    if block2 == block:
        die("PpssppActivity VIEW intent-filter not found")
    block3 = block2.replace('android:exported="true"', 'android:exported="false"', 1)
    if block3 == block2:
        die("PpssppActivity exported flag not found")
    text = text.replace(block, block3, 1)
    text = sub_regex(text, r'<activity\s+android:name="\.ShortcutActivity".*?</activity>', "", 1,
                     "ShortcutActivity")

    text = sub_exact(text, "<application",
                     '<application\n        android:name="com.ultrafe.launcher.HeroApp"',
                     1, "<application")

    # Run the emulator in its own process (see HeroApp for why). Everything that shares
    # static state with PpssppActivity has to live in that same process.
    text = sub_exact(text, 'android:name=".PpssppActivity"',
                     'android:name=".PpssppActivity"\n            android:process=":emu"',
                     1, "PpssppActivity process")
    for name in (".ImageResultProxyActivity", ".DocumentResultProxyActivity"):
        text = sub_exact(text, f'android:name="{name}"',
                         f'android:name="{name}"\n\t\t\tandroid:process=":emu"',
                         1, f"{name} process")
    # Only present on newer PPSSPP versions.
    receiver = 'android:name=".AchievementsHostOverrideReceiver"'
    if receiver in text:
        text = sub_exact(text, receiver, receiver + '\n\t\t\tandroid:process=":emu"',
                         1, "AchievementsHostOverrideReceiver process")

    snippet = (OVERLAY / "manifest_snippet.xml").read_text(encoding="utf-8").rstrip("\n")
    text = sub_exact(text, "</application>", snippet + "\n    </application>", 1, "</application>")

    path.write_text(text, encoding="utf-8")
    log("AndroidManifest.xml: launcher, emulator process split, Application class added")


def patch_java(android):
    path = android / "src" / "org" / "ppsspp" / "ppsspp" / "PpssppActivity.java"
    text = path.read_text(encoding="utf-8")

    # A clean exit entry point for the launcher and pause menu. Setting shuttingDown makes
    # onDestroy call NativeApp.shutdown(), exactly like PPSSPP's own Exit command.
    method = (
        "\t/** Added by Ultraman Fight Evolution 0: clean exit that also shuts the native core down. */\n"
        "\tpublic void heroQuit() {\n"
        "\t\tshuttingDown = true;\n"
        "\t\tfinish();\n"
        "\t}\n\n"
    )
    anchor = "\tpublic static boolean isVRDevice() {"
    text = sub_exact(text, anchor, method + anchor, 1, "PpssppActivity.isVRDevice anchor")
    if "private boolean shuttingDown" not in text:
        die("PpssppActivity.shuttingDown field not found")

    path.write_text(text, encoding="utf-8")
    log("PpssppActivity.java: heroQuit() added")


def patch_native(android):
    """Exposes the core's frame counters so the Kotlin FPS panel can show real numbers."""
    java = android / "src" / "org" / "ppsspp" / "ppsspp" / "NativeApp.java"
    text = java.read_text(encoding="utf-8")
    anchor = "public static native boolean isAtTopLevel();"
    text = sub_exact(text, anchor,
                     anchor + "\n\t// Added by Ultraman Fight Evolution 0: {vps, flips, actual fps} from the core.\n"
                     "\tpublic static native float[] heroStats();",
                     1, "NativeApp.isAtTopLevel anchor")
    java.write_text(text, encoding="utf-8")

    cpp = android / "jni" / "app-android.cpp"
    text = cpp.read_text(encoding="utf-8")
    text = sub_exact(text, '#include "Core/HLE/sceUsbGps.h"',
                     '#include "Core/HLE/sceUsbGps.h"\n#include "Core/HW/Display.h"',
                     1, "app-android.cpp include anchor")
    fn_anchor = ("extern \"C\" jboolean Java_org_ppsspp_ppsspp_NativeApp_isAtTopLevel(JNIEnv *env, jclass) {\n"
                 "\treturn NativeIsAtTopLevel();\n}\n")
    fn_new = (fn_anchor +
              "\n// Added by Ultraman Fight Evolution 0: lets the Kotlin FPS panel read the core's own counters.\n"
              "extern \"C\" jfloatArray Java_org_ppsspp_ppsspp_NativeApp_heroStats(JNIEnv *env, jclass) {\n"
              "\tfloat vps = 0.0f, flips = 0.0f, actual = 0.0f;\n"
              "\t__DisplayGetFPS(&vps, &flips, &actual);\n"
              "\tjfloat out[3] = { vps, flips, actual };\n"
              "\tjfloatArray arr = env->NewFloatArray(3);\n"
              "\tif (arr) {\n"
              "\t\tenv->SetFloatArrayRegion(arr, 0, 3, out);\n"
              "\t}\n"
              "\treturn arr;\n}\n")
    text = sub_exact(text, fn_anchor, fn_new, 1, "app-android.cpp isAtTopLevel anchor")
    cpp.write_text(text, encoding="utf-8")
    log("native: heroStats() JNI hook added (NativeApp.java + app-android.cpp)")


def patch_resources(android):
    res = android / "normal" / "res"
    if not res.is_dir():
        die(f"{res} not found")

    # Remove PPSSPP's own icons so none of its artwork ships under our name.
    for d in list(res.glob("mipmap-*")):
        shutil.rmtree(d)
    banner = res / "drawable-xhdpi" / "ic_banner.png"
    if banner.exists():
        banner.unlink()
    bg = res / "values" / "ic_launcher_background.xml"
    if bg.exists():
        bg.unlink()

    shutil.copytree(OVERLAY / "res", res, dirs_exist_ok=True)
    log("resources: icons, banner, theme and app name replaced")


def patch_icon(android):
    """Builds the launcher icon from overlay/branding/logo.jpg (or .png) when it exists."""
    logos = [p for p in sorted((OVERLAY / "branding").glob("logo.*"))
             if p.suffix.lower() in (".jpg", ".jpeg", ".png")]
    if not logos:
        log("icon: built-in logo (put your picture at overlay/branding/logo.jpg to use it)")
        return
    logo = logos[0]
    res = android / "normal" / "res"
    ext = ".png" if logo.suffix.lower() == ".png" else ".jpg"
    nodpi = res / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(logo, nodpi / ("ni_logo" + ext))

    # Foreground: the whole picture, sized to the visible part of the adaptive icon (2/3).
    (res / "drawable" / "ic_launcher_foreground.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<inset xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:drawable="@drawable/ni_logo"\n'
        '    android:inset="16.667%" />\n', encoding="utf-8")
    # A picture can't be recoloured, so no themed (monochrome) variant.
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        f = res / "mipmap-anydpi-v26" / name
        if f.exists():
            t = re.sub(r"\s*<monochrome[^>]*/>", "", f.read_text(encoding="utf-8"))
            f.write_text(t, encoding="utf-8")

    marker_dir = android / "hero_assets" / "ui"
    marker_dir.mkdir(parents=True, exist_ok=True)
    (marker_dir / "custom_logo.txt").write_text("custom logo in use\n", encoding="utf-8")
    log(f"icon: built from {logo.name}")


def copy_kotlin(android):
    shutil.copytree(OVERLAY / "kotlin", android / "kotlin", dirs_exist_ok=True)
    log("Kotlin sources copied to android/kotlin")


def copy_assets(android):
    src = OVERLAY / "assets"
    dst = android / "hero_assets"
    dst.mkdir(exist_ok=True)
    if src.is_dir():
        shutil.copytree(src, dst, dirs_exist_ok=True)
    # README placeholders only document the folders; they are not shipped.
    for r in dst.rglob("README*"):
        r.unlink()
    log("assets: ui/ and textures/ copied to android/hero_assets")


def drop_daemon_jvm(ppsspp):
    # Upstream pins a JetBrains JDK 21 download. CI already provides JDK 21, so skip the fetch.
    f = ppsspp / "gradle" / "gradle-daemon-jvm.properties"
    if f.exists():
        f.unlink()
        log("removed gradle-daemon-jvm.properties (using the installed JDK 21)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ppsspp_dir")
    ap.add_argument("--app-id", default="com.ditz.ultramanfe0")
    ap.add_argument("--abis", default="arm64-v8a")
    args = ap.parse_args()

    ppsspp = Path(args.ppsspp_dir).resolve()
    android = ppsspp / "android"
    if not (android / "build.gradle.kts").is_file():
        die(f"{android}/build.gradle.kts not found. Is {ppsspp} a PPSSPP checkout?")

    abis = [a.strip() for a in args.abis.split(",") if a.strip()]
    if not abis:
        die("no ABIs given")

    patch_gradle(android, args.app_id, abis)
    patch_manifest(android)
    patch_java(android)
    patch_native(android)
    patch_resources(android)
    copy_kotlin(android)
    copy_assets(android)
    patch_icon(android)
    drop_daemon_jvm(ppsspp)
    log("done")


if __name__ == "__main__":
    main()
