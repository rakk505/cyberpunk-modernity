# Fixed-Seed Gig Site Catalog

This report records the deterministic Arnis building scan and merge for fixed
content seed `50520260801`.

## Catalog Identity

| Field | Value |
|---|---|
| Format | `1` |
| Content seed | `50520260801` |
| Layout seed | `1033141802736436675` |
| Generator | `project-moon-megacity-v22-district-ring-fixed-seed-20260801` |
| Districts | `35` |
| Raw verified candidates | `286` |
| Initial worker merge | `274` |
| Final retained descriptors | `263` |
| Required minimum per district | `5` |
| Observed minimum per district | `6` |
| Deficient districts | None |
| Installed resource | `src/main/resources/data/neoncity/missions/gig_sites_50520260801.dat` |
| Resource SHA-256 | `58aa062f403ba2cbd228c27413a4062444f6d1e158538a6986557086f093dfc6` |

All retained descriptors are structural markers. Furniture, cover, corridors,
canisters, and turret slots are planned only after contract selection.

## Isolated Workers

Each worker used its own Modernity sandbox, world, ports, run directory,
Gradle home, logs, and shard artifact. Districts were sequential inside a
worker; the three workers ran concurrently. Every server flushed and stopped
cleanly before merge.

| Worker | Assigned districts | Retained | Shard SHA-256 |
|---|---|---:|---|
| 1 | A, D, G, J, M, P, S, V, Y, YI, UI, POK | 93 | `10d1cf1c698929d9e6695c3745696b93e7db29164e8b2d5b840baec28bbfb0b4` |
| 2 | B, E, H, K, N, Q, T, W, Z, WANG, UANG, PAK | 95 | `97c4b1691f0d29bdc2a7646eb90f10f0347932f2d57810fc7bcd7646e7b4489a` |
| 3 | C, F, I, L, O, R, U, X, AE, XI, PON | 86 | `ee800132e6aff43a48d487d0fa7416f8bdef03c98d24e4b34814b07ab50f2ec4` |

## District Results

`Raw` is the number accepted by the initial district scan. `Kept` is the initial
deterministic per-district export, capped at eight. Rejections list only nonzero reasons.
All districts had zero wrong-district, wrong-door-height, and wrong-floor-height
rejections.

| District | Raw | Kept | Regions | Buildings | Ready | Structural rejections | Content filters |
|---|---:|---:|---:|---:|---:|---|---|
| A | 8 | 8 | 6 | 50 | 44 | no entrance 6 | none |
| B | 9 | 8 | 2 | 24 | 22 | no floor window 2 | none |
| C | 8 | 8 | 13 | 88 | 84 | no floor window 4 | none |
| D | 5 | 5 | 24 | 88 | 61 | no floor window 21; no entrance 6 | mainline 1 |
| E | 8 | 8 | 4 | 65 | 44 | no entrance 21 | none |
| F | 9 | 8 | 22 | 49 | 46 | no floor window 2; no entrance 1 | none |
| G | 9 | 8 | 14 | 76 | 56 | no entrance 20 | mainline 40 |
| H | 8 | 8 | 2 | 24 | 20 | no entrance 4 | overlap 2 |
| I | 8 | 8 | 5 | 57 | 30 | no entrance 27 | overlap 2 |
| J | 8 | 8 | 9 | 50 | 45 | no entrance 5 | none |
| K | 8 | 8 | 5 | 46 | 34 | no entrance 12 | none |
| L | 7 | 7 | 24 | 77 | 73 | no entrance 4 | none |
| M | 8 | 8 | 13 | 62 | 49 | no floor window 2; no entrance 11 | none |
| N | 7 | 7 | 24 | 151 | 121 | no floor window 3; no entrance 27 | none |
| O | 8 | 8 | 8 | 117 | 70 | no entrance 47 | mainline 1 |
| P | 9 | 8 | 11 | 67 | 49 | no floor window 8; no entrance 10 | overlap 1 |
| Q | 8 | 8 | 3 | 9 | 9 | none | none |
| R | 9 | 8 | 7 | 50 | 42 | no entrance 8 | overlap 8 |
| S | 8 | 8 | 9 | 37 | 37 | none | none |
| T | 8 | 8 | 8 | 35 | 33 | no floor window 2 | none |
| U | 8 | 8 | 10 | 33 | 33 | none | overlap 2 |
| V | 13 | 8 | 3 | 53 | 50 | no entrance 3 | overlap 10 |
| W | 8 | 8 | 18 | 44 | 27 | no floor window 5; no entrance 12 | none |
| X | 8 | 8 | 11 | 83 | 18 | no floor window 15; no entrance 50 | overlap 3 |
| Y | 8 | 8 | 14 | 73 | 53 | no entrance 20 | none |
| Z | 8 | 8 | 3 | 28 | 24 | no entrance 4 | none |
| AE | 7 | 7 | 24 | 141 | 93 | no floor window 29; no entrance 19 | overlap 1 |
| YI | 8 | 8 | 16 | 98 | 87 | no floor window 2; no entrance 9 | none |
| WANG | 9 | 8 | 3 | 72 | 63 | no floor window 2; no entrance 7 | overlap 14 |
| XI | 8 | 8 | 3 | 12 | 10 | no entrance 2 | none |
| UI | 8 | 8 | 15 | 70 | 50 | no entrance 20 | none |
| UANG | 8 | 8 | 3 | 52 | 36 | no floor window 8; no entrance 8 | overlap 2 |
| POK | 9 | 8 | 5 | 64 | 64 | none | overlap 2 |
| PAK | 8 | 8 | 18 | 227 | 130 | no floor window 17; no entrance 80 | overlap 12 |
| PON | 8 | 8 | 3 | 45 | 39 | no entrance 6 | overlap 4 |

## Post-Merge Live Audit

The initial 274 descriptors were installed into isolated fixed-seed worlds and
audited after their Arnis neighborhoods had fully generated. D, P, and X were
rebuilt from the same seed after cross-chunk facade completion exposed stale
entrances. Structural failures in districts with sufficient remaining capacity
were pruned. No interior-only failure remains in the final artifact.

Final counts by district:

```text
A=6 B=8 C=8 D=6 E=8 F=8 G=8 H=7 I=8 J=8 K=8 L=7 M=7 N=6
O=8 P=7 Q=6 R=8 S=8 T=8 U=8 V=8 W=8 X=8 Y=8 Z=8 AE=7 YI=8
WANG=8 XI=8 UI=6 UANG=8 POK=8 PAK=8 PON=6
```

Final replacement shards were D=`6`, P=`7`, and X=`8`. The fresh bundled-world
plan audit passed D `6/6`, P `7/7`, and X `8/8`, with Arnis scan counters
remaining `0->0`. The other pruned districts passed their full retained sets:
A `6/6`, H `7/7`, M `7/7`, N `6/6`, Q `6/6`, Ui `6/6`, and Pon `6/6`.

Descriptors excluded after finalized-world DFS validation:

```text
a:12:-16:b37dc067b7364793
a:3:16:aa9466be4733820e
h:-34:-143:17a28a893613a62a
m:19:-184:e7c68eb89fd4f2f8
n:114:165:31be7c539663b491
q:119:-34:66383b7345060be7
q:136:-34:bdc25a398c1f9a8e
ui:148:277:c403dc2dfd1df108
ui:148:278:86ff24091402554
pon:-279:-161:255ec2e81df6bf7d
pon:-280:-170:975ce273bfadf1d1
p:-85:97:d5734ce17601dd4d
```

The final one-file export and merge retained all 35 districts exactly once and
produced SHA-256
`58aa062f403ba2cbd228c27413a4062444f6d1e158538a6986557086f093dfc6`.

## Merge Gates

The merge rejects an artifact unless:

- all 35 districts occur exactly once;
- all site IDs are globally unique;
- seed, layout seed, format, and generator fingerprint match;
- every decoded site belongs to its declared district;
- floor masks, stairs, patrol routes, entrance, and target topology are complete;
- every district retains five to eight sites; and
- no gig footprint overlaps a fixed mainline footprint.

Runtime verification results are recorded in
`SESSION_REGRESSION_LEDGER_2026-08-01.md` after the final build and GameTest run.
