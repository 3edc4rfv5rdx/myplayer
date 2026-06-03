#!/usr/bin/env python3
"""Render the MyPlayer launcher icon.

Teal background, white ring, inner teal circle, black play-triangle (with
slightly blunted tips) carrying a teal eighth-note. Rendered at 4x and
downscaled for clean antialiasing.
"""
import math
from PIL import Image, ImageDraw

TEAL = (0x52, 0xd6, 0xaf, 255)
WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)

BASE = 432
S = 4
N = BASE * S


def sc(v):
    return v * S


def blunt(poly, cut):
    """Replace each sharp vertex by two points inset `cut` px along its edges."""
    out = []
    n = len(poly)
    for i in range(n):
        prev = poly[(i - 1) % n]
        cur = poly[i]
        nxt = poly[(i + 1) % n]
        for nb in (prev, nxt):
            dx, dy = nb[0] - cur[0], nb[1] - cur[1]
            d = math.hypot(dx, dy)
            out.append((cur[0] + dx / d * cut, cur[1] + dy / d * cut))
    return out


img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# Background: rounded square, white.
d.rounded_rectangle([0, 0, N - 1, N - 1], radius=sc(86), fill=WHITE)

# Teal circle on the white background.
cx, cy = sc(216), sc(216)
r = sc(165)
d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=TEAL)

# Black play-triangle with blunted tips.
tri = [(153, 117), (153, 315), (339, 216)]
tri = [(sc(x), sc(y)) for x, y in tri]
d.polygon(blunt(tri, sc(5)), fill=BLACK)

# Teal eighth-note on the triangle.
def bez(p0, p1, p2, steps=24):
    pts = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        x = u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0]
        y = u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]
        pts.append((sc(x), sc(y)))
    return pts


# Tilted note head, drawn on its own layer and rotated. (Slightly smaller than the triangle.)
hx, hy, rx, ry = 198, 244, 22, 16
head = Image.new("RGBA", (sc(2 * rx + 8), sc(2 * ry + 8)), (0, 0, 0, 0))
ImageDraw.Draw(head).ellipse([sc(4), sc(4), sc(2 * rx + 4), sc(2 * ry + 4)], fill=TEAL)
head = head.rotate(18, expand=True, resample=Image.BICUBIC)
img.alpha_composite(head, (sc(hx - rx - 4) - (head.width - sc(2 * rx + 8)) // 2,
                           sc(hy - ry - 4) - (head.height - sc(2 * ry + 8)) // 2))

# Stem on the right of the head.
d.rounded_rectangle([sc(213), sc(170), sc(221), sc(244)], radius=sc(3), fill=TEAL)

# Curved flag (eighth-note tail) off the stem top.
flag = bez((220, 170), (252, 185), (241, 218)) + bez((241, 218), (238, 200), (220, 196))
d.polygon(flag, fill=TEAL)

out = img.resize((BASE, BASE), Image.LANCZOS)
out.save("app/src/main/res/mipmap-xxxhdpi/ic_launcher.png")
out.save("icon-preview.png")
print("written")
