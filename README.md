# Media Booster for OnePlus

Keeps your recently used media players in the OnePlus / ColorOS quick settings media panel, even after the apps were closed or killed in the background.

- The media panel has 3 places (ColorOS limit). Players that are open in their app take a place first, from any app (browsers too).
- Free places are filled with stand-in cards from a history of the last 5 used apps, most recent first. Only apps you allow ("Apps to remember") get stand-ins.
- A stand-in shows the last title, artist and cover. Controls are forwarded to the real app.
- A stand-in hides while the real app's own card is on screen, and takes over after the system times out the paused player (~5 min on ColorOS, counted from the app's last notification update).
- Cards refresh every 2 minutes so the system does not time them out.

## Install

You need a computer with **adb** once. Android blocks notification access for apps installed by tapping an APK, and Media Booster cannot work without it.

### What you need

- Android 12+ (built and tested on OnePlus / ColorOS 16)
- Android Platform-Tools (`adb`) on your computer: [download](https://developer.android.com/tools/releases/platform-tools), or `brew install android-platform-tools` / `winget install Google.PlatformTools` / `sudo apt install adb`
- USB debugging on your phone: Settings → About phone → tap **Build number** 7 times, then Settings → System → Developer options → **USB debugging**

### 1. Install the 4 APKs

From the [latest release](https://github.com/Intersebbtor/media-booster/releases/latest) download:

- `MediaBooster-x.y.z-release.apk` (the app)
- `slot-slot1-release.apk`, `slot-slot2-release.apk`, `slot-slot3-release.apk` (one per place in the media panel)

Connect your phone, allow USB debugging on the phone, then in the download folder:

```bash
adb install -r MediaBooster-1.0.0-release.apk
```

```bash
adb install -r slot-slot1-release.apk
```

```bash
adb install -r slot-slot2-release.apk
```

```bash
adb install -r slot-slot3-release.apk
```

### 2. Set it up in the app

Open **Media Booster**. The *Setup* card lists what is missing:

1. **Player slots → Activate.** Confirm "Allow notifications" for each slot if asked.
2. **Notification access → Grant**, switch Media Booster on.
3. **Notifications → Allow.**
4. **Battery → Allow** (recommended, OnePlus kills background apps otherwise).

When it says **✓ All set**, tick the apps to remember under *Apps to remember* (search works for all apps).

### 3. Use it

Pull down quick settings and tap the media tile to open the player panel, then swipe through the 3 places. You can turn USB debugging off again.

### Why slot apps?

ColorOS groups media cards by app, so several cards from one app would all show the same content. Each slot is an empty shell with its own package name. It never runs except for the one-time activation, where it allows Media Booster to post its card. No extra background processes.

## Build from source

```bash
./gradlew :app:assembleRelease :slot:assembleRelease
```

Outputs: `app/build/outputs/apk/release/` and `slot/build/outputs/apk/slot{1,2,3}/release/`.
