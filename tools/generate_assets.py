"""Build recipes, ammo icons and synthesized audio. Weapon art is curated separately.

Run with Python 3 and Pillow, NumPy and soundfile installed. The generated
resources are checked in, so Gradle can build the mod without Python.
"""

from pathlib import Path
import json
import math

import numpy as np
from PIL import Image, ImageDraw
import soundfile as sf


ROOT = Path(__file__).resolve().parents[1] / "src/main/resources"
ASSETS = ROOT / "assets/valkron"
DATA = ROOT / "data/valkron"

# name, ammo, glow, recipe pattern, ammo ingredients, ammo yield
KITS = {
    "pulse_rifle": ("Pulse Rifle", "Pulse Cell", "#39bcff",
                    ["ICR", "ISI", " S "], ["copper_ingot", "redstone"], 48),
    "plasma_cannon": ("Plasma Cannon", "Plasma Capsule", "#a6f348",
                      ["GCG", "IRI", " S "], ["glass", "charcoal", "redstone"], 32),
    "railgun": ("Railgun", "Rail Slug", "#ffb34d",
                ["III", "CRC", "  S"], ["iron_nugget", "copper_ingot"], 48),
    "laser_carbine": ("Laser Carbine", "Laser Cell", "#ff526d",
                      [" G ", "CRC", " IS"], ["glass", "redstone"], 64),
    "ion_blaster": ("Ion Blaster", "Ion Shell", "#b594ff",
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
        "key.valkron.inspect": "Inspect weapon",
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
        name, ammo_name, glow_hex, shape, ingredients, amount = kit
        ammo = AMMO[index]
        glow = rgb(glow_hex)
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
