# Ultraman Fight Evolution 0

**Version V1 · made by #ditz**

A standalone Android app for playing **Ultraman Fight Evolution 0**. Import your own copy of the game
once, tap **FIGHT**, and play with touch controls built for a fighting game. Nothing else needs to
be installed.

## What you get

- **Ultraman look**: deep navy, hero red and silver, with colour-timer blue light. Rising energy
  sparks over a slowly turning gem, light-ring buttons, and a spark on every tap. The logo is original.
- **New touch controls**, not a copy of a handheld's buttons: a glowing movement ring, round
  light-ring buttons for Attack, Strong attack, Jump and Guard, a gem-shaped Beam button, and slim
  Dash, Select and Pause bars. Move, resize, hide or remap any of them.
- **Resolution** from 360p to 4K, frame skip, anti-aliasing, screen fit and graphics API choice.
- **Live panel** with FPS, CPU and GPU. Drag it anywhere; tap it to shrink it.
- **Saves**: import and export as a zip, with backups of anything replaced.
- **Texture mods and mod packs**: import a zip from the app, or bundle one inside the APK.
- **Import your game** as a disc image file (.iso, .cso, .chd) or as an extracted game folder.
  Title and cover art are read automatically.
- **Tuned for MediaTek phones**: locks 60 Hz and keeps performance steady.
- **No menus in the way**: during a game, press Back twice to leave. Leaving the app pauses it.

## The controls

| Control | Default | Default button |
|---|---|---|
| Movement ring (bottom left) | glowing ring with a gem knob | analog stick (or D-pad) |
| Attack (big, right) | hold / tap | □ |
| Strong attack | tap | △ |
| Jump | tap | ✕ |
| Guard | hold | ○ |
| Beam (gem, far right) | tap / hold | R |
| Dash (top-left bar) | tap | L |
| Select, Pause (top middle) | tap | Select, Start |
| Menu pad (small, left) | for menus | D-pad |

**The button assignments are a best guess.** No reliable control list for this game was found.
Check the game's own controls screen, then use **Controls > Edit layout**, tap a control and pick
the button it should press under **Presses**. Changes save as you go, and **Save file** writes a
`layout.json` you can ship inside the APK (`overlay/assets/ui/layout.json`).

## Build the APK on GitHub

1. Create a new GitHub repository and upload everything in this folder.
2. Open **Actions → Build APK → Run workflow** (it also runs on pushes to `main`).
3. After roughly 30-60 minutes, download **UltramanFE0-apk** from the run page.
4. Install the APK (allow "install unknown apps" for your file manager or browser).

## Make it yours

```
overlay/assets/ui         background / poster / layout.json / icons (optional)
overlay/assets/textures   texture pack to bundle (optional)
overlay/assets/modpack    full modpack to bundle (optional)
overlay/branding          logo.jpg to replace the app icon (optional)
overlay/res/values        app name and colours
```

The app name is `app_name` in `overlay/res/values/ultrafe_values.xml`. Version and creator are
shown on the title screen and in **About**.

## Notes

- No game files are included. Supply your own legally obtained copy.
- Ultraman and related names belong to their owners. This is a fan project; keep it non-commercial.
  The logo is original.
- Open-source notices are in the app under **Licenses**. Technical and legal notes for whoever builds
  the APK are in `BUILDERS-NOTES.md`.
