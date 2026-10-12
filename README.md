# Media Booster (prototype)

Keeps your recently used media players in the OnePlus / ColorOS quick settings media panel, even after the apps were closed or killed in the background.

- The media panel has 3 places (ColorOS limit). Players that are open in their app take a place first, from any app (browsers too).
- Free places are filled with stand-in cards from a history of the last 5 used apps, most recent first. Only apps you allow ("Apps to remember") get stand-ins.
- A stand-in shows the last title, artist and cover. Controls are forwarded to the real app.
- A stand-in hides while the real app's own card is on screen, and takes over after the system times out the paused player (~5 min on ColorOS, counted from the app's last notification update).
- Cards refresh every 2 minutes so the system does not time them out.

## Slot shells

Some system UIs (ColorOS) group media cards by app package, so several cards from one app show the same content. The `slot` module builds empty shells (`...mediabooster.slot1` to `slot4`) that delegate their notifications to Media Booster. Each card is posted under its own slot package. The shells never run except for a one-time activation.

## Build and install

```bash
./gradlew :app:assembleRelease :slot:assembleRelease
adb install -r app/build/outputs/apk/release/MediaBooster-1.0.0-release.apk
adb install -r slot/build/outputs/apk/slot1/release/slot-slot1-release.apk
adb install -r slot/build/outputs/apk/slot2/release/slot-slot2-release.apk
adb install -r slot/build/outputs/apk/slot3/release/slot-slot3-release.apk
```

Then open Media Booster, grant notification access and tap Save. This activates the slots.
