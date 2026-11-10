#!/usr/bin/env python3
"""MR TV 2 (2.0.6): small flag images for the Live TV country headers, drawn from Noto Color Emoji (SIL OFL 1.1).
Bundled as images because flag emoji do not show on every Fire TV / Android 7 device.

    python3 make_flags.py ../../app/src/main/assets/mrtv2-categories.json ../../app/src/main/res/drawable-nodpi
"""
import json, sys
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"


def main(cats, out):
    d = json.load(open(cats, encoding="utf-8"))
    f = ImageFont.truetype(FONT, 109)
    for c in d["countries"]:
        fl = c.get("flag")
        if not fl:
            continue
        s = "".join(chr(0x1F1E6 + ord(ch) - 97) for ch in fl)
        im = Image.new("RGBA", (160, 160), (0, 0, 0, 0))
        ImageDraw.Draw(im).text((10, 10), s, font=f, embedded_color=True)
        im = im.crop(im.getbbox())
        w, h = im.size
        im.resize((round(w * 36 / h), 36), Image.LANCZOS).save(f"{out}/flag_{fl}.png", optimize=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
