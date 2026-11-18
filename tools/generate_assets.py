"""Build Valkron's authored models, pixel textures, recipes and synthesized audio.

Run with Python 3 and Pillow, NumPy and soundfile installed. The generated
resources are checked in, so Gradle can build the mod without Python.
"""

from pathlib import Path
import json
import math
import random

import numpy as np
from PIL import Image, ImageDraw
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources"
ASSETS = ROOT / "assets/valkron"
DATA = ROOT / "data/valkron"
SIDES = ("north", "south", "east", "west", "up", "down")

# name, ammo, glow, ceramic, recipe pattern, ammo ingredients, ammo yield
KITS = {
    "pulse_rifle": ("Pulse Rifle", "Pulse Cell", "#39bcff", "#8aadc6",
                    ["ICR", "ISI", " S "], ["copper_ingot", "redstone"], 48),
    "plasma_cannon": ("Plasma Cannon", "Plasma Capsule", "#a6f348", "#788b53",
                      ["GCG", "IRI", " S "], ["glass", "charcoal", "redstone"], 32),
    "railgun": ("Railgun", "Rail Slug", "#ffb34d", "#b5a38d",
                ["III", "CRC", "  S"], ["iron_nugget", "copper_ingot"], 48),
    "laser_carbine": ("Laser Carbine", "Laser Cell", "#ff526d", "#beb4bb",
                      [" G ", "CRC", " IS"], ["glass", "redstone"], 64),
    "ion_blaster": ("Ion Blaster", "Ion Shell", "#b594ff", "#9184b3",
                    ["CIC", " RS", " I "], ["copper_ingot", "charcoal"], 40),
}
AMMO = ("pulse_cell", "plasma_capsule", "rail_slug", "laser_cell", "ion_shell")
RELOAD_TIMES = (29, 47, 39, 23, 14)


def json_file(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def save_image(path, image):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def rgb(hex_color):
    return tuple(bytes.fromhex(hex_color.removeprefix("#")))


def mix(first, second, weight):
    return tuple(round(a + (b - a) * weight) for a, b in zip(first, second))


MATERIALS = ("ceramic", "frame", "rubber", "coil", "glass", "vent", "bolt", "void",
             "emitter", "warning", "panel", "flash_x", "flash_y")
GLOWING = {"coil", "glass", "emitter", "flash_x", "flash_y"}


def atlas(weapon_index, glow, ceramic):
    image = Image.new("RGBA", (128, 128))
    white = (245, 251, 255)
    dark = (19, 25, 34)
    aluminum = (112, 129, 147)
    rng = random.Random(544 + weapon_index)
    colors = (ceramic, (56, 69, 82), (31, 36, 46), mix(glow, dark, 0.4),
              mix(glow, white, 0.25), (42, 53, 65), aluminum, (10, 15, 23),
              glow, (139, 106, 65), mix(ceramic, dark, 0.36))
    for index, base in enumerate(colors):
        tile = Image.new("RGBA", (16, 16))
        for y in range(16):
            for x in range(16):
                bevel = 0.30 if y < 2 or x == 0 else -0.44 if y > 13 or x == 15 else 0
                patina = ((15 - y) / 15 - 0.5) * 0.12 + rng.uniform(-0.027, 0.027)
                amount = bevel + patina
                tile.putpixel((x, y), mix(base, white if amount > 0 else dark, abs(amount)) + (255,))
        draw = ImageDraw.Draw(tile)
        mat = MATERIALS[index]
        if mat in ("ceramic", "panel"):
            draw.line((1, 11, 14, 11), fill=mix(base, dark, 0.43))
            draw.line((3, 12, 12, 12), fill=mix(base, white, 0.32))
            draw.point([(2, 3), (13, 3), (2, 13), (13, 13)], fill=dark)
            if mat == "panel":
                for stripe in range(weapon_index + 1):
                    draw.line((4 + stripe * 2, 7, 5 + stripe * 2, 5), fill=glow)
        elif mat in ("vent", "rubber"):
            for stripe in range(2, 15, 3):
                draw.line((1, stripe, 14, stripe), fill=mix(base, dark, 0.80))
                draw.line((2, stripe + 1, 13, stripe + 1), fill=mix(base, white, 0.17))
        elif mat in ("coil", "glass", "emitter"):
            draw.line((1, 3, 14, 3), fill=mix(glow, white, 0.68), width=2)
            draw.rectangle((2, 6, 13, 10), fill=mix(glow, white, 0.23))
            draw.line((4, 8, 11, 8), fill=white)
            draw.line((2, 12, 13, 12), fill=mix(glow, dark, 0.37))
            if mat == "coil":
                for x in (3, 8, 13):
                    draw.line((x, 1, x, 14), fill=mix(glow, dark, 0.58))
        elif mat == "warning":
            for x in range(-8, 17, 5):
                draw.line((x, 13, x + 9, 2), fill=(35, 34, 39), width=2)
        image.paste(tile, (index % 8 * 16, index // 8 * 16))

    flare = Image.new("RGBA", (16, 16))
    draw = ImageDraw.Draw(flare)
    draw.polygon([(0, 7), (5, 5), (3, 2), (7, 5), (14, 4), (11, 7), (15, 8),
                  (10, 9), (12, 12), (7, 10), (4, 14), (5, 10), (0, 9)],
                 fill=mix(glow, dark, 0.22) + (179,))
    draw.polygon([(0, 8), (5, 7), (7, 5), (8, 7), (14, 8), (8, 9), (6, 11), (5, 9)],
                 fill=glow + (255,))
    draw.line((1, 8, 10, 8), fill=mix(glow, white, 0.83) + (255,))
    for index, tile in [(11, flare.transpose(Image.Transpose.ROTATE_90)), (12, flare)]:
        image.paste(tile, (index % 8 * 16, index // 8 * 16))
    return image


def part(start, stop, material, **faces):
    surfaces = {}
    for side in SIDES:
        name = faces.get(side, material)
        if name is None:
            continue
        index = MATERIALS.index(name)
        u, v = index % 8 * 2, index // 8 * 2
        surface = {"texture": "#skin", "uv": [u, v, u + 2, v + 2]}
        if name in GLOWING:
            surface["neoforge_data"] = {"block_light": 15, "sky_light": 15}
        surfaces[side] = surface
    return {"from": list(start), "to": list(stop), "faces": surfaces, "shade": material not in GLOWING}


def chassis(body, grip_z, butt_z):
    body.extend([
        # Lower receiver and a recessed grip with raised anti-slip cheeks.
        part((6.0, 6.0, grip_z - 1), (10.0, 8.4, butt_z), "frame"),
        part((6.7, 1.8, grip_z + 2.8), (9.3, 7.1, grip_z + 6.1), "rubber"),
        part((6.45, 1.5, grip_z + 2.5), (9.55, 2.6, grip_z + 6.35), "frame"),
        # Trigger and the three sides of the guard.
        part((7.5, 4.5, grip_z + 1.65), (8.5, 6.45, grip_z + 2.30), "bolt"),
        part((7.15, 3.25, grip_z - 0.4), (8.85, 4.0, grip_z + 3.1), "frame"),
        part((7.15, 4, grip_z - 0.4), (8.85, 6.6, grip_z + 0.25), "frame"),
    ])
    return body


def pulse():
    body = [
        part((4.7, 7.65, 2.3), (11.3, 12.15, 15.3), "ceramic", east="panel", west="panel"),
        part((5.4, 8.4, -5.6), (10.6, 11.65, 4.5), "frame"),
        part((6.25, 9.1, -9.2), (9.75, 11.15, 1.2), "bolt", north="void"),
        part((6.75, 9.48, -9.24), (9.25, 10.75, -8.7), "emitter"),
        part((5.65, 8.4, 15.1), (10.35, 10.4, 21.4), "frame"),
        part((5.05, 6.75, 20.1), (10.95, 11.4, 22), "rubber"),
        part((7.15, 12.15, 8.4), (8.85, 13.32, 14.7), "frame"),
        part((7.55, 11.61, -0.6), (8.45, 12.9, 0.4), "frame"),
        part((7.33, 12.88, -1.5), (8.67, 13.25, 1.1), "bolt"),
    ]
    for x in (5.15, 10.15):
        body.append(part((x, 9.35, -4.9), (x + 0.7, 10.3, 4.2), "coil"))
        for z in (-4, -1.65, 0.7, 3.05):
            body.append(part((x - 0.13, 8.35, z), (x + 0.83, 11.52, z + 0.52), "ceramic"))
    magazine = [
        part((5.84, 3.4, 1.38), (10.16, 7.9, 5.75), "frame", east="panel", west="panel"),
        part((5.55, 2.9, 1.02), (10.45, 4.35, 6.13), "bolt"),
        part((5.5, 3.4, 1.7), (10.5, 4.0, 5.45), "coil"),
    ]
    return chassis(body, 7.5, 16.5), magazine, -9.3, 10.1, 2.0


def plasma():
    body = [
        part((3.2, 7.0, 4.6), (12.8, 14.3, 15.3), "ceramic", east="panel", west="panel"),
        part((5.1, 6.45, 14.7), (10.9, 12.6, 18.65), "frame", south="vent"),
        part((4.05, 7.1, -6.9), (11.95, 13.7, 5.8), "frame"),
        part((3.65, 8.25, -9.1), (12.35, 12.55, -6.45), "bolt", north="void"),
        part((4.9, 7.32, -9.0), (11.1, 13.48, -6.58), "bolt", north="void"),
        part((5.4, 8.75, -9.16), (10.6, 12.05, -8.5), "emitter"),
        part((6.2, 9.15, -9.17), (9.8, 11.65, -9.15), "void"),
        part((6.66, 9.53, -9.2), (9.34, 11.27, -9.1), "glass"),
        part((5.1, 14.3, 5.4), (10.9, 15.1, 13.5), "warning"),
    ]
    for z in (-6.3, -3.9, -1.5, 0.9):
        body.append(part((3.58, 7.44, z), (12.42, 13.35, z + 0.65), "coil"))
        body.append(part((4.05, 7.0, z + 0.62), (11.95, 13.76, z + 1.20), "ceramic"))
    magazine = [
        part((3.45, 4.05, 2.65), (12.55, 7.45, 8.95), "glass"),
        part((3.12, 3.75, 2.1), (12.88, 7.75, 3.4), "bolt"),
        part((3.1, 3.75, 8.25), (12.9, 7.75, 9.65), "frame"),
        part((3.9, 3.75, 5.27), (12.1, 4.40, 6.48), "warning"),
    ]
    return chassis(body, 9.25, 17.5), magazine, -9.3, 10.6, 3.05


def rail():
    body = [
        part((4.85, 7.4, 2.5), (11.15, 11.9, 15.8), "ceramic", east="panel", west="panel"),
        part((5.6, 7.4, 15.4), (10.4, 10.5, 23.9), "frame"),
        part((5, 6.4, 22.6), (11, 12.0, 24.65), "rubber"),
        part((7.03, 11.8, 3.8), (8.97, 12.55, 5.1), "bolt"),
        part((7.03, 11.8, 9.25), (8.97, 12.55, 10.54), "bolt"),
        part((6.6, 12.4, 1.75), (9.4, 13.15, 12.65), "bolt"),
        part((6.95, 13.0, 3.75), (9.05, 15.05, 10.15), "frame", north="glass"),
        part((5.25, 7.36, -9.0), (10.75, 8.25, 5.6), "frame"),
        part((6.2, 9.3, -8.4), (9.8, 10.65, 4.6), "coil"),
    ]
    for x in (4.9, 9.64):
        body.append(part((x, 8.65, -14.2), (x + 1.46, 10.36, 4.7), "bolt", north="emitter"))
        body.append(part((x + 0.15, 10.35, -13.75), (x + 1.31, 10.94, 4.54), "frame", up="coil"))
    for z in (-8.8, -3.0, 2.25):
        body.append(part((4.5, 8.05, z), (11.5, 9.06, z + 1.02), "ceramic"))
    magazine = [
        part((6.0, 3.65, 1.45), (10, 8.0, 6.15), "frame", east="warning", west="warning"),
        part((5.78, 3.43, 1.22), (10.22, 4.52, 6.36), "bolt"),
    ]
    return chassis(body, 8.8, 16), magazine, -14.35, 9.85, 2.65


def laser():
    body = [
        part((4.85, 7.1, -3.05), (11.15, 12.1, 11.8), "ceramic", east="panel", west="panel"),
        part((5.5, 8.22, -6.8), (10.5, 10.9, -2.5), "frame", north="void"),
        part((5.96, 8.74, -6.9), (10.04, 10.36, -6.5), "emitter"),
        part((6.65, 9.05, -6.96), (9.35, 10.0, -6.89), "glass"),
        part((5.28, 8.05, 11.65), (10.72, 10.96, 15.45), "frame", south="rubber"),
        part((5.1, 6.75, 14.3), (10.9, 11.35, 16.1), "rubber"),
        part((6.4, 12.05, -2.4), (9.6, 12.8, 8.4), "frame"),
        part((7.12, 12.74, 1.9), (8.88, 13.97, 5.6), "bolt", north="emitter"),
    ]
    for x in (4.53, 11.02):
        body.append(part((x, 9.23, -2.9), (x + 0.45, 10.16, 2.3), "coil"))
        body.append(part((x + 0.04, 8.43, 2.3), (x + 0.41, 10.67, 5.66), "vent"))
    magazine = [
        part((5.45, 5.4, -0.2), (10.55, 7.23, 6.7), "frame", down="panel"),
        part((5.16, 5.76, 1.25), (10.84, 6.89, 5.5), "emitter"),
        part((5.76, 4.98, 5.28), (10.24, 6.35, 7.0), "rubber"),
    ]
    return chassis(body, 6.7, 13.4), magazine, -7.0, 9.57, 1.8


def ion():
    body = [
        part((3.75, 7.45, 1.25), (12.25, 12.25, 12.95), "ceramic", east="panel", west="panel"),
        part((4.75, 7.13, 12.1), (11.25, 11.4, 17.5), "frame", south="rubber"),
        part((3.28, 7.8, -4.44), (12.72, 11.9, 3.7), "frame"),
        part((3.55, 11.65, -3.95), (12.45, 12.94, 1.9), "vent"),
        part((6.9, 12.1, 5.3), (9.1, 12.75, 7.5), "frame"),
        part((6.3, 12.65, 4.6), (9.7, 13.8, 8.25), "bolt"),
    ]
    for x in (3.62, 6.64, 9.66):
        body.append(part((x, 8.25, -6.75), (x + 2.72, 11.45, 1.85), "ceramic", north="void", up="vent"))
        body.append(part((x + 0.48, 8.86, -6.85), (x + 2.24, 10.84, -6.36), "emitter"))
        body.append(part((x + 0.78, 9.21, -6.92), (x + 1.94, 10.48, -6.8), "glass"))
    for x in (3.13, 12.31):
        body.append(part((x, 8.65, -3.45), (x + 0.55, 10.42, 6.9), "coil"))
    magazine = [
        part((4.6, 3.2, 1.9), (11.4, 7.6, 6.4), "frame", east="vent", west="vent"),
        part((4.22, 3.86, 2.14), (11.78, 6.66, 5.72), "coil"),
        part((5.0, 2.8, 2.42), (11.0, 4.03, 5.35), "bolt"),
    ]
    return chassis(body, 8.28, 14.3), magazine, -7.0, 9.9, 2.55


def flash(muzzle, height, scale):
    tip = max(-15.95, muzzle - 3.6 * scale)
    return [
        part((8 - scale, height - 0.02, tip),
             (8 + scale, height + 0.02, muzzle + 0.04),
             "flash_x", north=None, south=None, east=None, west=None),
        part((7.98, height - scale, tip),
             (8.02, height + scale, muzzle + 0.04),
             "flash_y", north=None, south=None, up=None, down=None),
    ]


def model(weapon, factory, index):
    body, magazine, muzzle, height, flare_scale = factory()
    scale = (0.81, 0.75, 0.71, 0.88, 0.81)[index]
    display = {
        "firstperson_righthand": {"rotation": [2, 12, 1], "translation": [-2.7, 2.8, -2.2],
                                 "scale": [scale] * 3},
        "firstperson_lefthand": {"rotation": [2, 12, 1], "translation": [-2.7, 2.8, -2.2],
                                "scale": [scale] * 3},
        "thirdperson_righthand": {"rotation": [0, 0, 0], "translation": [0, 1.4, 3.6],
                                 "scale": [0.72] * 3},
        "thirdperson_lefthand": {"rotation": [0, 0, 0], "translation": [0, 1.4, 3.6],
                                "scale": [0.72] * 3},
        "gui": {"rotation": [14, -58, 27], "translation": [0, 0.8, 0],
                "scale": [(0.47, 0.51, 0.37, 0.62, 0.55)[index]] * 3},
        "ground": {"rotation": [0, 0, 90], "translation": [0, 3, 0], "scale": [0.32] * 3},
        "fixed": {"rotation": [0, -90, 0], "scale": [0.42] * 3},
    }
    base = {
        "render_type": "minecraft:cutout",
        "ambientocclusion": False,
        "gui_light": "side",
        "textures": {"skin": "valkron:item/" + weapon, "particle": "valkron:item/" + weapon},
        "display": display,
    }
    # The animation swaps to the open receiver while the viewmodel rolls down.
    models = {
        weapon: dict(base, elements=body + magazine, overrides=[
            {"predicate": {"valkron:action": 0.5}, "model": "valkron:item/" + weapon + "_open"},
            {"predicate": {"valkron:action": 1}, "model": "valkron:item/" + weapon + "_flash"},
        ]),
        weapon + "_open": dict(base, elements=body),
        weapon + "_flash": dict(base, elements=body + magazine + flash(muzzle, height, flare_scale)),
    }
    for name, payload in models.items():
        json_file(ASSETS / "models/item" / (name + ".json"), payload)


def ammo_icon(index, glow):
    image = Image.new("RGBA", (32, 32))
    draw = ImageDraw.Draw(image)
    white = (232, 242, 246)
    dark = (23, 29, 40)
    steel = (90, 115, 139)
    shade = mix(glow, dark, 0.50)
    cases = [
        [(7, 3), (21, 3), (25, 7), (25, 24), (21, 29), (7, 29), (5, 26), (5, 6)],
        [(12, 2), (20, 2), (25, 7), (27, 13), (26, 22), (21, 29), (11, 29), (6, 22), (5, 13), (7, 7)],
        [(15, 3), (23, 3), (26, 8), (25, 11), (12, 28), (6, 29), (3, 25), (4, 20)],
        [(7, 4), (24, 4), (28, 8), (28, 15), (24, 19), (10, 19), (10, 27), (5, 29), (3, 25), (3, 8)],
        [(9, 3), (23, 3), (25, 8), (25, 24), (21, 29), (11, 29), (7, 24), (7, 8)],
    ]
    draw.polygon(cases[index], fill=steel, outline=dark, width=2)
    if index == 0:
        draw.rectangle((9, 7, 20, 23), fill=shade, outline=dark)
        for y in (9, 14, 19):
            draw.rectangle((11, y, 18, y + 2), fill=glow)
            draw.line((11, y, 17, y), fill=white)
        draw.line((8, 26, 20, 26), fill=white, width=1)
    elif index == 1:
        draw.ellipse((9, 7, 23, 24), fill=shade, outline=dark, width=1)
        draw.ellipse((11, 8, 21, 21), fill=glow)
        draw.ellipse((12, 9, 16, 15), fill=white)
        draw.line((8, 20, 23, 22), fill=dark, width=2)
    elif index == 2:
        draw.polygon([(16, 6), (22, 6), (23, 9), (11, 25), (7, 26), (6, 23)], fill=shade)
        draw.line((19, 8, 8, 24), fill=glow, width=3)
        draw.line((20, 7, 16, 12), fill=white, width=2)
        draw.line((10, 15, 17, 21), fill=dark, width=2)
    elif index == 3:
        draw.rectangle((7, 8, 24, 14), fill=shade)
        draw.line((9, 10, 22, 10), fill=glow, width=3)
        draw.line((10, 10, 21, 10), fill=white)
        draw.rectangle((6, 19, 8, 25), fill=glow)
    else:
        draw.rectangle((10, 8, 22, 22), fill=shade)
        draw.line([(17, 8), (13, 15), (20, 15), (14, 22)], fill=glow, width=3)
        draw.line([(17, 8), (13, 15), (20, 15), (14, 22)], fill=white)
    return image


def projectile_texture():
    image = Image.new("RGBA", (32, 32))
    for y in range(32):
        for x in range(32):
            dx, dy = (x - 15.5) / 16, (y - 15.5) / 16
            radius = math.hypot(dx, dy)
            core = math.exp(-9.5 * radius * radius)
            rays = math.exp(-min(abs(dx), abs(dy)) * 22) * max(0.0, 0.85 - radius) ** 1.6
            alpha = round(255 * min(1, core + rays) * min(1, max(0, (1 - radius) * 6)))
            image.putpixel((x, y), (255, 255, 255, alpha))
    return image


def recipes(weapon, ammo, shape, ingredients, amount):
    materials = {"I": "iron_ingot", "C": "copper_ingot", "R": "redstone", "S": "stick", "G": "glass"}
    json_file(DATA / "recipe" / (weapon + ".json"), {
        "type": "minecraft:crafting_shaped",
        "category": "equipment",
        "pattern": shape,
        "key": {symbol: {"item": "minecraft:" + materials[symbol]}
                for symbol in sorted(set("".join(shape)) - {" "})},
        "result": {"id": "valkron:" + weapon, "count": 1},
    })
    json_file(DATA / "recipe" / (ammo + ".json"), {
        "type": "minecraft:crafting_shapeless",
        "category": "equipment",
        "ingredients": [{"item": "minecraft:" + ingredient} for ingredient in ingredients],
        "result": {"id": "valkron:" + ammo, "count": amount},
    })
    for item, material in [(weapon, "iron_ingot"), (ammo, ingredients[0])]:
        recipe_id = "valkron:" + item
        json_file(DATA / "advancement/recipes/equipment" / (item + ".json"), {
            "parent": "minecraft:recipes/root",
            "criteria": {
                "has_ingredient": {
                    "trigger": "minecraft:inventory_changed",
                    "conditions": {"items": [{"items": "minecraft:" + material}]},
                },
                "has_recipe": {"trigger": "minecraft:recipe_unlocked", "conditions": {"recipe": recipe_id}},
            },
            "requirements": [["has_ingredient", "has_recipe"]],
            "rewards": {"recipes": [recipe_id]},
        })


RATE = 32_000
RNG = np.random.default_rng(45712)


def time(seconds):
    return np.arange(round(seconds * RATE)) / RATE


def envelope(t, attack, decay):
    return (1 - np.exp(-t / attack)) * np.exp(-t / decay)


def sweep(t, start, low, bend, modulation=0):
    phase = low * t + (start - low) * bend * (1 - np.exp(-t / bend))
    return np.sin(2 * np.pi * phase + modulation * np.sin(2 * np.pi * phase * 1.43))


def noise(t, smoothing=7):
    raw = RNG.uniform(-1, 1, len(t))
    return np.convolve(raw, np.hanning(smoothing + 2)[1:-1] / smoothing, "same") * math.sqrt(smoothing) * 2


def add_sound(base, when, sound):
    start = round(when * RATE)
    count = min(len(sound), len(base) - start)
    base[start:start + count] += sound[:count]


def click(seconds, pitch, depth, weight=1):
    t = time(seconds)
    return weight * envelope(t, 0.0011, seconds * 0.25) * (
            0.49 * noise(t, 3) + 0.34 * sweep(t, pitch, pitch * 0.55, 0.012, 1.4)
            + 0.33 * sweep(t, depth, depth * 0.46, 0.014))


def shot(index):
    t = time((0.33, 0.77, 0.62, 0.23, 0.49)[index])
    attack = envelope(t, 0.0018, (0.053, 0.13, 0.087, 0.037, 0.080)[index])
    if index == 0:
        wave = attack * (0.66 * sweep(t, 780, 135, 0.035, 0.8) + 0.44 * noise(t, 5))
        wave += 0.36 * envelope(t, 0.009, 0.082) * sweep(t, 368, 130, 0.059)
    elif index == 1:
        wave = attack * (1.05 * sweep(t, 134, 31, 0.07, 0.72) + 0.63 * noise(t, 18))
        wave += 0.37 * envelope(t, 0.033, 0.17) * sweep(t, 369, 121, 0.125, 1.3)
    elif index == 2:
        wave = attack * (0.68 * sweep(t, 215, 43, 0.036) + 0.85 * noise(t, 4))
        wave += 0.29 * envelope(t, 0.019, 0.14) * sweep(t, 1950, 310, 0.09, 1.6)
    elif index == 3:
        wave = attack * (0.98 * sweep(t, 2390, 320, 0.026, 0.54) + 0.15 * noise(t, 3))
        wave += 0.31 * envelope(t, 0.007, 0.054) * sweep(t, 705, 290, 0.083)
    else:
        wave = attack * (0.76 * sweep(t, 350, 64, 0.064, 1.2) + 0.85 * noise(t, 10))
        for arc in range(4):
            add_sound(wave, 0.027 + 0.024 * arc, click(0.075, 1530 - arc * 193, 182, 0.34))
    add_sound(wave, (0.095, 0.20, 0.153, 0.057, 0.114)[index],
              click(0.076, 1140 - index * 97, 204 - index * 12, 0.44))
    return wave


def reload_sound(index):
    duration = RELOAD_TIMES[index] / 20
    t = time(duration)
    pitch = (980, 420, 660, 1480, 860)[index]
    depth = (192, 94, 139, 255, 151)[index]
    wave = np.zeros(len(t))
    add_sound(wave, 0.04, click(0.135, pitch, depth))
    add_sound(wave, duration * 0.24, click(0.18, pitch * 0.7, depth * 0.66, 0.65))
    service = time(duration * 0.41)
    pitch_rise = sweep(service, pitch * 0.23, pitch * 0.94, duration * 0.17, 0.25)
    add_sound(wave, duration * 0.42, pitch_rise * envelope(service, 0.04, duration * 0.145) * 0.23)
    add_sound(wave, duration * 0.73, click(0.18, pitch * 0.85, depth * 0.81, 1.25))
    add_sound(wave, duration * 0.91, click(0.092, pitch * 1.55, depth, 0.48))
    return wave


def impact_sound(index):
    t = time((0.22, 0.80, 0.28, 0.15, 0.18)[index])
    if index == 1:
        return envelope(t, 0.004, 0.17) * (1.4 * sweep(t, 107, 30, 0.075) + 0.65 * noise(t, 16))
    return click(len(t) / RATE, (713, 200, 1385, 1971, 1340)[index], (156, 88, 302, 420, 206)[index])


def sound_file(name, samples, level):
    path = ASSETS / "sounds" / (name + ".ogg")
    path.parent.mkdir(parents=True, exist_ok=True)
    wave = np.tanh(samples)
    fade = min(len(wave) // 4, int(RATE * 0.022))
    wave[-fade:] *= np.linspace(1, 0, fade)
    wave *= level / max(0.001, np.max(np.abs(wave)))
    sf.write(path, wave, RATE, format="OGG", subtype="VORBIS")


def main():
    phrases = [
        "Automatic energy rifle with blue pulse bolts.",
        "Heavy plasma orbs with a 3.6-block blast radius.",
        "High-velocity slugs pierce up to three targets.",
        "Automatic carbine with an instant, precise laser beam.",
        "Seven electrical pellets with short-range damage falloff.",
    ]
    translations = {
        "itemGroup.valkron.equipment": "Valkron",
        "key.categories.valkron": "Valkron",
        "key.valkron.reload": "Reload weapon",
        "tooltip.valkron.magazine": "Magazine %s/%s · %s",
        "tooltip.valkron.controls": "Use to fire · Reload in Controls",
        "hud.valkron.reloading": "Reloading",
        "hud.valkron.count": "%s/%s  ·  %s reserve",
        "hud.valkron.unlimited": "Unlimited ammo",
        "hud.valkron.no_ammo": "No %s in inventory",
        "death.attack.valkron.energy": "%1$s was vaporized",
        "death.attack.valkron.energy.player": "%1$s was vaporized by %2$s",
        "death.attack.valkron.energy.item": "%1$s was vaporized by %2$s using %3$s",
        "subtitles.valkron.dry": "Weapon clicks empty",
    }
    sounds = {"dry": {"subtitle": "subtitles.valkron.dry", "sounds": ["valkron:dry"]}}
    sound_file("dry", click(0.15, 860, 176), 0.47)
    for index, (weapon, kit) in enumerate(KITS.items()):
        name, ammo_name, glow_hex, ceramic_hex, shape, ingredients, amount = kit
        ammo = AMMO[index]
        glow = rgb(glow_hex)
        # The Pulse Rifle's item model, legacy snapshots, and GeckoLib art are maintained separately.
        if weapon != "pulse_rifle":
            model(weapon, (pulse, plasma, rail, laser, ion)[index], index)
            save_image(ASSETS / "textures/item" / (weapon + ".png"), atlas(index, glow, rgb(ceramic_hex)))
        save_image(ASSETS / "textures/item" / (ammo + ".png"), ammo_icon(index, glow))
        json_file(ASSETS / "models/item" / (ammo + ".json"), {
            "parent": "minecraft:item/generated", "textures": {"layer0": "valkron:item/" + ammo},
        })
        recipes(weapon, ammo, shape, ingredients, amount)
        translations["item.valkron." + weapon] = name
        translations["item.valkron." + ammo] = ammo_name
        translations["tooltip.valkron." + weapon] = phrases[index]
        for action, wave, caption, volume in [
            ("fire", shot(index), name + " fires", 0.86),
            ("reload", reload_sound(index), name + " reloads", 0.60),
            ("impact", impact_sound(index), name + " shot impacts", 0.76),
        ]:
            event = weapon + "." + action
            sound_name = weapon + "/" + action
            subtitle = "subtitles.valkron." + event
            translations[subtitle] = caption
            sounds[event] = {"subtitle": subtitle, "sounds": ["valkron:" + sound_name]}
            sound_file(sound_name, wave, volume)
    json_file(ASSETS / "lang/en_us.json", translations)
    json_file(ASSETS / "sounds.json", sounds)
    save_image(ASSETS / "textures/entity/bolt.png", projectile_texture())


if __name__ == "__main__":
    main()
