#!/usr/bin/env python3
"""
render_brand_assets.py — Rasterize Pulseboard's adaptive-icon vector drawables
to PNG assets required by Play Store (512×512 store icon, 1024×500 feature
graphic) and by Android itself (mipmap-*dpi PNG fallbacks).

Single source of truth: app/src/main/res/drawable/ic_launcher_*.xml
Brand palette:          docs/design/palette.md

Run from the pulseboard repo root:
    DYLD_LIBRARY_PATH=/opt/homebrew/lib python3 scripts/render_brand_assets.py

Produces:
    metadata/android/en-US/images/icon.png           (512×512, Play store icon)
    metadata/android/en-US/images/featureGraphic.png (1024×500, Play banner)
    app/src/main/res/mipmap-mdpi/ic_launcher.webp    (48×48)
    app/src/main/res/mipmap-hdpi/ic_launcher.webp    (72×72)
    app/src/main/res/mipmap-xhdpi/ic_launcher.webp   (96×96)
    app/src/main/res/mipmap-xxhdpi/ic_launcher.webp  (144×144)
    app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp (192×192)
    (same filename set for ic_launcher_round.webp — same source art, circular
    crop applied at render time)
"""
from __future__ import annotations

import os
from pathlib import Path

import cairosvg
from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO_ROOT = Path(__file__).resolve().parent.parent

# From docs/design/palette.md
BRAND_PRIMARY = "#0FC9E3"   # signal cyan — background
BRAND_DARK = "#0A0D14"      # neutral-950 (app background)
BRAND_TEXT_ON_DARK = "#E7ECF5"  # neutral-100

# Adaptive icon geometry: 108×108 viewport, but only the central 72×72 is
# visible — outer 18dp ring is masked by launcher. All drawable paths already
# account for this (pulse arcs + dot sit inside the visible circle).
VIEWPORT = 108
SVG_TEMPLATE = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vp} {vp}" width="{size}" height="{size}">
  <rect width="{vp}" height="{vp}" fill="{bg}"/>
  <path d="M22,74 A32,32 0 0 1 86,74" stroke="#FFFFFF" stroke-width="7" stroke-linecap="round" fill="none"/>
  <path d="M32,74 A22,22 0 0 1 76,74" stroke="#FFFFFF" stroke-width="7" stroke-linecap="round" fill="none"/>
  <path d="M42,74 A12,12 0 0 1 66,74" stroke="#FFFFFF" stroke-width="7" stroke-linecap="round" fill="none"/>
  <circle cx="54" cy="74" r="4" fill="#FFFFFF"/>
</svg>"""


def render_icon_png(size: int, bg: str = BRAND_PRIMARY) -> Image.Image:
    svg = SVG_TEMPLATE.format(vp=VIEWPORT, size=size, bg=bg)
    png_bytes = cairosvg.svg2png(
        bytestring=svg.encode(),
        output_width=size,
        output_height=size,
    )
    from io import BytesIO
    return Image.open(BytesIO(png_bytes)).convert("RGBA")


def apply_circular_mask(img: Image.Image) -> Image.Image:
    """For ic_launcher_round: render the same art onto a circle with anti-aliased mask."""
    size = img.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse(
        (0, 0, size * 4 - 1, size * 4 - 1), fill=255
    )
    mask = mask.resize((size, size), Image.LANCZOS)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), mask)
    return out


def write_mipmap_webps() -> None:
    """Android mipmap densities per the Play Store publish doc.

    Modern Android (min SDK 26) uses the XML adaptive icon at runtime, but
    some tooling (older Play Console checks, ADB aapt reports) still likes
    having PNG/webp fallbacks present. Render for completeness.
    """
    densities = [
        ("mdpi", 48),
        ("hdpi", 72),
        ("xhdpi", 96),
        ("xxhdpi", 144),
        ("xxxhdpi", 192),
    ]
    for density, size in densities:
        base = REPO_ROOT / "app" / "src" / "main" / "res" / f"mipmap-{density}"
        base.mkdir(parents=True, exist_ok=True)
        square = render_icon_png(size)
        circle = apply_circular_mask(square)
        square.save(base / "ic_launcher.webp", format="WEBP", quality=92)
        circle.save(base / "ic_launcher_round.webp", format="WEBP", quality=92)
        print(f"  mipmap-{density}/ ← {size}×{size}")


def write_store_icon() -> None:
    """Play Store listing icon: 512×512 PNG, no alpha, square."""
    out = REPO_ROOT / "metadata" / "android" / "en-US" / "images" / "icon.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img = render_icon_png(512)
    img.convert("RGB").save(out, "PNG", optimize=True)
    print(f"  {out.relative_to(REPO_ROOT)}  (512×512 store icon)")


def write_feature_graphic() -> None:
    """Play Store feature graphic: 1024×500 PNG, no alpha.

    Layout: dark-mode canvas (neutral-950), pulse mark left-of-center,
    wordmark "Pulseboard" + tagline right. Minimal, brand-consistent.
    """
    W, H = 1024, 500
    canvas = Image.new("RGB", (W, H), BRAND_DARK)

    # Render a 320×320 pulse mark on cyan background, then paste left-center.
    mark = render_icon_png(320, bg=BRAND_PRIMARY)
    # Round the mark corners so it doesn't look like a raw square against dark BG.
    radius = 60
    mask = Image.new("L", mark.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, mark.size[0], mark.size[1]), radius=radius, fill=255
    )
    canvas.paste(mark, (96, (H - 320) // 2), mask)

    # Wordmark + tagline (use a system font; fall back gracefully).
    draw = ImageDraw.Draw(canvas)
    title_font = None
    tag_font = None
    for candidate in [
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/SFNS.ttf",
        "/Library/Fonts/Arial Bold.ttf",
    ]:
        if os.path.exists(candidate):
            try:
                title_font = ImageFont.truetype(candidate, 96)
                tag_font = ImageFont.truetype(candidate, 26)
                break
            except Exception:
                continue
    if title_font is None:
        title_font = ImageFont.load_default()
        tag_font = ImageFont.load_default()

    text_x = 96 + 320 + 56  # mark width + gap
    TAGLINE = "Network health vitals for your team"

    def _text_w(s: str, f) -> int:
        try:
            return int(draw.textlength(s, font=f))
        except Exception:
            return f.getsize(s)[0]  # legacy Pillow

    avail_w = W - text_x - 28  # right margin
    # Shrink tagline font if it still overflows (long locales / font swaps)
    size = 26
    while _text_w(TAGLINE, tag_font) > avail_w and size > 16:
        size -= 2
        try:
            tag_font = ImageFont.truetype(tag_font.path, size)
        except Exception:
            break

    draw.text((text_x, 170), "Pulseboard", font=title_font, fill=BRAND_TEXT_ON_DARK)
    draw.text((text_x, 300), TAGLINE, font=tag_font, fill="#9CAAC8")  # neutral-300

    out = REPO_ROOT / "metadata" / "android" / "en-US" / "images" / "featureGraphic.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, "PNG", optimize=True)
    print(f"  {out.relative_to(REPO_ROOT)}  (1024×500 feature graphic)")


def _load_fonts(sizes: list[int]) -> list[ImageFont.FreeTypeFont]:
    for candidate in [
        "/System/Library/Fonts/Helvetica.ttc",
        "/System/Library/Fonts/SFNS.ttf",
        "/Library/Fonts/Arial Bold.ttf",
    ]:
        if os.path.exists(candidate):
            try:
                return [ImageFont.truetype(candidate, s) for s in sizes]
            except Exception:
                continue
    return [ImageFont.load_default() for _ in sizes]


def write_phone_screenshots() -> None:
    """Render 2 phone-sized screenshots (1080×1920 portrait, Play-compliant).

    The stub app currently shows a splash — render a faithful tile of it, plus
    a second "roadmap" tile that honestly communicates the current build state.
    These are replaceable once v1.1 has a real UI to capture with screencap.
    """
    W, H = 1080, 1920
    BG = BRAND_DARK
    PRIMARY = BRAND_PRIMARY
    TEXT = BRAND_TEXT_ON_DARK
    MUTED = "#9CAAC8"

    # --- Screenshot 1: splash (mirrors app/src/main/res/layout/activity_main.xml) ---
    shot1 = Image.new("RGB", (W, H), BG)
    draw = ImageDraw.Draw(shot1)

    mark = render_icon_png(360)
    radius = 72
    mask = Image.new("L", mark.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, mark.size[0], mark.size[1]), radius=radius, fill=255
    )
    shot1.paste(mark, ((W - 360) // 2, 560), mask)

    f_title, f_sub, f_body = _load_fonts([130, 52, 44])
    _cdraw = ImageDraw.Draw(shot1)

    def _centered(y: int, text: str, font, fill) -> None:
        try:
            w = int(_cdraw.textlength(text, font=font))
        except Exception:
            w = font.getsize(text)[0]
        _cdraw.text(((W - w) // 2, y), text, font=font, fill=fill)

    _centered(1000, "Pulseboard", f_title, PRIMARY)
    _centered(1160, "Network health vitals for your team", f_sub, TEXT)
    _centered(1400, "Shared engine: ready.", f_body, MUTED)
    _centered(1460, "Public app build: v1.1 (coming soon).", f_body, MUTED)

    # --- Screenshot 2: "what it measures" roadmap tile ---
    shot2 = Image.new("RGB", (W, H), BG)
    d2 = ImageDraw.Draw(shot2)
    f_h, f_item, f_cap = _load_fonts([96, 52, 40])
    d2.text((80, 180), "What it\nmeasures", font=f_h, fill=PRIMARY)

    items = [
        ("Latency", "RTT to your targets — p50, p95, p99"),
        ("Packet loss", "Sample-window aggregates"),
        ("Jitter", "Connection stability over time"),
        ("Wi-Fi context", "BSSID, RSSI, channel, visible APs"),
        ("VPN + net type", "Every sample tagged"),
    ]
    y = 640
    for title, desc in items:
        d2.rectangle((80, y, 120, y + 8), fill=PRIMARY)
        d2.text((160, y - 20), title, font=f_item, fill=TEXT)
        d2.text((160, y + 44), desc, font=f_cap, fill=MUTED)
        y += 180

    d2.text((80, H - 180), "Uploads to your Google Sheet.", font=f_cap, fill=MUTED)
    d2.text((80, H - 130), "No backend. No accounts. Just vitals.", font=f_cap, fill=MUTED)

    # Save both
    out_dir = REPO_ROOT / "metadata" / "android" / "en-US" / "images" / "phoneScreenshots"
    out_dir.mkdir(parents=True, exist_ok=True)
    shot1.save(out_dir / "01_splash.png", "PNG", optimize=True)
    shot2.save(out_dir / "02_measures.png", "PNG", optimize=True)
    print(f"  {out_dir.relative_to(REPO_ROOT)}/01_splash.png      (1080×1920)")
    print(f"  {out_dir.relative_to(REPO_ROOT)}/02_measures.png    (1080×1920)")


def main() -> None:
    print("rendering brand assets…")
    write_store_icon()
    write_feature_graphic()
    write_phone_screenshots()
    write_mipmap_webps()
    print("done.")


if __name__ == "__main__":
    main()
