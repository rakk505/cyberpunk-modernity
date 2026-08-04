# Animated Advertising Displays

The current feature intentionally supports only one large `8x4` display. Small and medium
variants are deferred. Placement scans exactly 32 target/support cells once, rejects glass and
non-flat or obstructed walls, then creates one ticking/rendering anchor plus 31 inert light cells.

Source videos live in `ads/`. Each catalog entry must contain an audio track and last from 30 to
45 seconds. Minecraft does not decode MP4 video natively, so the checked-in runtime assets are
preprocessed to shared 4x4 sprite sheets at 160x90 and 8 frames per second. Audio is extracted to
mono streaming Ogg Vorbis and remains spatially attenuated at the display center.

```bash
python3 tools/create_demo_ads.py --ffmpeg /path/to/ffmpeg
python3 tools/process_ads.py --ffmpeg /path/to/ffmpeg
```

`process_ads.py` validates the duration, identifier, source file, audio stream, total frame count,
and maximum sheet budget before replacing generated assets.
