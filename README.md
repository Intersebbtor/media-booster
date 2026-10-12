# Player Pin (prototype)

Keeps chosen media players in the system media controls (quick settings), even after the app was killed.

- A pinned app without its own visible player gets a stand-in card: last title, artist and cover, with controls forwarded to the real app.
- The stand-in hides while the real app plays, and takes over after the system times out the paused player (~5 min on ColorOS).
- Cards refresh every 2 minutes so the system does not time them out.

## Slot shells

Some system UIs (ColorOS) group media cards by app package, so several cards from one app show the same content. The `slot` module builds empty shells (`...playerpin.slot1` to `slot4`) that delegate their notifications to Player Pin. Each card is posted under its own slot package. The shells never run except for a one-time activation.

## Build and install

```bash
./gradlew :app:assembleRelease :slot:assembleRelease
adb install -r app/build/outputs/apk/release/PlayerPin-1.0.0-release.apk
adb install -r slot/build/outputs/apk/slot1/release/slot-slot1-release.apk
adb install -r slot/build/outputs/apk/slot2/release/slot-slot2-release.apk
adb install -r slot/build/outputs/apk/slot3/release/slot-slot3-release.apk
```

Then open Player Pin, grant notification access and tap Save. This activates the slots.
