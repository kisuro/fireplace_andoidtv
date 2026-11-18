# Fireplace TV

Sideloadable **Android TV / Google TV (Leanback)** ambient fireplace player.

- **Package:** `com.kisuro.fireplace.tv`
- **App label:** Fireplace
- **Current version:** `1.0.15` (versionCode 15)
- **Playback:** Media3 ExoPlayer (no YouTube chrome / ads)
- **Extraction:** NewPipe Extractor (live + VOD)

## Features

- D-pad carousel of 7 fireplaces (live + looping VOD)
- Fullscreen audio+video loop; LEFT/RIGHT switch fireplaces in place; Back returns to picker
- Live: HLS/DASH, URL refresh, AVC/HEVC preferred (MediaTek VP9 demoted)
- Quality: resolution-first up to 4K when available
- Leanback launcher banner + icon

## Build

Requires Android SDK (compileSdk 35) and JDK 17+.

```bash
./gradlew :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

### Debug signing (consistent)

Debug builds are signed with the project keystore so sideloads stay upgradeable:

| Field | Value |
| --- | --- |
| Keystore | `app/keystore/debug.keystore` |
| Store password | `android` |
| Key alias | `androiddebugkey` |
| Key password | `android` |

Configured in `app/build.gradle.kts` (`signingConfigs.debugConfig` → `buildTypes.debug`). Do not replace this keystore if you want `adb install -r` upgrades to keep working.

## Sideload (Google TV)

If an older build with package `com.fireplace.tv` is installed, uninstall it first (different package / signing key will conflict):

```bash
adb uninstall com.fireplace.tv
```

Then install the debug APK:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open **Fireplace** from the Apps row (Leanback launcher).

More detail: see Project install note if present (`docs/fireplace-tv-install.md` in the Cursor Project store). A prebuilt APK may also be available there as `media/fireplace-tv.apk` — this repository is **source-only** (APKs are gitignored).

## Notes / limitations

- YouTube may bot-check datacenter / VPN IPs; home TV Wi‑Fi is the intended environment.
- Geo-restricted or DRM-only streams will not play.
- Extractor can break when YouTube changes APIs until NewPipe Extractor is updated.
