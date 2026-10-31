#!/usr/bin/env python3
"""Generate Jellysync wordmarks and banner fallbacks; inherit upstream icons."""
from copy import deepcopy
from pathlib import Path
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw
import resvg_py

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/res"
RES = ROOT / "app/src/jellysync/res"
PREVIEWS = ROOT / "branding/previews"
ANDROID = "http://schemas.android.com/apk/res/android"
AAPT = "http://schemas.android.com/aapt"
SVG = "http://www.w3.org/2000/svg"
ET.register_namespace("android", ANDROID)
ET.register_namespace("aapt", AAPT)
BG = "#000B25"

# Hand-drawn extensions to Jellyfin's outlined lettering, CC BY-SA 4.0.
# Coordinates share upstream's x-height 80, overshoot 79.34/110.66, baseline 110.
# Curves follow e's bowl; 7.2-unit stems, 6.3-6.6-unit curved strokes and small
# rounded, angled terminals follow e/n. No font files are used.
S = (
    "M12.8,110.66C7.1,110.66 2.6,108.18 0.2,104.14"
    "C0.07,103.92 0,103.8 0,103.67C0,103.56 0.04,103.45 0.11,103.37"
    "C0.19,103.27 0.32,103.21 0.57,103.09L4.68,101.08"
    "C4.99,100.93 5.15,100.85 5.3,100.86C5.43,100.86 5.54,100.9 5.64,100.98"
    "C5.75,101.06 5.84,101.21 6.02,101.5"
    "C7.42,103.61 9.77,104.71 12.8,104.71C16.27,104.71 18.18,103.53 18.18,101.52"
    "C18.18,99.74 16.75,99.03 14.14,98.33L9.24,97.01"
    "C3.6,95.5 0.88,92.75 0.88,88.36C0.88,82.96 5.5,79.34 12.25,79.34"
    "C17.5,79.34 21.76,81.52 24.04,85.24"
    "C24.18,85.46 24.25,85.57 24.25,85.7C24.25,85.81 24.21,85.92 24.14,86"
    "C24.06,86.1 23.93,86.16 23.68,86.28L19.65,88.25"
    "C19.34,88.4 19.18,88.48 19.03,88.47C18.9,88.47 18.79,88.43 18.69,88.35"
    "C18.58,88.27 18.49,88.12 18.31,87.83"
    "C17.03,86.01 14.93,85 12.25,85C9.24,85 7.58,86.18 7.58,88.03"
    "C7.58,89.6 8.84,90.39 11.3,91.05L16.36,92.42"
    "C22.1,93.97 24.88,96.69 24.88,101.13C24.88,106.85 20.15,110.66 12.8,110.66Z"
)
C = (
    "M15.44,110.66C12.35,110.66 9.65,109.96 7.33,108.57"
    "C5.02,107.17 3.22,105.28 1.93,102.89C0.64,100.5 0,97.85 0,94.94"
    "C0,91.93 0.64,89.26 1.93,86.95C3.25,84.6 5.04,82.74 7.28,81.38"
    "C9.56,80.02 12.09,79.34 14.89,79.34C18.01,79.34 20.68,80.02 22.95,81.38"
    "C25.01,82.62 26.52,84.16 27.47,85.99"
    "C27.59,86.2 27.64,86.31 27.64,86.44C27.64,86.54 27.61,86.65 27.55,86.73"
    "C27.47,86.83 27.35,86.89 27.11,87.01L22.85,89.1"
    "C22.53,89.26 22.38,89.33 22.24,89.34C22.1,89.34 22,89.32 21.89,89.26"
    "C21.76,89.19 21.64,89.03 21.4,88.71"
    "C20.81,87.94 20.08,87.28 19.2,86.73C18.06,86.03 16.66,85.68 15,85.68"
    "C12.68,85.68 10.8,86.54 9.37,88.25C7.94,89.94 7.22,92.17 7.22,94.94"
    "C7.22,97.67 7.94,99.92 9.37,101.67C10.8,103.43 12.86,104.32 15.44,104.32"
    "C17.1,104.32 18.5,103.97 19.64,103.27C20.52,102.72 21.25,102.06 21.84,101.29"
    "C22.08,100.97 22.2,100.81 22.33,100.74C22.44,100.68 22.54,100.66 22.68,100.66"
    "C22.82,100.67 22.97,100.74 23.29,100.9L27.55,102.99"
    "C27.79,103.11 27.91,103.17 27.99,103.27C28.05,103.35 28.08,103.46 28.08,103.56"
    "C28.08,103.69 28.03,103.8 27.91,104.01C26.96,105.84 25.45,107.38 23.39,108.62"
    "C21.11,109.98 18.44,110.66 15.44,110.66Z"
)


def attrs(**values):
    return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}


def get(node, key, default=None):
    return node.get(f"{{{ANDROID}}}{key}", default)


def gradient(node, start, end):
    node.attrib.pop(f"{{{ANDROID}}}fillColor", None)
    attr = ET.SubElement(node, f"{{{AAPT}}}attr", {"name": "android:fillColor"})
    grad = ET.SubElement(attr, "gradient", attrs(type="linear", startX=start,
                                               startY=79.34, endX=end, endY=110.66))
    for offset, color in [(0, "#AA5CC3"), (1, "#00A4DC")]:
        ET.SubElement(grad, "item", attrs(offset=offset, color=color))


def wordmark(root, banner_source, is_banner):
    paths = list(banner_source)
    # The banner contains separate paths for n, i dot, i, yf, l, l, e, j.
    j, e, l1, l2, n = [deepcopy(paths[i]) for i in (9, 8, 7, 6, 2)]
    yf = get(paths[5], "pathData")
    # Keep y's original curves and descender. Remove f's joined bar and stem;
    # extend y's exposed diagonal to x-height, with the original 0.5-unit corner.
    y_start = yf.index("H221.53")
    y_end = yf.index("L225.98,86.45") + len("L225.98,86.45")
    y = ET.Element("path", attrs(fillColor="#ffffff", pathData=(
        "M221.53,80" + yf[y_start + len("H221.53"):y_end]
        + "L228.1,81.09C228.25,80.73 228.32,80.54 228.29,80.4"
          "C228.26,80.27 228.19,80.16 228.08,80.08"
          "C227.95,80 227.75,80 227.36,80H221.53Z")))
    s = ET.Element("path", attrs(pathData=S))
    c = ET.Element("path", attrs(pathData=C))
    # Original j/e/l/l/y placement and spacing. New optical gaps: ys 3.2,
    # sy 2.8, yn 3.2, nc 3.2. Same path for both occurrences of y.
    letters = [(j, 125, 0), (e, 144.17, 19.17), (l1, 176.94, 51.94),
               (l2, 188.56, 63.56), (y, 197.65, 72.65), (s, 0, 106.85),
               (deepcopy(y), 197.65, 134.53), (n, 259.42, 168.73), (c, 0, 198.51)]
    natural_width = 198.51 + 28.08
    # Same text start, baseline and glyph size as upstream; never distort the lettering.
    # main() widens the logo and scales the whole banner lockup evenly to make room.
    group = ET.SubElement(root, "group", attrs(
        translateX=125 if is_banner else 90.5,
        translateY=0 if is_banner else -54))
    for index, (node, source_left, cursor) in enumerate(letters):
        letter = ET.SubElement(group, "group", attrs(translateX=cursor-source_left))
        if index >= 5:
            gradient(node, 106.85 - cursor + source_left,
                     natural_width - cursor + source_left)
        letter.append(node)
    return natural_width


def write_xml(relative, root):
    target = RES / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    ET.indent(root, space="    ")
    ET.ElementTree(root).write(target, encoding="utf-8", xml_declaration=True)
    return target


def svg_from_vector(xml_file, background=False):
    """Translate paths, group transforms, clipping and linear aapt gradients."""
    root = ET.parse(xml_file).getroot()
    width, height = get(root, "viewportWidth"), get(root, "viewportHeight")
    svg = ET.Element("svg", xmlns=SVG, viewBox=f"0 0 {width} {height}",
                     width=width, height=height)
    defs = ET.SubElement(svg, "defs")
    if background:
        ET.SubElement(svg, "rect", width=width, height=height, fill=BG)
    serial = 0

    def visit(node, parent):
        nonlocal serial
        if node.tag == "group":
            parent = ET.SubElement(parent, "g", transform=(
                f"translate({get(node,'translateX','0')} {get(node,'translateY','0')}) "
                f"scale({get(node,'scaleX','1')} {get(node,'scaleY','1')})"))
        if node.tag == "clip-path":
            serial += 1
            name = f"clip{serial}"
            clip = ET.SubElement(defs, "clipPath", id=name)
            ET.SubElement(clip, "path", d=get(node, "pathData"),
                          **{"clip-rule": get(node, "fillType", "nonZero")})
            parent.set("clip-path", f"url(#{name})")
        if node.tag == "path":
            fill = get(node, "fillColor", "#000000")
            # VectorDrawable permits #RGB, #ARGB, #RRGGBB and #AARRGGBB;
            # SVG's eight-digit syntax instead puts alpha last.
            if fill.startswith("#") and len(fill) in (5, 9):
                fill = "#" + fill[3:] + fill[1:3] if len(fill) == 9 else (
                    "#" + "".join(ch * 2 for ch in fill[2:] + fill[1]))
            grad_node = node.find(f"{{{AAPT}}}attr/gradient")
            if grad_node is not None:
                serial += 1
                name = f"gradient{serial}"
                grad = ET.SubElement(defs, "linearGradient", id=name,
                    gradientUnits="userSpaceOnUse", x1=get(grad_node, "startX"),
                    y1=get(grad_node, "startY"), x2=get(grad_node, "endX"), y2=get(grad_node, "endY"))
                for item in grad_node:
                    color = get(item, "color")
                    if len(color) == 9:
                        color = "#" + color[3:] + color[1:3]
                    ET.SubElement(grad, "stop", offset=get(item, "offset"),
                                  **{"stop-color": color})
                fill = f"url(#{name})"
            fill_rule = "evenodd" if get(node, "fillType") == "evenOdd" else "nonzero"
            ET.SubElement(parent, "path", d=get(node, "pathData"), fill=fill,
                          **{"fill-rule": fill_rule})
        for child in node:
            if child.tag in ("group", "path", "clip-path"):
                visit(child, parent)
    visit(root, svg)
    return ET.tostring(svg, encoding="unicode")


def render(xml_file, output, width, height, background=True):
    output.write_bytes(resvg_py.svg_to_bytes(
        svg_string=svg_from_vector(xml_file, background), width=width, height=height))


def main():
    PREVIEWS.mkdir(parents=True, exist_ok=True)
    banner_source = ET.parse(MAIN / "drawable/app_banner_foreground.xml").getroot()
    vectors = {}
    for name in ("app_banner_foreground", "app_logo"):
        source = ET.parse(MAIN / f"drawable/{name}.xml").getroot()
        root = ET.Element("vector", source.attrib)
        # Copy mark paths, fill rules, gradients and positions without alteration.
        root.extend(deepcopy(list(source)[:2]))
        text_width = wordmark(root, banner_source, name == "app_banner_foreground")
        if name == "app_logo":
            # The longer name needs a wider logo: 90.5 (text start) + text width + 1 margin.
            logo_width = f"{90.5 + text_width + 1:.2f}"
            root.set(f"{{{ANDROID}}}viewportWidth", logo_width)
            root.set(f"{{{ANDROID}}}width", f"{logo_width}dp")
        else:
            # Scale mark and text together, keeping Jellyfin's mark-to-text proportions, to fit
            # the fixed 320x180 banner with 20-unit side margins, centred vertically like upstream.
            lockup_left, lockup_right, lockup_mid_y = 35, 125 + text_width, 89
            scale = (320 - 2 * 20) / (lockup_right - lockup_left)
            lockup = ET.Element("group", attrs(
                scaleX=f"{scale:.5f}", scaleY=f"{scale:.5f}",
                translateX=f"{20 - lockup_left * scale:.4f}",
                translateY=f"{90 - lockup_mid_y * scale:.4f}"))
            lockup.extend(list(root))
            for child in list(root):
                root.remove(child)
            root.append(lockup)
        target = write_xml(f"drawable/{name}.xml", root)
        vectors[name] = target
        generated_root = ET.parse(target).getroot()
        if generated_root[0].tag == "group" and name == "app_banner_foreground":
            generated_root = generated_root[0]
        for original, generated in zip(list(source)[:2], list(generated_root)[:2]):
            assert [(e.tag, e.attrib) for e in original.iter()] == [
                (e.tag, e.attrib) for e in generated.iter()]
        render(target, PREVIEWS / f"{name}.png", round(float(get(root, "viewportWidth"))),
               int(float(get(root, "viewportHeight"))), background=False)

    # Layer-list is retained unchanged; icons resolve from src/main/res.
    for name in ("app_icon_foreground.xml", "app_icon_foreground_monochrome.xml"):
        (RES / "drawable" / name).unlink(missing_ok=True)
    for density in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        (RES / f"mipmap-{density}/app_icon.png").unlink(missing_ok=True)
        with Image.open(MAIN / f"mipmap-{density}/app_banner.png") as image:
            width, height = image.size
        output = RES / f"mipmap-{density}/app_banner.png"
        output.parent.mkdir(parents=True, exist_ok=True)
        render(vectors["app_banner_foreground"], output, width, height)

    for width, height in [(320, 180), (160, 90)]:
        render(vectors["app_banner_foreground"], PREVIEWS / f"banner-{width}x{height}.png",
               width, height)
    logo_root = ET.parse(vectors["app_logo"]).getroot()
    render(vectors["app_logo"], PREVIEWS / "logo.png", round(float(get(logo_root, "viewportWidth"))), 72)
    render(MAIN / "drawable/app_banner_foreground.xml", PREVIEWS / "upstream-banner-320x180.png",
           320, 180)
    comparison = Image.new("RGB", (640, 204), BG)
    draw = ImageDraw.Draw(comparison)
    for x, name, label in [(0, "upstream-banner-320x180.png", "Upstream jellyfin"),
                           (320, "banner-320x180.png", "Jellysync: jellysync")]:
        with Image.open(PREVIEWS / name) as image:
            comparison.paste(image, (x, 24))
        draw.text((x + 12, 6), label, fill="white")
    comparison.save(PREVIEWS / "upstream-vs-jellysync.png")
    print("Lettering undistorted; banner lockup scaled evenly to fit.")
    print("Generated 2 vectors, 5 banner fallbacks and 7 previews. No Gradle run.")


if __name__ == "__main__":
    main()
