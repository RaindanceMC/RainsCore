"""
Core's menu backdrops — the oak-and-brass cabinet every menu gets, and riveted steel for the staff screens — one
painting per window height, plus the font that draws them and the invisible filler item.

    python3 menus.py        # writes into src/main/resources/de/raindancer/core/pack, and previews here
"""
import json
import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from canvas import (Canvas, W, chest_well, inventory_well, hotbar_well, label_y, height, wood, metal, rivet, well,
                    plaque, vignette, drop_shadow, mix, shade)

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "..", "..", "src", "main", "resources", "de", "raindancer", "core", "pack")
NS = "rainscore"
ASSETS = os.path.join(PACK, "assets", NS)

BRASS = (212, 166, 70)
BRASS_DARK = (120, 82, 30)
BRASS_LIGHT = (255, 228, 150)


def brass_bracket(c, x, y, dx, dy, arm=12):
    """An L-shaped brass corner fitting pointing (dx, dy) into the window, with an engraved line and a rivet."""
    def at(i, t):
        return (x + dx * i, y + dy * t), (x + dx * t, y + dy * i)
    for i in range(arm):
        width = 3 if i < arm - 3 else 2
        for t in range(width):
            for px, py in at(i, t):
                c.put(px, py, shade(BRASS, (1.12, 1.0, 0.82)[t]))
        for px, py in at(i, width):
            c.put(px, py, (34, 20, 10), 170)
    for i in range(3, arm - 2):
        for px, py in at(i, 1):
            c.put(px, py, BRASS_DARK, 110)
    c.put(x, y, BRASS_LIGHT)
    rivet(c, x + dx * 2, y + dy * 2, BRASS, BRASS_DARK, BRASS_LIGHT)


def regions(rows):
    h = height(rows)
    return h, 17, 17 + 18 * rows, 30 + 18 * rows, 88 + 18 * rows


# ------------------------------------------------------------------------------------------------ house

def house(rows):
    h, chest_top, chest_bottom, inv_top, hot_top = regions(rows)
    c = Canvas(W, h, seed=rows)
    WALNUT, WALNUT_D, WALNUT_L = (98, 61, 36), (48, 27, 15), (156, 106, 64)
    OAK, OAK_D, OAK_L = (150, 104, 62), (98, 62, 34), (204, 156, 102)
    INK = (26, 14, 7)

    wood(c, 0, 0, W, h, WALNUT, WALNUT_D, WALNUT_L, seed=11 + rows)
    vignette(c, 0, 0, W, h, WALNUT_D, 0.45, 6)

    # the nameboard the title is written on: a shallow carved recess
    nx0, ny0, nx1, ny1 = 5, 3, W - 5, 15
    for y in range(ny0, ny1):
        for x in range(nx0, nx1):
            c.tint(x, y, WALNUT_D, 0.35)
    c.hline(nx0, nx1, ny0, INK, 150)
    c.vline(nx0, ny0, ny1, INK, 120)
    c.hline(nx0, nx1, ny1, WALNUT_L, 120)
    c.vline(nx1, ny0, ny1 + 1, WALNUT_L, 90)

    def panel(x0, y0, x1, y1, seed):
        wood(c, x0, y0, x1, y1, OAK, OAK_D, OAK_L, seed=seed)
        c.outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, INK, corner=False)
        c.hline(x0 - 1, x1 + 1, y1, WALNUT_L, 140)
        c.vline(x1, y0 - 1, y1 + 1, WALNUT_L, 140)

    panel(6, chest_top - 1, W - 6, chest_bottom + 1, 100 + rows)
    panel(6, inv_top - 1, W - 6, hot_top + 19, 200 + rows)

    WELL, RIM_D, RIM_L = (176, 132, 84), (92, 58, 32), (224, 188, 136)
    for r in range(rows):
        for col in range(9):
            x, y = chest_well(r, col)
            if r == rows - 1:
                well(c, x, y, (112, 76, 46), (40, 24, 12), (168, 122, 76), 0.3)
            else:
                well(c, x, y, WELL, RIM_D, RIM_L, 0.26, show=0.3)
    # the shelf the navigation sits on: a brass rail along its top, capped at both ends
    cy = chest_well(rows - 1, 0)[1]
    if rows > 1:
        for x in range(4, W - 4):
            c.put(x, cy - 1, mix(BRASS, BRASS_LIGHT, 0.4))
            c.put(x, cy, BRASS_DARK)
        for x in range(5, W - 5, 20):
            rivet(c, x + 2, cy - 1, BRASS, BRASS_DARK, BRASS_LIGHT)
    for x0 in (2, W - 6):
        c.rect(x0, cy + 2, x0 + 4, cy + 16, BRASS)
        c.bevel(x0, cy + 2, x0 + 4, cy + 16, BRASS_LIGHT, BRASS_DARK, 1.0)
        c.outline(x0 - 1, cy + 1, x0 + 5, cy + 17, INK)
        rivet(c, x0 + 1, cy + 8, BRASS, BRASS_DARK, BRASS_LIGHT)

    for r in range(3):
        for col in range(9):
            x, y = inventory_well(rows, r, col)
            well(c, x, y, WELL, RIM_D, RIM_L, 0.26, show=0.3)
    gy = hot_top - 2
    c.hline(6, W - 6, gy, INK)
    c.hline(6, W - 6, gy + 1, OAK_L, 120)
    for col in range(9):
        x, y = hotbar_well(rows, col)
        well(c, x, y, WELL, RIM_D, RIM_L, 0.26, show=0.3)

    # the label the game writes "Inventory" on, in its fixed dark grey: parchment in a brass frame
    ly = label_y(rows)
    lx0, lx1 = 6, 60
    for y in range(ly - 2, ly + 9):
        for x in range(lx0, lx1):
            e = min(x - lx0, y - (ly - 2), lx1 - 1 - x, ly + 8 - y)
            col = mix((236, 220, 180), (206, 180, 132), c.rng.random() * 0.25 + (0.5 if e == 0 else 0))
            c.put(x, y, col)
    c.outline(lx0 - 1, ly - 3, lx1 + 1, ly + 10, BRASS_DARK)
    c.hline(lx0, lx1, ly - 3, BRASS)
    c.hline(lx0 - 1, lx1 + 2, ly + 10, INK, 160)
    c.vline(lx1 + 1, ly - 2, ly + 11, INK, 160)

    brass_bracket(c, 0, 0, 1, 1)
    brass_bracket(c, W - 1, 0, -1, 1)
    brass_bracket(c, 0, h - 1, 1, -1)
    brass_bracket(c, W - 1, h - 1, -1, -1)
    c.round_corners(1)
    return c


# ---------------------------------------------------------------------------------------------- workshop

def workshop(rows):
    h, chest_top, chest_bottom, inv_top, hot_top = regions(rows)
    c = Canvas(W, h, seed=50 + rows)
    STEEL, STEEL_D, STEEL_L = (78, 86, 96), (36, 40, 47), (150, 162, 174)
    SLATE = (40, 52, 66)
    COPPER, COPPER_D, COPPER_L = (186, 110, 64), (96, 50, 26), (240, 170, 120)
    AMBER = (232, 168, 48)

    metal(c, 0, 0, W, h, STEEL, STEEL_D, STEEL_L, seed=rows)
    # the dark nameplate the title is stencilled on
    for y in range(3, 15):
        for x in range(5, W - 5):
            c.put(x, y, shade((50, 56, 64), 1.0 + 0.04 * ((x * 7 + y * 3) % 3 - 1)))
    c.hline(5, W - 5, 3, (14, 16, 20))
    c.vline(5, 3, 15, (14, 16, 20))
    c.hline(5, W - 4, 15, STEEL_L, 150)
    c.vline(W - 5, 3, 16, STEEL_L, 120)
    # plate seams
    for y in (chest_bottom + 2,):
        c.hline(2, W - 2, y, STEEL_D)
        c.hline(2, W - 2, y + 1, STEEL_L, 110)
    c.bevel(1, 1, W - 1, h - 1, STEEL_L, STEEL_D, 1.0, 2)

    def bay(x0, y0, x1, y1):
        for y in range(y0, y1):
            for x in range(x0, x1):
                grid = (x - x0) % 9 == 0 or (y - y0) % 9 == 0
                c.put(x, y, mix(SLATE, (70, 110, 140), 0.18) if grid else SLATE)
        vignette(c, x0, y0, x1, y1, (10, 14, 20), 0.5, 4)
        c.outline(x0 - 1, y0 - 1, x1 + 1, y1 + 1, (18, 20, 24), corner=False)
        c.hline(x0 - 1, x1 + 1, y1, STEEL_L, 140)
        c.vline(x1, y0 - 1, y1 + 1, STEEL_L, 140)

    bay(5, chest_top - 1, W - 5, chest_bottom + 1)
    bay(5, inv_top - 1, W - 5, hot_top + 19)

    WELL, RIM_D, RIM_L = (84, 96, 110), (24, 28, 34), (150, 164, 178)
    for r in range(rows):
        chrome = r == rows - 1
        for col in range(9):
            x, y = chest_well(r, col)
            well(c, x, y, (44, 36, 24) if chrome else WELL, RIM_D, (176, 124, 42) if chrome else RIM_L, 0.45)
    # hazard stripes either side of the chrome row
    cy = chest_well(rows - 1, 0)[1]
    for x0 in (1, W - 6):
        for y in range(cy + 1, cy + 17):
            for x in range(x0, x0 + 5):
                stripe = ((x + y) // 3) % 2 == 0
                c.put(x, y, AMBER if stripe else (30, 30, 32))
        c.outline(x0 - 1, cy, x0 + 6, cy + 18, (18, 20, 24), corner=False)

    for r in range(3):
        for col in range(9):
            x, y = inventory_well(rows, r, col)
            well(c, x, y, WELL, RIM_D, RIM_L, 0.45)
    gy = hot_top - 2
    c.hline(6, W - 6, gy, (18, 20, 24))
    c.hline(6, W - 6, gy + 1, STEEL_L, 90)
    for col in range(9):
        x, y = hotbar_well(rows, col)
        well(c, x, y, WELL, RIM_D, RIM_L, 0.45)

    ly = label_y(rows)
    plaque(c, 6, ly - 2, 62, ly + 9, (196, 206, 214), (40, 46, 54), (240, 246, 250), seed=rows)
    for x in (4, 63):
        rivet(c, x, ly + 3, COPPER, COPPER_D, COPPER_L)

    # rivets along the frame
    for x in range(6, W - 4, 14):
        rivet(c, x, h - 4, STEEL_L, STEEL_D, (230, 236, 240))
    for (x, y) in ((4, 4), (W - 5, 4), (4, h - 4), (W - 5, h - 4)):
        rivet(c, x, y, COPPER, COPPER_D, COPPER_L)
    c.outline(0, 0, W, h, (14, 16, 20))
    c.round_corners(2)
    return c


# ------------------------------------------------------------------------------------------------- pack

def write_pack():
    files = []

    def out(rel, data=None, image=None):
        path = os.path.join(PACK, rel)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        if image is not None:
            image.save(path)
        else:
            with open(path, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=1)
        files.append(rel)

    out("pack.mcmeta", {"pack": {"description": "Rain's Core — menus", "min_format": 88, "max_format": 97}})
    providers = []
    sheets = []
    for rows in range(1, 7):
        for style, paint, base in (("house", house, 0xE000), ("workshop", workshop, 0xE010)):
            c = paint(rows)
            name = f"{style}_{rows}"
            out(f"assets/{NS}/textures/font/menu/{name}.png", image=c.img)
            providers.append({"type": "bitmap", "file": f"{NS}:font/menu/{name}.png", "ascent": 13,
                              "height": c.h, "chars": [chr(base + rows)]})
            sheets.append((name, c.img))
    providers.append({"type": "space", "advances": {"": -8, "": -169}})
    out(f"assets/{NS}/font/menu.json", {"providers": providers})

    import title_font
    sheet, title = title_font.provider(NS)
    out(f"assets/{NS}/textures/font/title.png", image=sheet)
    out(f"assets/{NS}/font/title.json", {"providers": [
        {"type": "space", "advances": {" ": 4}},
        title,
        {"type": "reference", "id": "minecraft:include/default"}]})

    out(f"assets/{NS}/items/ui/blank.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/ui/blank"}})
    out(f"assets/{NS}/models/item/ui/blank.json", {"parent": "minecraft:item/generated",
                                                    "textures": {"layer0": f"{NS}:item/ui/blank"}})
    out(f"assets/{NS}/textures/item/ui/blank.png", image=Image.new("RGBA", (16, 16), (0, 0, 0, 0)))

    with open(os.path.join(PACK, "files.txt"), "w") as f:
        f.write("\n".join(sorted(files)) + "\n")

    # previews: each painting at 3x, six rows of house and workshop side by side
    for style in ("house", "workshop"):
        imgs = [img for name, img in sheets if name.startswith(style)]
        sheet = Image.new("RGBA", (sum(i.width for i in imgs) + 8 * 5, max(i.height for i in imgs)), (32, 32, 36, 255))
        x = 0
        for i in imgs:
            sheet.alpha_composite(i, (x, 0))
            x += i.width + 8
        sheet.resize((sheet.width * 2, sheet.height * 2), Image.NEAREST).save(os.path.join(HERE, f"{style}.png"))
    print(len(files), "files")


if __name__ == "__main__":
    write_pack()
