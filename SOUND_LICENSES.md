# Ambient sound licenses / attribution

Fireplace TV ships a separate ambient nature layer (independent of YouTube
fireplace audio). Bundled loops follow the same sourcing approach as
[Exhalune](https://github.com/kisuro/Exhalune)
(`Apps/iOS/Resources/BackgroundSounds/` + `Sources.json`).

## Source

| App sound | Label | Origin URL (SoundFX.ru) |
| --- | --- | --- |
| `ambient_rain.ogg` | Rain | https://soundfx.ru/_include/audio/zvd1.mp3 |
| `ambient_forest.ogg` | Forest | https://soundfx.ru/_include/audio/les1.mp3 |
| `ambient_thunderstorm.ogg` | Thunderstorm | https://soundfx.ru/_include/audio/shum-grozi-i-dojdia.mp3 |
| `ambient_stream.ogg` | Stream | https://soundfx.ru/_include/sounds/foreststream.mp3 |
| `ambient_wind.ogg` | Wind | https://soundfx.ru/_include/audio/les3.mp3 |
| `ambient_birds.ogg` | Birds | https://soundfx.ru/_include/audio/birds/The_Sounds_Of_Nature_-_3D_zvuki_Pticy_sverchki5.mp3 |

- **Site:** [SoundFX.ru](https://soundfx.ru/) — free online nature sounds (same library Exhalune uses for ambient loops).
- **Packing:** Short (~45s) OGG Vorbis loops in `app/src/main/res/raw/` for Android TV.
- **Machine-readable map:** `app/src/main/assets/ambient_sources.json`.

Rain / forest / thunderstorm / wind / birds match Exhalune’s SoundFX.ru entries.
Stream (mountain brook) is the same SoundFX.ru library; Exhalune does not ship a
stream loop, so Fireplace TV adds it for the river/stream requirement.

## App behavior (audio prefs)

- Ambient nature: **off by default**; user enables and picks one sound at a time.
- Fireplace crackle (YouTube stream audio): separate mute/unmute; independent of ambient.
- Both preferences persist across launches.
