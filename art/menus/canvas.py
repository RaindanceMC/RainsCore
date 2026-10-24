"""
Shared painting for menu backdrops: the chest window's geometry and the few primitives every backdrop is built
from. Used by Core's own frames and by the plugins' (economy's stall, the adventurers' parchment).

Geometry measured from the vanilla generic_54 texture and ContainerScreen: a window of n rows is 176 wide and
114 + 18n tall; slot wells (18x18, outer corner) sit at (7 + 18c, 17 + 18r) in the chest, the player's inventory
at y = 30 + 18n, the hotbar at 88 + 18n; the game writes "Inventory" at (8, 20 + 18n) in dark grey.
"""
import math
import random

from PIL import Image

W = 176
SLOT = 18


def height(rows):
    return 114 + SLOT * rows


def chest_well(row, col):
    return 7 + SLOT * col, 17 + SLOT * row


def inventory_well(rows, row, col):
    return 7 + SLOT * col, 30 + SLOT * rows + SLOT * row


def hotbar_well(rows, col):
    return 7 + SLOT * col, 88 + SLOT * rows


def label_y(rows):
    return 20 + SLOT * rows


def clamp(v):
    return int(max(0, min(255, round(v))))


def mix(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def shade(c, f):
    return tuple(c[i] * f for i in range(3))


def hexrgb(h):
    return (h >> 16 & 255, h >> 8 & 255, h & 255)


class Canvas:
    def __init__(self, w, h, seed=1):
        self.w, self.h = w, h
        self.img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.px = self.img.load()
        self.rng = random.Random(seed)

    def put(self, x, y, rgb, a=255):
        if 0 <= x < self.w and 0 <= y < self.h:
            if a >= 255:
                self.px[x, y] = tuple(clamp(c) for c in rgb) + (255,)
            else:
                r, g, b, old = self.px[x, y]
                t = a / 255
                base = (r, g, b) if old else rgb
                self.px[x, y] = tuple(clamp(base[i] + (rgb[i] - base[i]) * t) for i in range(3)) + (
                    max(old, a),)

    def get(self, x, y):
        r, g, b, _ = self.px[x, y]
        return r, g, b

    def tint(self, x, y, rgb, t):
        if 0 <= x < self.w and 0 <= y < self.h and self.px[x, y][3]:
            self.put(x, y, mix(self.get(x, y), rgb, t))

    def scale(self, x, y, f):
        if 0 <= x < self.w and 0 <= y < self.h and self.px[x, y][3]:
            self.put(x, y, shade(self.get(x, y), f))

    def rect(self, x0, y0, x1, y1, rgb):
        for y in range(y0, y1):
            for x in range(x0, x1):
                self.put(x, y, rgb)

    def hline(self, x0, x1, y, rgb, a=255):
        for x in range(x0, x1):
            self.put(x, y, rgb, a)

    def vline(self, x, y0, y1, rgb, a=255):
        for y in range(y0, y1):
            self.put(x, y, rgb, a)

    def bevel(self, x0, y0, x1, y1, light, dark, strength=1.0, width=1):
        """Raised edge: light top/left, dark bottom/right (swap for a recess)."""
        for i in range(width):
            t = strength * (1 - i / width)
            for x in range(x0 + i, x1 - i):
                self.tint(x, y0 + i, light, 0.55 * t)
                self.tint(x, y1 - 1 - i, dark, 0.55 * t)
            for y in range(y0 + i, y1 - i):
                self.tint(x0 + i, y, light, 0.55 * t)
                self.tint(x1 - 1 - i, y, dark, 0.55 * t)

    def outline(self, x0, y0, x1, y1, rgb, corner=True):
        self.hline(x0 + (1 if corner else 0), x1 - (1 if corner else 0), y0, rgb)
        self.hline(x0 + (1 if corner else 0), x1 - (1 if corner else 0), y1 - 1, rgb)
        self.vline(x0, y0 + (1 if corner else 0), y1 - (1 if corner else 0), rgb)
        self.vline(x1 - 1, y0 + (1 if corner else 0), y1 - (1 if corner else 0), rgb)

    def clear(self, x, y):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[x, y] = (0, 0, 0, 0)

    def round_corners(self, r=2):
        for (cx, cy, dx, dy) in ((0, 0, 1, 1), (self.w - 1, 0, -1, 1), (0, self.h - 1, 1, -1),
                                 (self.w - 1, self.h - 1, -1, -1)):
            for i in range(r):
                for j in range(r - i):
                    self.clear(cx + dx * i, cy + dy * j)

    def save(self, path):
        self.img.save(path)


def wood(c, x0, y0, x1, y1, base, dark, light, seed=0, plank=None, vertical=False, seams=None):
    """Planks with grain: long sine-warped streaks, the odd knot, a dark seam between boards."""
    rng = random.Random(seed)
    knots = [(rng.uniform(x0, x1), rng.uniform(y0, y1), rng.uniform(1.6, 2.8))
             for _ in range(max(1, (x1 - x0) * (y1 - y0) // 2600))]
    phase = rng.uniform(0, 6.28)
    for y in range(y0, y1):
        for x in range(x0, x1):
            u, v = (y, x) if vertical else (x, y)
            board = 0 if not plank else (v - (y0 if not vertical else x0)) // plank
            off = board * 37.0
            warp = math.sin(u * 0.045 + off + phase) * 2.2 + math.sin(u * 0.13 + off * 1.7) * 0.6
            for kx, ky, kr in knots:
                d = math.hypot(x - kx, y - ky)
                if d < kr * 4:
                    warp += (kr * 4 - d) * 0.35 * math.copysign(1, (y - ky) if not vertical else (x - kx))
            g = math.sin((v + warp) * 1.25 + off) * 0.5 + 0.5
            g2 = math.sin((v + warp * 1.4) * 0.41 + off * 0.3) * 0.5 + 0.5
            t = 0.55 * g ** 3 + 0.25 * g2
            col = mix(base, dark, t * 0.75)
            if g > 0.93:
                col = mix(col, light, 0.25)
            col = mix(col, dark, rng.random() * 0.06)
            c.put(x, y, col)
        if plank and not vertical:
            pass
    for kx, ky, kr in knots:
        for y in range(int(ky - kr), int(ky + kr) + 1):
            for x in range(int(kx - kr * 1.6), int(kx + kr * 1.6) + 1):
                if x0 <= x < x1 and y0 <= y < y1:
                    d = math.hypot((x - kx) / 1.6, y - ky)
                    if d < kr:
                        c.tint(x, y, dark, 0.55 * (1 - d / kr) + 0.15)
    if plank:
        if vertical:
            for bx in range(x0 + plank, x1, plank):
                c.vline(bx, y0, y1, dark)
                c.vline(bx + 1, y0, y1, light, 60)
        else:
            for by in range(y0 + plank, y1, plank):
                c.hline(x0, x1, by, dark)
                c.hline(x0, x1, by + 1, light, 60)
    if seams:
        for sx in seams:
            c.vline(sx, y0, y1, dark)
            c.vline(sx + 1, y0, y1, light, 50)


def metal(c, x0, y0, x1, y1, base, dark, light, seed=0, brushed=True):
    rng = random.Random(seed)
    streaks = [rng.uniform(-1, 1) for _ in range(y1 - y0)]
    for y in range(y0, y1):
        s = streaks[y - y0] * 0.07
        for x in range(x0, x1):
            t = (y - y0) / max(1, y1 - y0 - 1)
            col = mix(light, base, min(1, t * 1.6)) if t < 0.5 else mix(base, dark, (t - 0.5) * 1.2)
            if brushed:
                col = shade(col, 1 + s + math.sin(x * 0.9 + y * 3.1) * 0.012)
            c.put(x, y, col)


def rivet(c, x, y, face, dark, light):
    """A 3x3 domed rivet with a lit top-left and a cast shadow."""
    for dx, dy, f in ((0, 0, 1.0), (1, 0, 0.92), (-1, 0, 0.92), (0, 1, 0.85), (0, -1, 1.0)):
        c.put(x + dx, y + dy, shade(face, f))
    c.put(x - 1, y - 1, light, 140)
    c.put(x, y - 1, light)
    c.put(x - 1, y, light, 160)
    c.put(x + 1, y + 1, dark)
    c.put(x + 1, y + 2, dark, 90)
    c.put(x + 2, y + 1, dark, 90)


def well(c, x, y, inner, rim_dark, rim_light, depth=0.35, rim=None, show=0.0):
    """A slot recess 18x18 at its outer corner: shadowed top-left, lit bottom-right, a soft floor.

    show lets that much of what is painted underneath (grain, cloth) through the floor."""
    if rim:
        c.outline(x - 1, y - 1, x + 19, y + 19, rim, corner=True)
    for yy in range(y, y + 18):
        for xx in range(x, x + 18):
            edge = min(xx - x, yy - y)
            t = max(0.0, 1 - edge / 5) * depth
            floor = mix(inner, c.get(xx, yy), show) if show and c.px[xx, yy][3] else inner
            c.put(xx, yy, mix(floor, (0, 0, 0), t))
    c.hline(x, x + 18, y, rim_dark)
    c.vline(x, y, y + 18, rim_dark)
    c.hline(x + 1, x + 17, y + 1, rim_dark, 120)
    c.vline(x + 1, y + 1, y + 17, rim_dark, 120)
    c.hline(x + 1, x + 18, y + 17, rim_light)
    c.vline(x + 17, y + 1, y + 18, rim_light)


def plaque(c, x0, y0, x1, y1, face, edge_dark, edge_light, seed=0, grain=None):
    rng = random.Random(seed)
    for y in range(y0, y1):
        for x in range(x0, x1):
            t = (y - y0) / max(1, y1 - y0 - 1)
            col = mix(face, shade(face, 0.9), t)
            if grain:
                col = mix(col, grain, max(0, math.sin(x * 0.7 + y * 0.2 + rng.random())) * 0.08)
            c.put(x, y, col)
    c.outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, edge_dark)
    c.hline(x0, x1, y0, edge_light, 150)
    c.vline(x0, y0, y1, edge_light, 110)
    c.hline(x0, x1, y1 - 1, edge_dark, 80)


def vignette(c, x0, y0, x1, y1, dark, strength=0.25, reach=10):
    for y in range(y0, y1):
        for x in range(x0, x1):
            e = min(x - x0, y - y0, x1 - 1 - x, y1 - 1 - y)
            if e < reach:
                c.tint(x, y, dark, strength * (1 - e / reach) ** 2)


def drop_shadow(c, x0, y0, x1, y1, dark, size=2, alpha=0.45):
    """Shadow cast down-right by a raised rectangle onto what is already painted."""
    for i in range(1, size + 1):
        a = alpha * (1 - (i - 1) / size)
        c_x = x1 - 1 + i
        for y in range(y0 + i, y1 + i):
            c.tint(c_x, y, dark, a)
        for x in range(x0 + i, x1 + i):
            c.tint(x, y1 - 1 + i, dark, a)
