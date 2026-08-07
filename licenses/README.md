# Third-party font licenses

Skerry bundles the fonts below in `composeApp/src/commonMain/composeResources/font/` and ships them
inside every artifact (APK, `.deb`/`.rpm`/`.msi`/`.dmg`, Flatpak, AppImage). Each of their licenses
requires the copyright notice and the license text to travel with the binary — these files are that
copy. The client code itself stays under [GPL-3.0](../LICENSE); a font license covers only the font.

| Font | Version | Files | License | Upstream |
|---|---|---|---|---|
| Hack | 3.003 | `hack_regular.ttf`, `hack_bold.ttf` | [Hack Open Font License + Bitstream Vera License](Hack-LICENSE.md) | [source-foundry/Hack](https://github.com/source-foundry/Hack) |
| JetBrains Mono | 2.305 | `jetbrainsmono_regular.ttf`, `jetbrainsmono_bold.ttf` | [OFL-1.1](JetBrainsMono-OFL-1.1.txt) | [JetBrains/JetBrainsMono](https://github.com/JetBrains/JetBrainsMono) |
| IBM Plex Sans | 3.005 | `ibmplexsans_{regular,medium,semibold,bold}.ttf` | [OFL-1.1](IBMPlex-OFL-1.1.txt) | [IBM/plex](https://github.com/IBM/plex) |
| IBM Plex Sans SC | 1.000 | `ibmplexsanssc_{regular,semibold}.ttf` | [OFL-1.1](IBMPlex-OFL-1.1.txt) | [IBM/plex](https://github.com/IBM/plex) |
| Maple Mono CN | 7.900 | `maplemono_{regular,bold}.ttf` | [OFL-1.1](MapleMono-OFL-1.1.txt) | [subframe7536/maple-font](https://github.com/subframe7536/maple-font) |
| Material Symbols Outlined | 2.952 | `material_symbols_outlined.ttf` | [Apache-2.0](MaterialSymbols-Apache-2.0.txt) | [google/material-design-icons](https://github.com/google/material-design-icons) |

## Modified fonts

Maple Mono CN is the only bundled face that is not the upstream binary: it is a subset (7426
codepoints, ~5 MB per weight instead of ~18 MB) covering GB2312, ASCII and CJK punctuation. OFL-1.1
permits this — the upstream copyright carries no Reserved Font Name — and requires the derivative to
stay under OFL-1.1, which it does.

`tools/fonts/verify-maple-mono.py` checks that claim against the upstream release: every bundled
glyph must exist upstream with the same outline and advance. Everything else in the table is the
untouched upstream binary, renamed to satisfy the compose-resources naming rules.
