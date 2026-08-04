# Third-Party Audio

## Free Firearm Sound Library

- Source: [The Free Firearm Sound Library](https://opengameart.org/content/the-free-firearm-sound-library)
- Prepared archive: [Prepared SFX Library.7z](https://opengameart.org/sites/default/files/Prepared%20SFX%20Library.7z)
- Retrieved: 2026-08-03
- Archive SHA-256: `cc1ab5a99a0a365105c7c5dd783f4b0b1fe90938114d3ceec53856bfe005f7d6`
- License: [CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/)
- Original project statement: "Our team holds CC0 NO RIGHTS RESERVED for this library."

The files under `assets/cyberdeck/sounds/weapons/` are trimmed derivatives of the prepared
96 kHz, 24-bit stereo recordings. `tools/process_firearm_sfx.py` documents every source recording
and onset, downmixes it to positional mono, resamples to 48 kHz, normalizes the active transient,
applies short boundary fades, and uses `ffmpeg` to encode it as Ogg Vorbis.

| Game profile | Source recordings |
| --- | --- |
| Light pistol | Walther PPQ 9mm, Bersa .380, Ruger Mark III .22 |
| Heavy pistol | 1911 .45, Smith & Wesson 642 .38 Special |
| SMG | Carl Gustav M45 9mm, PPSh 7.62x25 |
| Rifle | AR-15 5.56, AK-47 7.62x39, SKS 7.62x39 |
| Shotgun | Benelli Nova, Charles Daly, Winchester Model 12; all 12 gauge |
| Sniper | Mosin-Nagant 7.62x54, Tikka .30-06, Savage 10 .300 Blackout |
