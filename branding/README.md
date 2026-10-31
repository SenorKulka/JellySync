# Jellysync branding

Original Jellyfin triangle, unchanged, beside `jellysync`. `jelly` is white;
`sync` uses Jellyfin's `#AA5CC3` to `#00A4DC` gradient. All lettering is outlined.
No font is bundled or required at runtime.

## Lettering and layout

`generate.py` reuses j, e, l, l, y and n from upstream's
`app/src/main/res/drawable/app_banner_foreground.xml`. Both lockups use those
same outlines. The in-app version translates them by the upstream lockup's
34.5-unit horizontal and 54-unit vertical offset.

Jellyfin UX was shallow-cloned and inspected at commit
[`69a2b7b1237732a134dd501ff55b9404cf2d1601`](https://github.com/jellyfin/jellyfin-ux/tree/69a2b7b1237732a134dd501ff55b9404cf2d1601).
Its README and SVG artwork contain no wordmark typeface attribution, and the
checkout contains no font binaries. The old `branding/SVG` wordmark also differs
from the current `logos/SVG` lettering. No font identity was assumed.

The new **s and c are hand-drawn**, with their source paths in `generate.py`.
They follow e's bowl, n's stem weight and the existing small rounded terminals:
x-height 80, baseline 110, curved overshoots 79.34/110.66, straight stem width
about 7.2 units and curved strokes about 6.3 units. The y and f share a single
upstream outline. The generator removes f, preserves y's body and descender,
and closes its exposed upper-right diagonal at x-height. Both y occurrences
use this same outline.

The lettering is never distorted: glyph size, text start, mark-to-text gap and
baselines (110 in the banner, 56 in the in-app logo) match upstream. The longer
word is fitted differently in each file:

- In-app logo: the viewport widens from 252 to 318.09 units, so the word keeps
  its full size. Callers that fix the logo's width (splash, screensaver) show it
  slightly shorter.
- Banner: the size is fixed at 320×180, so mark and text are scaled together
  evenly (0.884), keeping Jellyfin's mark-to-text proportions, with 20-unit side
  margins and upstream's vertical centre.

Each drawable copies its own upstream mark paths, fill rules, gradients and
coordinates verbatim; only the banner's enclosing group scales them.

## Android resources

Resources under `app/src/jellysync/res/` override only the fork's branding:

- `drawable/app_banner_foreground.xml`: 320×180 TV banner foreground.
- `drawable/app_logo.xml`: 318×72 in-app lockup.
- `mipmap-anydpi-v26/app_banner.xml`: retained layer-list using upstream's
  `#000B25` background and the new foreground.
- `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/app_banner.png`: fallbacks at upstream's
  160×90, 240×135, 320×180, 480×270 and 640×360 sizes.

There are no icon overrides. Adaptive, monochrome and legacy launcher icons
resolve from `app/src/main/res/`. `values/strings.xml` is preserved.
The vectors use plain Android paths, groups and `aapt:attr` linear gradients,
matching upstream. No SVG-only effects or runtime text are used.

## Regenerate

From the repository root, with Python 3.10 or newer:

```sh
python3 -m venv /tmp/logo-venv
/tmp/logo-venv/bin/python -I -m pip install -r branding/requirements.txt
/tmp/logo-venv/bin/python -I branding/generate.py
```

An existing `/tmp/logo-venv` can be reused. The generator reads upstream vectors
and PNG dimensions, writes the two wordmark vectors and five banner fallbacks,
removes the seven superseded icon overrides, and renders previews. It preserves
the banner layer-list and unrelated resources. No font files are used.

## Previews and verification

- `previews/banner-320x180.png` and `previews/banner-160x90.png`: actual-size navy
  composites.
- `previews/logo.png`: actual-size in-app logo on `#000B25`.
- `previews/upstream-vs-jellysync.png`: labelled side-by-side banners, upstream
  on the left and Jellysync on the right.
- `previews/upstream-banner-320x180.png`: upstream banner reference.
- `previews/app_banner_foreground.png` and `previews/app_logo.png`: transparent
  vector renders.

Previews are rendered from the generated Android XML through an in-memory SVG
translation and resvg. The comparison, 160×90 banner, in-app logo and enlarged
uncompressed lettering were visually inspected, including s/c curves and
spacing beside the reused glyphs. XML parsing, exact mark-path/gradient copies,
PNG dimensions and inherited icons were checked. No Gradle build or device
test was run. Matching s/c to the source is an optical judgement, not a verified
font match.

## Artwork credits and licence

Mark and reused lettering: Jellyfin contributors,
[Jellyfin UX](https://github.com/jellyfin/jellyfin-ux),
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).
Local source: Jellyfin Android TV's upstream drawables in `app/src/main/res/`.
Upstream licence and contributor notice are included as
`LICENSE-Jellyfin-CC-BY-SA-4.0.txt` and `Jellyfin-CONTRIBUTORS.md`.

Jellysync changes comprise the new word, hand-drawn s/c, separated y terminal,
even fitting of the longer lockup and gradient word suffix. These artwork adaptations are
also licensed under CC BY-SA 4.0. s/c come from these hand-drawn paths, not
Outfit or another font. The Jellyfin mark itself is unchanged.
