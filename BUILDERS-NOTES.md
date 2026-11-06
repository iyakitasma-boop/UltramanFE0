# Notes for whoever builds this APK (not part of the app description)

**Version V1 · creator #ditz**

## How it is built

The app's launcher, controls and branding live in `overlay/`. The workflow clones the open-source
PPSSPP v1.20.4 sources (GPLv2), applies the overlay with `scripts/apply_overlay.py` (it stops loudly
if a patch anchor is missing), and builds an arm64 release APK signed with the debug key that project
ships (fine for sideloading).

## Licence obligations

The game engine inside the APK is GPLv2. If you share the APK you must offer the source (this
repository plus PPSSPP's) and keep the notices in the in-app **Licenses** screen. That screen is the
one place the engine is named; it must stay.

## Things that could not be removed from the APK

All user-visible text has been written without handheld or emulator wording. What remains is internal
and needed by the engine:

- Java package names of the engine (`org.ppsspp.ppsspp`) and its internal class names.
- The engine's own data folder names inside the app's private storage (for example
  `Android/data/<app id>/files/PSP/...`). The engine looks for those exact names.
- Engine resources and language files that are never shown in this app's screens.
- A short on-screen note from the engine when an extracted game folder starts.

## Limits

- Not a native port: the game still runs through the engine.
- The default button mapping is a best guess (no reliable control list was found); every control
  is remappable in the editor.
- The patch script was tested against the real v1.20.4 sources and the Kotlin was syntax-checked, but
  nothing could be compiled or run on a device. The first CI run is the first real compile; the
  native FPS hook is a few lines in `app-android.cpp`. If the build fails, the log names the line.
- GPU load shows "--" on phones that don't let apps read it.
- The engine only renders at whole multiples of a base height, so 480p and 540p share a step.
- Folder import is untested on a device.
