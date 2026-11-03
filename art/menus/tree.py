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
# A mastery badge spreads into the free slots around it (TreeSprite.MASTERY_SIZE), so the item fits inside it.
MASTERY_SIZE = 40
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


def shade(c, f):
    return tuple(v * f for v in c)


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
        """A mastery badge's pixel: two five-pointed stars as clean pixel art — a bigger one behind, turned a little,
        and a smaller one over it, each with a dark outline — the item in the middle. Override for a look of its own."""
        back_fill, front_fill, front_light, outline = {
            "locked": ((78, 76, 72), (112, 108, 102), (140, 136, 128), (30, 28, 26)),
            "open": ((40, 132, 84), (88, 206, 134), (170, 245, 196), (14, 52, 32)),
            "maxed": ((196, 128, 30), (250, 204, 70), (255, 240, 170), (86, 50, 10)),
        }[state]
        front, front_edge = star_shape(x, y, FRONT_SCALE)
        back, back_edge = star_shape(x, y, 1.0, BACK_TURN)
        alpha = 215 if state == "locked" else 255
        if front:
            if front_edge < 1.0:
                return outline, 255
            # one highlight: the upper-left half of the front star
            return (front_light if x + y < -2 else front_fill), alpha
        if back:
            if back_edge < 1.0 or front_edge < 1.0:
                return outline, 255
            return back_fill, alpha
        return None

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


STAR_OUTER = 19.6
STAR_INNER = 10.6
# The badge is two stars: a bigger one behind, turned a little, and a smaller one over it.
BACK_TURN = math.radians(15)
FRONT_SCALE = 0.8


def _star_points(scale=1.0, turn=0.0):
    points = []
    for k in range(10):
        a = -math.pi / 2 + turn + k * math.pi / 5
        r = (STAR_OUTER if k % 2 == 0 else STAR_INNER) * scale
        points.append((r * math.cos(a), r * math.sin(a) + 0.6))
    return points


def star_shape(x, y, scale=1.0, turn=0.0):
    """Whether (x, y) from a sprite's centre is inside a five-pointed star of that size and turn, and how far it is
    from its edge (inside or out)."""
    points = _star_points(scale, turn)
    inside = False
    nearest = 1e9
    for i in range(len(points)):
        x1, y1 = points[i]
        x2, y2 = points[(i + 1) % len(points)]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1:
            inside = not inside
        dx, dy = x2 - x1, y2 - y1
        t = max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
        nearest = min(nearest, math.hypot(x - (x1 + t * dx), y - (y1 + t * dy)))
    return inside, nearest


def size_of(name):
    return MASTERY_SIZE if name.startswith("mastery_") else SIZE


def _image(paint, size=SIZE):
    centre = (size - 1) / 2.0
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    for yy in range(size):
        for xx in range(size):
            got = paint(xx - centre, yy - centre)
            if got:
                rgb, a = got
                px[xx, yy] = tuple(clamp(c) for c in rgb) + (clamp(a),)
    # The game measures a bitmap glyph by its last column that is not fully transparent, and moves the pen that far
    # and one more. TreeGlyphs counts on SIZE + 1 for every sprite, so the last column always holds a pixel — of an
    # alpha so low that the text shader throws it away.
    if all(px[size - 1, yy][3] == 0 for yy in range(size)):
        px[size - 1, 0] = (0, 0, 0, 1)
    return img


def sprites(style):
    out = {}
    for state in FRAMES:
        out["frame_" + state] = _image(lambda x, y, s=state: style.frame(s, x, y))
    for state in MASTERIES:
        out["mastery_" + state] = _image(lambda x, y, s=state: style.mastery(s, x, y), MASTERY_SIZE)
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
                              "ascent": -18 * row + (size_of(name) - SIZE) // 2, "height": size_of(name),
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
    w = sum(size_of(name) + 2 for name in SPRITES)
    h = MASTERY_SIZE + 2
    out = Image.new("RGBA", (w, h), (40, 40, 48, 255))
    x = 1
    for name in SPRITES:
        out.alpha_composite(images[name], (x, 1 + (MASTERY_SIZE - size_of(name)) // 2))
        x += size_of(name) + 2
    out.resize((w * scale, h * scale), Image.NEAREST).save(path)


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
