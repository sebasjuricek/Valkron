# Valkron

Valkron adds five original sci-fi weapons and five ammunition types to Minecraft 1.21.1. The installed build targets NeoForge 21.1.267 and Java 21. It has no additional mod dependencies.

## Controls

- Hold a weapon in your main hand. Right-click to fire, or use your configured **Use Item/Place Block** input.
- Hold fire for the Pulse Rifle and Laser Carbine. The other three weapons fire once per press.
- Press **R** to reload. Change this binding under **Options → Controls → Key Binds → Valkron**.
- Pulling the trigger on an empty magazine starts a reload if you have matching ammo. Switching away cancels a reload. Loading draws only the rounds needed to fill the magazine and can use a partial stack.
- Crouch to tighten your spread. The bottom-right display shows magazine capacity, reserve ammunition, and reload progress.
- Creative Mode fires with unlimited ammunition. All ten items are in the **Valkron** creative tab.

## Weapons

Damage is in health points before armor and other modifiers. A heart is two points.

| Weapon | Magazine | Shot interval | Reload | Damage and effects |
| --- | ---: | ---: | ---: | --- |
| Pulse Rifle | 28 | 0.15 s | 1.45 s | 4.5 damage per blue energy bolt |
| Plasma Cannon | 4 | 1.20 s | 2.35 s | 17 on a direct hit, with splash damage within 3.6 blocks. The blast leaves terrain intact. |
| Railgun | 5 | 0.85 s | 1.95 s | 14 per target, with a high-speed amber slug that pierces up to three targets |
| Laser Carbine | 20 | 0.20 s | 1.15 s | 5.5 per hit with an instant red beam |
| Ion Blaster | 6 | 0.65 s | 0.70 s per shell | Seven violet pellets at up to 2.5 each, with damage falling off over an 18-block range |

The Ion Blaster loads shells one at a time. Fire as soon as one shell is loaded to interrupt the reload.

## Crafting

Use a vanilla crafting table. Each grid lists the top, middle, and bottom rows from left to right.

`I` = iron ingot · `C` = copper ingot · `R` = redstone dust · `S` = stick · `G` = glass · `.` = empty

| Gun | Top | Middle | Bottom |
| --- | --- | --- | --- |
| Pulse Rifle | `ICR` | `ISI` | `.S.` |
| Plasma Cannon | `GCG` | `IRI` | `.S.` |
| Railgun | `III` | `CRC` | `..S` |
| Laser Carbine | `.G.` | `CRC` | `.IS` |
| Ion Blaster | `CIC` | `.RS` | `.I.` |

Ammo recipes are shapeless. Place one of each listed ingredient anywhere in the grid.

| Ammunition | Ingredients | Output |
| --- | --- | ---: |
| Pulse Cell | Copper ingot + redstone dust | 48 |
| Plasma Capsule | Glass + charcoal + redstone dust | 32 |
| Rail Slug | Iron nugget + copper ingot | 48 |
| Laser Cell | Glass + redstone dust | 64 |
| Ion Shell | Copper ingot + charcoal | 40 |

## Multiplayer and building

Install the same Valkron JAR in the server's and every joining client's `mods` folders. The server validates firing, reloads, ammunition, and damage. Valkron respects the server's PvP setting and team friendly-fire settings.

With a Java 21 JDK selected in `JAVA_HOME`, run `.\gradlew.bat build` from this folder. The compiled mod is written to `build/libs/valkron-1.0.0.jar`. The 3D models, textures, and audio ship inside the JAR. To regenerate those resources, run `python tools/generate_assets.py` with Pillow, NumPy, and soundfile installed.
