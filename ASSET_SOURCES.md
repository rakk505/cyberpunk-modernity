# Asset provenance

## Animated advertising campaigns

`neon_skyline`, `chrome_cola`, and `orbital_air` are original audiovisual campaigns generated
from scratch by `tools/create_demo_ads.py`. `tools/generate_meta_ads.py` remains checked in for
reproducibility of the earlier procedural artwork.

The four silent campaigns `meta_logo`, `meta_glasses`, `meta_ai`, and `meta_future` use real Meta
advertisement videos. The project owner, a Meta employee, confirmed on 2026-08-04 that these assets
are authorized for inclusion in this integration. The prepared inputs derive from the following
source links and retain them here for an accurate audit trail:

- `meta_logo`: [Meta Logo Animation](https://youtu.be/7XrmfVIBweU), looped to 30 seconds.
- `meta_glasses`: [Meta Glasses Commercial #2 (2026)](https://youtu.be/Fpu1uAZPwF8), trimmed to 30 seconds.
- `meta_ai`: [Your personal AI. The new Meta AI app.](https://youtu.be/2l_2k5KrXmo), trimmed to 45 seconds.
- `meta_future`: [The future is for everyone.](https://youtu.be/JlRgRmAqoAc), trimmed to 45 seconds.

The corresponding inputs are `ads/meta_logo.mp4`, `ads/meta_glasses.mp4`, `ads/meta_ai.mp4`, and
`ads/meta_future.mp4`. The processor aspect-fits each source inside a 160x90 frame at 8 FPS and
verifies its hash before processing. Their SHA-256 hashes are respectively
`e865d609eec6d85f107f345c39da06c40e89b297cc1937d2ba67aaa3ea64cf66`,
`14125e81121180a7c42b540eabc813d3ecf63f22d1e0860119fe323ac5d87911`,
`4f7d6ab6c866d5e2023397a05f9c0d06077f67dcd47fda8add7fbb9f0070ecf2`, and
`3c25d6ecbd46567b8a25f6ae412bbe87b930c7a6c0f5049bc483df698a93a3b8`.

The project owner supplied `ads/misanthropic.mov` and `ads/closed_ai.mov` on 2026-08-04. They are
silent H.264 recordings used only for the correspondingly named parody campaigns. The processor
loops each source to 30 seconds, preserves its aspect ratio inside a 160x90 frame, and verifies the
source before processing. Their SHA-256 hashes are respectively
`ecef312c4f125e5c5e08a36579690dbf853a66841c274c24e8e20b7d1ab2cd9e` and
`0570dd81195c5cefd43e93114b87e5075defc9e6e0cb2c2edc52a4bb6f1a1799`. This records the supplied
provenance and deterministic inputs; it is not an independent license determination.

The five silent `highway` campaign clips were requested by the project owner on 2026-08-05 and
downloaded from the source links they supplied. Each source is shorter or longer than the clip
length, so the processor loops or trims it to exactly 30 seconds, aspect-fits it inside a 160x90
frame at 8 FPS against a black pad, and verifies its hash before processing:

- `vater`: [FREEDIO: "Water pouring into glass slow motion" royalty free HD stock video footage](https://youtu.be/rl5tNMpW--k), 20s source looped to 30 seconds.
- `gojo`: [Sound Re-Design: Domain Expansion: Infinite Void - \[Jujutsu Kaisen S2\]](https://youtu.be/HzCeyfNjSbo), 21s source looped to 30 seconds.
- `horizon`: [manga: "Horizon"\[edit\]](https://youtube.com/shorts/cT1A0Eharo4), 10s source looped to 30 seconds.
- `meta_logo_2`: [Meta logo animation](https://youtu.be/k-pBdWjVHBM), 7s source looped to 30 seconds.
- `petrochem`: [Cyberpunk 2077: Petrochem](https://www.youtube.com/shorts/EPCxeL5dOUQ), 58s source trimmed to 30 seconds.
- `eri`: [Eri](https://www.youtube.com/watch?v=PKEx03X3ydM), 74s source trimmed to 30 seconds.
- `hamburger`: [Hamburger](https://youtu.be/3iJNe0Huw3g), 18s source looped to 30 seconds.
- `soda`: [Soda](https://youtu.be/3lzuQ-yZ0aA), 50s source trimmed to 30 seconds.

The corresponding inputs are `ads/vater.mp4`, `ads/gojo.mp4`, `ads/horizon.mp4`,
`ads/meta_logo_2.mp4`, and `ads/petrochem.mp4`, whose SHA-256 hashes are respectively
`d2ece481ebd62dd45e1c90842d23644d523d08e51c3317087275278e27cfb8cf`,
`36d3fea88b23aad8efdcbf990387f555492cdfecb887c0f96fe96a5d861d04ef`,
`c7420259b558af7e7a1c86384e1990986f20de4c3cfad1cafc19aab8684b1bfc`,
`32a0d9c2df0c41762351205de85148e2e1c489eee6d50006c33a73a488e276e1`,
`4ffb04f95bdea2e3537bdb8dd648a2a77595a449f57819600098cc3dc582af0a`,
`04af3b167329d25c08871d9877cbe8ba6a6fcec04c5f6879aa547ce55aa8500b`,
`f2766ba3478ac3c644965b86ff92cfa1e2e76cb75a9b18e5eb52b66c39a082f0`, and
`de752641d28cdd7c19d460f07e735793aea5da445cdd034592c54a962b06ca97`. Their audio is discarded. As
above, this records the supplied provenance and deterministic inputs; it is not an independent
license determination, and these particular sources are third-party recordings rather than
owner-supplied or owner-authorized material.

## Freestanding advertising logos

`src/main/resources/assets/cyberdeck/textures/ad_logos/meta.png`, `closedai.png`, and
`misanthropic.png` are original parody brand cards drawn from scratch by
`tools/generate_ad_logos.py`. They contain no copied logo pixels or third-party artwork. The
deterministic standard-library-only generator remains checked in so every pixel can be reproduced
and audited. The separate `ad_logos` directory is intentionally outside the video-ad processor's
replaceable `textures/ads` output tree.

## Tactical corporate patrol skins

`src/main/resources/assets/cyberdeck/textures/entity/faction_enemy/tactical_0.png` through
`tactical_7.png`, plus the body-only Bulletproof Vest equipment textures under
`textures/entity/equipment/humanoid/`, are original project assets generated by
`tools/generate_tactical_skins.py`.
The project-owner-supplied tactical-team image was used only for broad outfit direction: muted
olive/charcoal uniforms, black load-bearing equipment, protective headwear, eye protection, and
restrained yellow accents. No source pixels, logos, or character designs are redistributed.

The source generator is deterministic, standard-library-only, and remains checked in so every
pixel can be reproduced and audited.

## R Corp paramilitary skins

`src/main/resources/assets/cyberdeck/textures/entity/faction_enemy/r_corp_0.png` through
`r_corp_7.png` are original project assets generated by `tools/generate_r_corp_skins.py`.
The project-owner-supplied tactical-team image was used only for broad outfit direction: gray
technical uniforms, orange hard-shell protection, black load-bearing equipment, protective
headwear, and compact optics. No source pixels, logos, text, likenesses, or character designs are
redistributed.

The eight skins are deterministic 64x64 RGBA wide-arm player textures drawn from scratch using only
Python's standard library. The checked-in generator preserves their reproducibility and makes each
pixel auditable.

## Corporate civilian skins

`src/main/resources/assets/cyberdeck/textures/entity/city_npc/corporate_0.png` through
`corporate_7.png` are original project assets generated by
`tools/generate_corporate_skins.py`. They do not copy or redistribute third-party player skins.
They are provided under the mod's `All Rights Reserved` terms recorded in `gradle.properties`.

The source generator is deterministic and uses only Python's standard library. It remains in the
repository so every pixel can be reproduced and audited.

## Project Moon map atlas

`src/main/resources/assets/cyberdeck/textures/gui/project_moon_map_atlas.png` is a deterministic
occupancy mask generated by `tools/generate_city_map_atlas.py` from the 17,920 audited Arnis
structure templates already shipped under `data/neoncity/arnis_districts/`. Building, surface,
vegetation, and water classes contain no copied map imagery. Their OpenStreetMap/Arnis source and
ODbL attribution remain recorded per atlas in the checked-in district catalog.

The new and replaced atlas pairs are reproducible from
`provenance/arnis_districts/perimeter_manifest.json` with
`tools/arnis/build_perimeter_atlases.py`. Nest/Backstreets sources are:

- District Æ: Oslo Bjørvika / Helsinki Punavuori.
- District Yi: Moscow New Arbat / Yekaterinburg Uralmash.
- District 王: Boston Newbury Street / Boston Fort Point.
- District Xi: Bangkok Ratchaprasong / Bangkok Yaowarat.
- District Ui: Singapore Raffles Place / Singapore Tiong Bahru.
- District Uang: Amsterdam Nine Streets / Amsterdam De Pijp.
- District Pon: Madrid Gran Vía / Lisbon Alfama.
- District Pok: Austin Congress Avenue / East Cesar Chavez.
- District Pak: Downtown Dubai / Al Fahidi.
- J Corp: Las Vegas Strip / Downtown Fremont.
- Q Corp: Fukuoka Tenjin / Daimyo.

The saved OpenStreetMap inputs, Arnis settings, bounding boxes, and SHA-256 audits live beside that
manifest under `provenance/arnis_districts/`. All use OpenStreetMap contributors under ODbL 1.0.

## U Corp container-port reference

The project-owner-supplied `cont.schem` (SHA-256
`bd50e9b8e7163bfe48a6403d6a30eb6426eb791bf4a02c4b3ba0fa00840fb6b4`) was inspected as a
visual and dimensional reference for U Corp's container terminal. It is a Sponge schematic v2 for
Minecraft 1.20.1 with ten 7x7x28 container modules arranged into four two- or three-level stacks.
The source contains no embedded author or license metadata.

No source geometry or NBT is distributed by the mod. In particular, its 518 shulker boxes, 512
patterned banners, and 186 blank signs were not imported. `UCorpPortGeneration` instead produces
deterministic, block-entity-free container shells, cranes, harbor terrain, and Portships from world
coordinates. The project-owner-supplied Portship concept image was used only as a composition and
density reference; no image pixels are included in the mod.

## Tactical Movement behavioral reference

The local file `tacticalmovement-1.0.0.jar` was inspected only to establish its technical scope.
It targets Minecraft 1.20.1 / Forge 47, is marked All Rights Reserved, and contains a two-key
client lean implementation rather than dash, slide, sprint, gunfight, or enemy animations.

No class, source, texture, model, animation, or other asset from that JAR is included here. The
tactical movement implementation in this repository is an original clean-room design for
Minecraft 26.2 / NeoForge, with server validation and synchronized procedural posing. The audited
reference JAR's SHA-256 is
`916442ff3bcfaa3a5fed6a639b34a139500f95095783ba2bdae69683877bcc38`.

## Exsilit park trees

The structures under `src/main/resources/data/neoncity/park_trees/structures/` are deterministic
Minecraft 26.2 conversions of the park-scale, logs-and-leaves-only models in
`exsilits-tree-repository.zip`, supplied by the project owner for this integration. The archive
credits Exsilit in its directory name but contains no embedded README or license file. Its SHA-256
is `c6e2eb17fcedc02ea658cac7f433a9b1736225de6fffd8a294ba8d3c8f95a592`.

`tools/import_exsilit_trees.py` records each source path, converted dimensions, block count, and
output hash in `data/neoncity/park_trees/catalog.json`. It excludes every schematic containing
non-tree blocks or exceeding the bounded park footprint; the runtime retains the source geometry
while selecting district-appropriate log and leaf blocks.

## Healing consumable HUD icons

`src/main/resources/assets/cyberdeck/textures/item/bounce_back.png` and `maxdoc.png` are 16x16
derivatives of the item images linked by the Cyberpunk Wiki pages for
[Bounce Back](https://cyberpunk.fandom.com/wiki/Bounce_Back) and
[MaxDoc](https://cyberpunk.fandom.com/wiki/MaxDoc). The canonical images were resolved through the
wiki's MediaWiki API and downloaded from:

- `BonesMcCoy70.png`: `https://static.wikia.nocookie.net/cyberpunk/images/a/a9/BonesMcCoy70.png/revision/latest?cb=20240502123051`
  (download SHA-256 `aa34f858c9c9116d8da7ad6f86de4e8cb681427b22a1db82e022adfe273b8ad8`).
- `FirstAidWhiff.png`: `https://static.wikia.nocookie.net/cyberpunk/images/9/9b/FirstAidWhiff.png/revision/latest?cb=20240502123053`
  (download SHA-256 `ecad9c985fa90038be8c7958fba8846b74a936eed85eaa33f9d11aaa1d492669`).

Both were processed with `pixelart_downsample.py -s 16 -c 10` from the pixelart-downsample skill.
The resulting SHA-256 hashes are
`d9271a14a4643715c626e74a58975261bbcfa0b394397e51e551b58fe339530a` (Bounce Back) and
`00bf77ea88690837a283b29e634ddc41d16802fc56d4b9c12930bbc0a3cdbb4f` (MaxDoc). These remain
derivative game artwork; distribution must follow the upstream artwork's applicable license and
fan-content terms.

## Park merchant truck

`src/main/resources/data/neoncity/structure/merchant_trucks/spudtruck.nbt` is a deterministic
Minecraft structure conversion of the project-owner-supplied `spudtruck.schem` WorldEdit file
(SHA-256 `09b20c8480aaa9f84e5a04af483ce08e65bff1aa0d2b1639d2735a723519d58a`).
The source file contains WorldEdit 7.3.10 / Fabric metadata but no embedded author or license.

`tools/import_spudtruck.py` crops the empty border to 14x8x7, preserves 298 occupied truck blocks,
and deliberately omits six stale wall signs, two obstructing counter shutters, and all block-entity
data so the merchant remains reachable from ground level. The reproducible output
metadata and SHA-256 are recorded in `data/neoncity/merchant_trucks/catalog.json`.

# Mainline character skins

The Jerry, Kaito Park, Selene Voss, Dr. Nadira Quill, Jax Renner, Warden
Hargrove, and Fog Mother textures are original deterministic pixel assets
generated by `tools/generate_mainline_skins.py` from the visual notes in the
bundled story catalog. They do not incorporate third-party artwork.
