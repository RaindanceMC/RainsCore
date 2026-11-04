"""
The menu arrows: brass triangles — Back (|◀), and left, right, up and down for pages and scrolling. 16 x 16 items in
the pack (rainscore:ui/arrow_*), shown instead of the vanilla arrow to a viewer wearing it (Arrow, Icons.arrow).

    python3 arrows.py        # writes the pack files and /tmp/arrows-preview.png
"""
import json
import os

from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.path.join(HERE, "..", "..", "src", "main", "resources", "de", "raindancer", "core", "pack")
ASSETS = os.path.join(PACK, "assets", "rainscore")

N = 16
SS = 16  # the shapes are drawn this much bigger and thresholded back to crisp pixels

LIGHT = (255, 236, 160)
MID = (226, 172, 60)
DARK = (168, 110, 34)
SHADOW = (20, 12, 6, 170)


def mask(draw):
    big = Image.new("L", (N * SS, N * SS), 0)
    draw(ImageDraw.Draw(big), SS)
    small = big.resize((N, N), Image.BOX).load()
    return {(x, y) for y in range(N) for x in range(N) if small[x, y] >= 128}


def right(d, s):
    d.polygon([(4.5 * s, 2.5 * s), (12.8 * s, 8 * s), (4.5 * s, 13.5 * s)], fill=255)


def back(d, s):
    d.polygon([(12.5 * s, 2.5 * s), (4.6 * s, 8 * s), (12.5 * s, 13.5 * s)], fill=255)
    d.rectangle([(2.2 * s, 2.6 * s), (4.0 * s, 13.4 * s)], fill=255)


def turned(cells, quarter_turns):
    for _ in range(quarter_turns % 4):
        cells = {(N - 1 - y, x) for x, y in cells}
    return cells


def paint(cells):
    """Brass lit from the top left, darker down the bottom-right edges, a soft shadow below and right of it."""
    img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    px = img.load()
    for x, y in cells:
        if (x + 1, y + 1) not in cells and x + 1 < N and y + 1 < N:
            px[x + 1, y + 1] = SHADOW
    for x, y in cells:
        lit = (x, y - 1) not in cells or (x - 1, y) not in cells
        low = (x, y + 1) not in cells or (x + 1, y) not in cells
        px[x, y] = (LIGHT if lit and not low else DARK if low and not lit else MID) + (255,)
    return img


def shapes():
    r = mask(right)
    return {
        "arrow_back": mask(back),
        "arrow_left": {(N - 1 - x, y) for x, y in r},
        "arrow_right": r,
        "arrow_up": turned(r, 3),
        "arrow_down": turned(r, 1),
    }


def write():
    written = []
    for name, cells in shapes().items():
        texture = os.path.join(ASSETS, "textures", "item", "ui", name + ".png")
        os.makedirs(os.path.dirname(texture), exist_ok=True)
        paint(cells).save(texture)
        for kind, data in (("items", {"model": {"type": "minecraft:model", "model": "rainscore:item/ui/" + name}}),
                           ("models/item", {"parent": "minecraft:item/generated",
                                            "textures": {"layer0": "rainscore:item/ui/" + name}})):
            path = os.path.join(ASSETS, kind, "ui", name + ".json")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as out:
                json.dump(data, out, indent=1)
            written.append("assets/rainscore/%s/ui/%s.json" % (kind, name))
        written.append("assets/rainscore/textures/item/ui/%s.png" % name)
    listing = os.path.join(PACK, "files.txt")
    known = [line.strip() for line in open(listing) if line.strip()]
    with open(listing, "w") as out:
        out.write("\n".join(sorted(set(known) | set(written))) + "\n")
    return written


def preview(path):
    """The chrome row of a painted three-row window with the arrows where menus put them, five times the size."""
    row = Image.open(os.path.join(ASSETS, "textures", "font", "menu", "house_3.png")).convert("RGBA").crop(
        (0, 53, 176, 72))
    painted = {name: paint(cells) for name, cells in shapes().items()}
    for column, name in ((0, "arrow_back"), (2, "arrow_up"), (3, "arrow_left"), (5, "arrow_right"),
                         (6, "arrow_down")):
        row.alpha_composite(painted[name], (8 + 18 * column, 1))
    row.resize((row.width * 5, row.height * 5), Image.NEAREST).save(path)


if __name__ == "__main__":
    print(len(write()), "files")
    preview("/tmp/arrows-preview.png")
