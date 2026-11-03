"""
Paints a skill tree's frames and lines and writes them as a font for the window title to draw (Java:
de.raindancer.core.ui.tree — TreeSprite, TreeGlyphs, TreeGrid). A plugin with a look of its own imports this, hands
write() its own Style and gets the same characters in its own font.

Every sprite is 26x26, centred on its slot: one character per sprite and slot row 0..4 from U+E100 (a bitmap's
ascent is fixed, so each row needs its own), ascent -18*row; spaces U+F100+i move back 2^i, U+F108+i forward 2^i.

    python3 tree.py            # Core's brass look into RainsCore's pack
"""
import json
import math
import os

from PIL import Image

SIZE = 26
C = (SIZE - 1) / 2.0
ROWS = 5
FIRST_SPRITE = 0xE100
FIRST_SPACE = 0xF100
# The order of TreeSprite.
SPRITES = ["frame_locked", "frame_open", "frame_owned", "frame_maxed", "frame_barred",
           "across_dim", "across_lit", "down_dim", "down_lit", "falling_dim", "falling_lit", "rising_dim", "rising_lit",
           "mastery_locked", "mastery_open", "mastery_maxed"]
# A branch's mastery badge, in the order of TreeSprite after the lines.
MASTERIES = ["locked", "open", "maxed"]
FRAMES = ["locked", "open", "owned", "maxed", "barred"]


def clamp(v):
    return int(max(0, min(255, round(v))))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


class Style:
    """How a look draws. Override any method; colours are (r, g, b)."""

    def line(self, along, across, lit):
        """A line's pixel `across` pixels off its middle, `along` pixels from its start; None for nothing."""
        d = abs(across)
        if lit:
            if d <= 1.0:
                return mix((255, 236, 160), (232, 180, 70), d), 255
            if d <= 2.2:
                return (176, 120, 36), 255
            if d <= 4.5:
                return (255, 200, 90), int(110 * (1 - (d - 2.2) / 2.3))
            return None
        if d <= 1.0:
            return (118, 96, 66), 255
        if d <= 2.0:
            return (58, 44, 30), 255
        return None

    def mastery(self, state, x, y):
        """A mastery badge's pixel: the look's own frame (open, maxed or locked) under a five-pointed star crown.
        Override for a look of its own."""
        colour = {"locked": (110, 104, 96), "open": (150, 255, 180), "maxed": (255, 214, 90)}[state]
        star = _star(x, y)
        if star is not None:
            return mix(colour, (255, 255, 240), star), 255
        if _studs(x, y):
            return mix(colour, (255, 255, 255), 0.35), 255
        return self.frame({"locked": "locked", "open": "open", "maxed": "maxed"}[state], x, y)

    def frame(self, state, x, y):
        """A frame's pixel at (x, y) from its centre; None for nothing. The item sits on top of the middle."""
        r = math.hypot(x, y)
        ring = {"locked": ((92, 88, 84), (48, 46, 44)),
                "open": ((120, 220, 230), (40, 110, 130)),
                "owned": ((246, 204, 96), (150, 98, 30)),
                "maxed": ((255, 236, 150), (196, 130, 40)),
                "barred": ((150, 70, 64), (70, 34, 32))}[state]
        inner = {"locked": (26, 24, 24), "open": (14, 40, 50), "owned": (60, 40, 18), "maxed": (84, 58, 20),
                 "barred": (36, 20, 20)}[state]
        if state == "maxed":
            a = math.atan2(y, x)
            spike = 11.0 + 1.6 * max(0.0, math.cos(a * 8)) ** 4
            if 10.0 < r <= spike and r > 11.0:
                return (255, 220, 120), 255
        if state == "open" and 11.0 < r <= 12.6:
            return (140, 240, 255), int(120 * (1 - (r - 11.0) / 1.6))
        if 8.6 < r <= 11.0:
            light = 0.5 - 0.5 * (x + y) / max(1.0, r) / 1.2
            return mix(ring[1], ring[0], light + (0.3 if r < 9.6 else 0.0)), 255
        if r <= 8.6:
            colour = mix(inner, (0, 0, 0), r / 8.6 * 0.5)
            if state == "barred" and abs(x - y) <= 1.0:
                return (190, 70, 60), 255
            return colour, 230
        return None


def _star(x, y):
    """How bright a five-pointed star crowning a frame is at (x, y), or None outside it."""
    dx, dy = x, y + 8.6
    r = math.hypot(dx, dy)
    if r > 4.2:
        return None
    a = math.atan2(dy, dx) + math.pi / 2
    k = (a % (2 * math.pi / 5)) - math.pi / 5
    if r > 4.2 - 2.4 * (abs(k) / (math.pi / 5)):
        return None
    return 0.6 - 0.4 * (dx + dy) / 4.2


def _studs(x, y):
    """Diamond studs on a frame's ring, left, right and below — what marks a badge from a node."""
    for sx, sy in ((-10.2, 0.0), (10.2, 0.0), (0.0, 10.2)):
        if abs(x - sx) + abs(y - sy) <= 1.8:
            return True
    return False


def _image(paint):
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()
    for yy in range(SIZE):
        for xx in range(SIZE):
            got = paint(xx - C, yy - C)
            if got:
                rgb, a = got
                px[xx, yy] = tuple(clamp(c) for c in rgb) + (clamp(a),)
    # The game measures a bitmap glyph by its last column that is not fully transparent, and moves the pen that far
    # and one more. TreeGlyphs counts on SIZE + 1 for every sprite, so the last column always holds a pixel — of an
    # alpha so low that the text shader throws it away.
    if all(px[SIZE - 1, yy][3] == 0 for yy in range(SIZE)):
        px[SIZE - 1, 0] = (0, 0, 0, 1)
    return img


def sprites(style):
    out = {}
    for state in FRAMES:
        out["frame_" + state] = _image(lambda x, y, s=state: style.frame(s, x, y))
    for state in MASTERIES:
        out["mastery_" + state] = _image(lambda x, y, s=state: style.mastery(s, x, y))
    for lit in (False, True):
        tag = "_lit" if lit else "_dim"
        out["across" + tag] = _image(lambda x, y, l=lit: style.line(x + C, y, l))
        out["down" + tag] = _image(lambda x, y, l=lit: style.line(y + C, x, l))
        out["falling" + tag] = _image(lambda x, y, l=lit: style.line((x + y) / math.sqrt(2) + C * math.sqrt(2),
                                                                     (y - x) / math.sqrt(2), l))
        out["rising" + tag] = _image(lambda x, y, l=lit: style.line((x - y) / math.sqrt(2) + C * math.sqrt(2),
                                                                    (x + y) / math.sqrt(2), l))
    return out


def write(assets, namespace, font="tree", style=None):
    """Writes textures/font/<font>/<sprite>.png and font/<font>.json under assets; returns the files written."""
    images = sprites(style or Style())
    written = []
    tex = os.path.join(assets, "textures", "font", font)
    os.makedirs(tex, exist_ok=True)
    providers = []
    for index, name in enumerate(SPRITES):
        images[name].save(os.path.join(tex, name + ".png"))
        written.append("assets/%s/textures/font/%s/%s.png" % (namespace, font, name))
        for row in range(ROWS):
            providers.append({"type": "bitmap", "file": "%s:font/%s/%s.png" % (namespace, font, name),
                              "ascent": -18 * row, "height": SIZE,
                              "chars": [chr(FIRST_SPRITE + index * ROWS + row)]})
    advances = {}
    for power in range(8):
        advances[chr(FIRST_SPACE + power)] = -(1 << power)
        advances[chr(FIRST_SPACE + 8 + power)] = 1 << power
    providers.append({"type": "space", "advances": advances})
    os.makedirs(os.path.join(assets, "font"), exist_ok=True)
    with open(os.path.join(assets, "font", font + ".json"), "w") as out:
        json.dump({"providers": providers}, out, indent=1)
    written.append("assets/%s/font/%s.json" % (namespace, font))
    return written, images


def sheet(images, path, scale=6):
    """All sprites side by side, big, for looking at."""
    w = (SIZE + 2) * len(SPRITES)
    out = Image.new("RGBA", (w, SIZE + 2), (40, 40, 48, 255))
    for i, name in enumerate(SPRITES):
        out.alpha_composite(images[name], (1 + i * (SIZE + 2), 1))
    out.resize((w * scale, (SIZE + 2) * scale), Image.NEAREST).save(path)


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    pack = os.path.join(here, "..", "..", "src", "main", "resources", "de", "raindancer", "core", "pack")
    files, imgs = write(os.path.join(pack, "assets", "rainscore"), "rainscore")
    listing = os.path.join(pack, "files.txt")
    known = [line.strip() for line in open(listing) if line.strip()]
    known = [f for f in known if "/font/tree" not in f] + files
    with open(listing, "w") as out:
        out.write("\n".join(known) + "\n")
    sheet(imgs, "/tmp/tree-brass.png")
    print(len(files), "files")
