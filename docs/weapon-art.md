# Weapon art and animation

All five guns use the same GeckoLib renderer. Each loads its own assets under `assets/valkron`.

- `geo/<weapon_id>.geo.json` defines the bone hierarchy, pivots, geometry, and UVs.
- `animations/<weapon_id>.animation.json` defines `animation.<weapon_id>.idle`, `.fire`, `.reload`, and `.inspect`.
- `textures/geo/<weapon_id>.png` is the UV atlas. The matching `<weapon_id>_glowmask.png` uses transparent pixels outside the emissive regions.
- `models/item/<weapon_id>.json` routes to the custom renderer and defines Minecraft's first-person, third-person, inventory, frame, and ground transforms. The `_legacy`, `_open`, and `_flash` JSONs and the original item atlas are preserved as reference and fallback art. The active model never selects those variants.

## Rig hierarchy and pivots

Keep the `root` and `body` pivots at the model origin. `body` is the parent of the main assemblies and `muzzle_anchor` sits at the barrel mouth. Pivots in the JSON are in model units, with 16 units per Minecraft block. The coordinates in the released geometry are the reference for retaining exact keyframe motion.

| Weapon | Moving hierarchy under `body` | Pivot landmarks |
| --- | --- | --- |
| Pulse Rifle | `magazine`, `receiver`, `energy_core` | Magazine feed center, upper receiver slide, forward core |
| Plasma Cannon | `chamber` → `chamber_cover`, `locking_mechanism`, `plasma_capsule` → `energy_core`. `muzzle_shroud` is a sibling of `chamber`. | Chamber cradle, cover's upper hinge, rear latch, capsule center |
| Railgun | `upper_rail`, `lower_rail`, `charging_bolt`, `slug_module`, `energy_core`, and `coil_left` / `coil_right` → each side's `_rear`, `_mid`, and `_front` segments | Rail guides, charging slide, underside module socket, luminous segment centers |
| Laser Carbine | `battery`, `battery_housing`, `side_panel`, `emitter`, `energy_indicator` → `indicator_rear`, `indicator_mid`, `indicator_front` | Battery center, latch rail, panel track, emitter axis, indicator window |
| Ion Blaster | `shell_chamber` → `loading_gate`, `shell`, `feeding_mechanism`, `energy_core`. `energy_coils` → `coil_rear`, `coil_center`, `coil_front`. | Gate hinge, shell seat, feeder axis, chamber core, individual coil centers |

## Replacing a rig from Blender

1. Model against the existing geometry scale, grip, and muzzle landmarks. Use Blender collections or named meshes to keep moving parts separate.
2. Rebuild or import the parts in a GeckoLib-compatible Blockbench project. Preserve animated bone names, parenting, neutral axes, and pivots. Verify the exported geometry format supports the asset representation, since a Blender rig or arbitrary mesh cannot be dropped directly into the current cuboid renderer.
3. Replace the geometry and matching atlas and glow mask together. Keep the animation file if the new rig retains the bone contract. If a pivot or axis changes, edit that bone's keys and preview all four clips before replacing the files.
4. Keep the reload clip at the server interval and the inspect clip at its client duration. A confirmed Ion shell cycle lasts 14 ticks. Its first and last visible poses match, and the shell is hidden at both ends, so consecutive cycles do not reset the camera.
5. Run the asset validator and Gradle build, then use the matrix below. Inspect finishes in the neutral pose, and a canceled clip must clear the moving parts on the next render.

Current parts and cosmetic keyframes are original prototype art. For a high-detail pass, refine panel clearance, replacement cartridge alignment, coil and indicator UVs, emissive transitions, and hand contact in both hands. The gun rig has no player-hand choreography. In-game projectile spawn and aiming remain on the gameplay path even if a new artist moves the visual muzzle.

## Runtime test matrix

| Scenario | Checks |
| --- | --- |
| Each of the five weapons in Survival | Fire with ammo, hold automatic fire where supported, reload, and verify magazine and reserve changes follow the server. Compare the body and mechanical bone movement in first and third person. |
| Local inspection | Open the mechanism with H, allow it to settle, then interrupt it by firing, reloading, swapping to another stack of the same weapon, or opening inventory. H during a reload leaves the reload active. |
| Ion Blaster on low reserve | Load several shells, watch one insertion per 14-tick cue, interrupt after the first shell with fire, and verify that the reserve stops decreasing. Cancel a new insertion by switching away. |
| Inventory and dropped weapon rendering | Watch inventory icons and a dropped gun while the same gun is active in hand. Their poses should stay neutral. |
| Dedicated connection and tracked players | Equip, fire, reload, and switch on the server. On a tracking client, compare an active player's gun with another player's idle gun of the same type. Only the inspecting user's client plays inspect. |
