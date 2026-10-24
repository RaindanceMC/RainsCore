"""
A local look at a backdrop as the game will draw it: the painting, the title and "Inventory" in the game's font,
and a few items in the slots. Needs the vanilla client jar's font and item textures unpacked somewhere (they are
only read, never shipped):

    python3 preview.py <painting.png> <rows> "<title>" <title-rgb-hex> <out.png> [vanilla-assets-dir]
"""
import os
import sys

from PIL import Image

from canvas import chest_well, inventory_well, hotbar_well, label_y

VANILLA = os.environ.get("VANILLA", "/tmp/vanillaref/assets/minecraft/textures")


def glyphs():
    sheet = Image.open(os.path.join(VANILLA, "font", "ascii.png")).convert("RGBA")
    cell = sheet.width // 16
    table = {}
    for code in range(32, 127):
        gx, gy = code % 16 * cell, code // 16 * cell
        g = sheet.crop((gx, gy, gx + cell, gy + cell))
        bbox = g.getbbox()
        width = (bbox[2] if bbox else 0) + 1
        table[chr(code)] = (g, 4 if code == 32 else width)
    return table


def text(img, x, y, s, rgb, font):
    for ch in s:
        g, w = font.get(ch, font["?"])
        tinted = Image.new("RGBA", g.size, rgb + (0,))
        tinted.putalpha(g.getchannel("A"))
        img.alpha_composite(tinted, (x, y))
        x += w


def item(name):
    for sub in ("item", "block"):
        p = os.path.join(VANILLA, sub, name + ".png")
        if os.path.exists(p):
            im = Image.open(p).convert("RGBA")
            return im.crop((0, 0, 16, 16))
    return None


def render(painting, rows, title, ink, items=None, inventory=True, offset=(0, 0)):
    """offset: where the window's corner is inside the painting, for one that reaches past the window."""
    font = glyphs()
    base = Image.new("RGBA", (painting.width + 16, painting.height + 16), (40, 44, 52, 255))
    base.alpha_composite(painting, (8, 8))
    ox, oy = 8 + offset[0], 8 + offset[1]
    text(base, ox + 8, oy + 6, title, ink, font)
    if inventory:
        text(base, ox + 8, oy + label_y(rows), "Inventory", (64, 64, 64), font)
    for (slot, name) in (items or {}).items():
        im = item(name)
        if im is None:
            continue
        if slot < rows * 9:
            x, y = chest_well(slot // 9, slot % 9)
        elif slot < rows * 9 + 27:
            s = slot - rows * 9
            x, y = inventory_well(rows, s // 9, s % 9)
        else:
            x, y = hotbar_well(rows, slot - rows * 9 - 27)
        base.alpha_composite(im, (ox + x + 1, oy + y + 1))
    return base


DEMO = {10: "diamond_sword", 12: "emerald", 14: "golden_apple", 16: "map", 20: "compass", 22: "book",
        24: "clock", 45: "arrow", 49: "barrier", 53: "knowledge_book"}

if __name__ == "__main__":
    painting = Image.open(sys.argv[1]).convert("RGBA")
    rows = int(sys.argv[2])
    ink = int(sys.argv[4], 16)
    items = {k: v for k, v in DEMO.items() if k < rows * 9}
    items[rows * 9 + 27] = "iron_pickaxe"
    items[rows * 9 + 28] = "bread"
    items[rows * 9 + 4] = "oak_log"
    out = render(painting, rows, sys.argv[3], (ink >> 16 & 255, ink >> 8 & 255, ink & 255), items)
    out.resize((out.width * 3, out.height * 3), Image.NEAREST).save(sys.argv[5])
