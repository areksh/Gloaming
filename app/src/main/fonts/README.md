# Bundled typeface

This folder holds the typeface's licence and provenance. It sits beside `res/`
rather than in `res/font/` because Android's resource compiler rejects any file
there that is not a font or XML.

`../res/font/atkinson_hyperlegible_next.ttf` is Atkinson Hyperlegible Next, a variable font
with a weight axis (200 to 800), by the Braille Institute and Applied Design
Works. It is licensed under the SIL Open Font Licence 1.1; the full text is
`OFL.txt` beside it and is also shown in the app under Credits and licences
(`ui/Credits.kt`, checked against this file by `CreditsTest`).

Taken unmodified from the Google Fonts repository at commit
`95f4904fc8bcf26d3420fe315560c96417c6dec7`:

| File | Source | SHA-256 |
|---|---|---|
| `res/font/atkinson_hyperlegible_next.ttf` | https://raw.githubusercontent.com/google/fonts/95f4904fc8bcf26d3420fe315560c96417c6dec7/ofl/atkinsonhyperlegiblenext/AtkinsonHyperlegibleNext%5Bwght%5D.ttf | `5a455d1cfa099b601ab70751bb9673e8fe1854dc4500c80e1a220d0d75e31745` |
| `OFL.txt` | https://raw.githubusercontent.com/google/fonts/95f4904fc8bcf26d3420fe315560c96417c6dec7/ofl/atkinsonhyperlegiblenext/OFL.txt | `aca6a428580965d2297d1b718042dd427c2a9443ece3b0d02d758e161e0c4030` |

The file is renamed only because Android resource names must be lower case with
underscores. To upgrade, replace both files, update the commit and checksums
above, and update `ATKINSON_LICENCE` in `Credits.kt` if the licence text moved.

The weight axis is used through `FontVariation` (API 26 and later). On API 24
and 25, which ignore variation settings, the font renders at its default weight
and Compose thickens bold text synthetically; see `ui/theme/Type.kt`.
