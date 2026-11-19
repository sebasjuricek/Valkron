"""Check Valkron's GeckoLib resource and timeline contracts without loading Minecraft."""

import json
from pathlib import Path
import re
import struct


PROJECT = Path(__file__).resolve().parents[1]
ASSETS = PROJECT / "src/main/resources/assets/valkron"
JAVA = PROJECT / "src/main/java/dev/valkron"
PHASES = ("idle", "fire", "reload", "inspect")
REQUIRED_BONES = {
    "pulse_rifle": {"magazine", "receiver", "energy_core"},
    "plasma_cannon": {"chamber", "chamber_cover", "plasma_capsule", "locking_mechanism", "energy_core"},
    "railgun": {"upper_rail", "lower_rail", "charging_bolt", "slug_module", "coil_left", "coil_right", "energy_core"},
    "laser_carbine": {"battery", "battery_housing", "side_panel", "energy_indicator", "emitter"},
    "ion_blaster": {"shell_chamber", "loading_gate", "shell", "feeding_mechanism", "energy_coils", "energy_core"},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    return json.loads((ASSETS / path).read_text(encoding="utf-8"))


def size_png(relative_path):
    with (ASSETS / relative_path).open("rb") as image:
        header = image.read(24)
    require(header[:16] == b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" and len(header) == 24, f"{relative_path} is not a PNG")
    return struct.unpack(">II", header[16:24])


def validate_weapon(weapon, cooldown, reload_ticks, inspect_ticks):
    model = read(f"models/item/{weapon}.json")
    require(model["parent"] == "builtin/entity" and "overrides" not in model, f"{weapon} renderer routing")
    require("elements" in read(f"models/item/{weapon}_legacy.json"), f"{weapon} legacy geometry")

    geometry, = read(f"geo/{weapon}.geo.json")["minecraft:geometry"]
    description = geometry["description"]
    require(description["identifier"] == f"geometry.valkron.{weapon}", f"{weapon} geometry identifier")
    atlas_size = (description["texture_width"], description["texture_height"])
    require(size_png(f"textures/geo/{weapon}.png") == atlas_size, f"{weapon} atlas dimensions")
    require(size_png(f"textures/geo/{weapon}_glowmask.png") == atlas_size, f"{weapon} glowmask dimensions")

    bones = {}
    for bone in geometry["bones"]:
        name = bone["name"]
        parent = bone.get("parent")
        require(name not in bones and len(bone["pivot"]) == 3, f"{weapon} invalid bone {name}")
        require((parent in bones) if name != "root" else parent is None, f"{weapon} invalid parent for {name}")
        bones[name] = bone
    require(REQUIRED_BONES[weapon] | {"root", "body", "muzzle_anchor"} <= bones.keys(), f"{weapon} missing rig bones")
    require(any(bone.get("cubes") for bone in bones.values()), f"{weapon} missing geometry")

    animations = read(f"animations/{weapon}.animation.json")["animations"]
    clips = {phase: animations[f"animation.{weapon}.{phase}"] for phase in PHASES}
    for phase, ticks in (("fire", min(cooldown, 8)), ("reload", reload_ticks), ("inspect", inspect_ticks)):
        require(clips[phase]["animation_length"] == ticks / 20, f"{weapon} {phase} timing")
    for phase, clip in clips.items():
        require(clip["bones"].keys() <= bones.keys(), f"{weapon} {phase} references unknown bones")
        for name, channels in clip["bones"].items():
            for values in channels.values():
                if isinstance(values, dict):
                    require(all(0 <= float(key) <= clip["animation_length"] for key in values),
                            f"{weapon} {phase} keyframe outside clip on {name}")
        if phase in {"reload", "inspect"}:
            moving = [name for name, channels in clip["bones"].items() if any(isinstance(v, dict) for v in channels.values())]
            require(len(moving) >= 3, f"{weapon} {phase} lacks independent motion")
    return f"{weapon}: {len(bones)} bones, four clips, reload {reload_ticks} ticks, inspect {inspect_ticks} ticks"


def main():
    weapon_source = (JAVA / "Weapon.java").read_text(encoding="utf-8")
    state_source = (JAVA / "client/GunAnimationState.java").read_text(encoding="utf-8")
    timings = {
        name.lower(): (int(cooldown), int(reload))
        for name, cooldown, reload in re.findall(r'\b([A-Z_]+)\("[a-z_]+",\s*\d+,\s*(\d+),\s*(\d+),', weapon_source)
    }
    inspect = {name.lower(): int(ticks) for name, ticks in re.findall(r"case ([A-Z_]+) -> (\d+);", state_source)}
    require(timings.keys() == REQUIRED_BONES.keys() == inspect.keys(), "Weapon and asset sets differ")
    for weapon, (cooldown, reload) in timings.items():
        print(validate_weapon(weapon, cooldown, reload, inspect[weapon]))


if __name__ == "__main__":
    main()
