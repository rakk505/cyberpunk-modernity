# Animated Advertising Displays

The current feature intentionally supports only large displays. Small and medium variants are
deferred. Manual placement uses an `8x4` panel. Generated Arnis facades use an offline catalog of
the largest eligible rectangle (5-16 blocks wide and 3-9 blocks high) in each source tile.

`tools/advertising/find_large_ad_surfaces.py` reads the audited structure NBTs, finds exposed solid
wall components, prefers rectangles containing sea lanterns, glowstone, or another full light
block, rejects any candidate rectangle containing glass, and emits deterministic placements.
Runtime generation performs only a catalog lookup and final world-state validation.

Source videos live in `ads/`. Each catalog entry must contain an audio track and last from 30 to
45 seconds. Minecraft does not decode MP4 video natively, so the checked-in runtime assets are
preprocessed to shared 4x4 sprite sheets at 192x108 and 8 frames per second. Audio is extracted to
mono streaming Ogg Vorbis and remains spatially attenuated at the display center.

```bash
python3 tools/process_ads.py --ffmpeg /path/to/ffmpeg
python3 tools/advertising/find_large_ad_surfaces.py
```

`process_ads.py` validates the MP4 metadata, duration, identifier, audio stream, total frame count,
and maximum sheet budget before replacing generated assets. Source and edit provenance is recorded
in `THIRD_PARTY_VIDEO.md` and `ads/catalog.json`.
