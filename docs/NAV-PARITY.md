# NAV-PARITY: what the ray-traced levels must carry over from Navidson (card NAV-PARITY, 2026-11-25)

Owner 2026-11-25 (DECISIONS "Rendering quality modes: five levels"): Low / Medium / High "still have to look like and support
everything that Navidson does in terms of mod support and features". Owner final word (via orchestrator): ONE context-sensitive button under Rendering quality. Off / GLSL levels show "Shader Settings"
(Iris-style pack list, add own packs, per-pack options from `shaders.properties`); Low / Medium / High show "Advanced Options" (the RT
tweak screen). Section 3 maps Navidson's options to the fixed RT defaults, section 3b is the proposed Advanced Options list, section 4
lists what the Iris-style Shader Settings menu must support for Navidson's `shaders.properties`.

Sources (all read-only): `~/Projects/navidson` (`pack/shaders`, `pack/Navidson.md`, `v2/`, `tools/`; the working tree has
uncommitted edits: deleted EP credit files, modified `pack/Navidson.md`), the unmodified base
`ComplementaryUnbound_r5.9.zip` (diffed for what is Navidson's), RT-PLAN (int-4c `bbb83337`) and its amendments, knowledge
`navidson.md`, `iris-loader.md`, `rt-iroh.md`, `owner-mods.md`, `history/navidson-inventory-p0.md`. V = read in a file named here,
U = unverified.

Legend. RT status: COVERED = an RT-PLAN card delivers the effect (look parity still needs the constants of section 3);
PARTIAL = a card touches it but misses part; GAP = no card; REUSE = raster/post effect that works on the RT output
unchanged in algorithm (Iroh implements it in its own post pass, see licence note); N/A = not needed under Iroh.
Licence rule (navidson.md section 1): do NOT copy base GLSL (tonemapper, saturation, bloom tiles, floodfill, material/light-colour
tables). The owner's own files (`lib/navidson/*`, `lib/shaderSettings/navidson.glsl`, `lib/lighting/lodShadows.glsl`, `lib/vista/*`,
`program/{deferred,vista_terrain}.glsl`, per-dimension wrappers, `world2/`) may be re-used once the owner relicenses them (open
question). Everything marked "re-derive" is written fresh from published methods plus the owner's multipliers.

## 1. Feature inventory

Where = file:line in `~/Projects/navidson/pack/shaders` (V). "Owner" = file written by the owner, "base" = Complementary r5.9 code
(Navidson edits marked N).

### 1a. Lighting and shadows
| # | feature | where | depends on | RT provision | RT card | status |
|---|---|---|---|---|---|---|
| F1 | Sun and moon shadows (shadow map, SHADOW_QUALITY, distance 80-96, `shadow.culling reversed`) | `program/shadow.glsl`, `lib/lighting/shadowSampling.glsl:1`, `lib/lighting/mainLighting.glsl:38` | sun angle, moon phase (`MOON_PHASE_INF_LIGHT`), cloud shadow | RT visibility ray to sun/moon, denoised | RT-2 (Low), RT-8 / FFX shadow denoiser | COVERED |
| F2 | Distant-terrain (LOD) shadows, half res (N, owner) | `program/deferred.glsl:58`, `lib/lighting/lodShadows.glsl:1`, `program/deferred1.glsl:113,313,327` | DH/Voxy/Vista depth | RT shadows reach all resident geometry; past residency use the far-field blend | RT-3b far-field blend, RT-14 | PARTIAL (Vista past residency has no shadow ray; owner wants LOD shading to match) |
| F3 | Coloured block light: floodfill ACT into `floodfill_img` rgba16f 3D (128^3 default), voxel_img r16ui | `program/shadowcomp.glsl:37`, `lib/voxelization/lightVoxelization.glsl:5`, `lib/colors/blocklightColors.glsl:1`, `shaders.properties:206-221` | `COLORED_LIGHTING 128`, light colour table (base) | emissive rectangle lights with per-sprite colour, ReSTIR DI, light grid | RT-3a, RT-3b | PARTIAL (colour source: Navidson uses base per-block colour tables, which may not be copied; RT-3a derives colour from the atlas, so torch/soul/redstone/lantern hues must be checked per block against Navidson) |
| F4 | Block-light colour options (saturation 120, fog 0.85, curve 1.5, XLIGHT_I 1.5 in owner file) and coloured light fog | `program/composite1.glsl`, `composite2.glsl`, options `COLORED_LIGHT_*`, `XLIGHT_*` | composite1 half-res | RT light contribution scale + a coloured-light medium term | RT-3b (scale only) | PARTIAL (fog term GAP, see F15) |
| F5 | Handheld light (own and other players), `oldHandLight=false` | `lib/lighting/heldLighting.glsl:2`, `lib/lighting/mainLighting.glsl:444`, `program/gbuffers_hand.glsl` | `heldItemId`, `heldBlockLightValue` | a dynamic point light at the player's hand fed into the RT light list every frame | none (RT-3a is chunk lights only) | GAP |
| F6 | Ambient, night, minimum light (owner multipliers `NV_NIGHT_LIGHT/AMBIENT/SKY/CLOUD/SKY_TINT`, `CAVE_LIGHTING`, `AMBIENT_MULT`) | `lib/colors/lightAndAmbientColors.glsl:21,95-97`, `lib/lighting/minimumLighting.glsl:1`, `lib/colors/skyColors.glsl:65-66` | `isEyeInCave`, rain/thunder factors | sky/ambient radiance level and floor in `RtLookModel`; "never brighten a night" rule exists in rt-iroh.md | RT-10, RT-3b GI | PARTIAL (multipliers not wired; cave floor 10 in the owner's file is very dark) |
| F7 | SSAO and vanilla AO (`SSAO_I 140`, `VANILLAAO_I 140`, pixelated AO true) | `program/deferred1.glsl` (SSAO), mesher baked AO | depth, normals | RT ambient occlusion (High); vanilla face shade stays in the mesh colour | none by name (GI covers indirect only) | GAP (AO is named in the High level, no card) |
| F8 | Light shafts / volumetric rays (half-res composite1, up-sampled in composite2) | `lib/atmospherics/volumetricLight/volumetricLight.glsl`, `program/composite1.glsl`, `composite2.glsl` | sun visibility, `LIGHTSHAFT_*` | RT-visibility-sampled volumetric scattering (shadow rays along the view ray, half res) | none | GAP |
| F9 | Cloud shadows on the ground | `lib/lighting/cloudShadows.glsl:23` (Tempest branch) | Tempest maps, `NV_TEMPEST_CLOUDS` | sun visibility multiplied by the cloud transmittance | RT-10 "IrohSky hook for Tempest cloud shadows" | PARTIAL (hook only; owner's Navidson owner file `CLOUD_SHADOWS=false` in the tuned config, so low priority) |

### 1b. Sky, clouds, fog, weather
| # | feature | where | depends on | RT provision | RT card | status |
|---|---|---|---|---|---|---|
| F10 | Fantasy sky (violet-blue zenith, mint horizon, magenta/gold dusk) | `lib/colors/skyColors.glsl:44-66`, `program/deferred1.glsl:357-433`, options `NV_FANTASY_SKY .70`, `NV_FANTASY_SUNSET .80` (tuned) | time of day, rain/gloom | displayed sky pass + miss shader radiance with the same palette | RT-10 (LUTs for lighting; "displayed sky stays" own) | GAP (palette not carried; rt_look.glsl has a neutral gradient + sunset band) |
| F11 | Starfield (celestial sphere turning with the moon) and galaxy band | `lib/navidson/starfield.glsl:16,29,71,104` (owner), `program/deferred1.glsl:368`, `lib/materials/materialMethods/reflectionBackground.glsl:24` | moon rotation, `NV_STARFIELD`, `NV_GALAXY*` | owner file re-used in the sky pass and the reflection miss | none (rt_look.glsl has hashed stars only) | GAP |
| F12 | Night nebulae, aurora, rainbow, ender stars | `lib/atmospherics/nightNebula.glsl:67`, `auroraBorealis.glsl:1`, `rainbow.glsl` (composite2:146), `enderStars.glsl:1` | `NIGHT_NEBULAE`, `AURORA_STYLE`, `RAINBOWS` | re-derive in the sky pass | none | GAP |
| F13 | Sun/moon style, horizon, lens flare | options `SUN_MOON_STYLE_DEFINE 1`, `LENSFLARE_MODE 0` (off) | | sky pass; flare off by default | none | GAP (sun/moon disc style only; flare N/A while off) |
| F14 | Unbound clouds (style 3 in the tuned config), silver lining, depth shading, storm thickening | `lib/atmospherics/clouds/unboundClouds.glsl:71,241`, `mainClouds.glsl:103`, `NV_CLOUD_*`, `NV_STORM_*` | `CLOUD_ALT1`, wind, rain | cloud ray-march pass between sky and geometry (screen pass using depth + view ray, algorithm unchanged) | none | GAP (REUSE the algorithm in an own pass) |
| F15 | Fog family: atmospheric fog (`ATM_FOG_*`), border fog (off in tuned), cave fog, nether view limit, fog wall, distance haze | `lib/atmospherics/fog/mainFog.glsl:11-24,92,175,192`, `lib/navidson/fogWall.glsl:14` (owner) | distance, height, biome | analytic fog in the post/compose pass | rt_look.glsl has an exponential fog; RT-3b far-field | PARTIAL |
| F16 | Mountain fog `NvMountainFog` (height-integrated valley mist, 50% cap, drifting banks) | `lib/atmospherics/fog/mainFog.glsl:127-165` (gate line 127: `(DISTANT_HORIZONS || VOXY) && OVERWORLD && NV_MOUNTAIN_FOG>0 && !OUTSIDE_OF_TIME`) | far terrain exists; VISTA defines DISTANT_HORIZONS (`lib/common.glsl:15`) so the Vista gate edit is moot in the pack (U whether Iroh defines VISTA) | owner function already in `rt_look.glsl` (rt-iroh.md P5-1) | exists; RT-3b far-field | COVERED (gate: "far terrain exists") |
| F17 | Storm fog, blizzard fog, storm gloom (light and sky go grey under storm cloud before rain) | `lib/navidson/weather.glsl:24-66`, `lib/navidson/blizzard.glsl:99` (world-anchored flake grid, never screen-space), `mainFog.glsl:329`, `lib/colors/lightAndAmbientColors.glsl:95`, `skyColors.glsl:56`, `lib/navidson/tempestData.glsl:20` | Tempest data texel 22, `thunderFactor`, `rainFactor`, `inSnowy` | storm term in the sun/sky radiance + fog; blizzard grid in a compose/volume pass | none | GAP |
| F18 | Weather precipitation (rain streaks, snow, `IMPROVED_RAIN`, `NV_STORM_PRECIPITATION`, `NV_SNOW_VISIBILITY`) | `program/gbuffers_weather.glsl:52`, `lib/navidson/weather.glsl` | `thunderFactor` | vanilla particle/weather raster composited after compose | RT-6 | COVERED (raster; scale by storm) |
| F19 | Lightning bolt colour/brightness and the flash light | `lib/materials/specificMaterials/others/lightningBolt.glsl:5` (`NV_LIGHTNING_BRIGHTNESS 8`), `lib/navidson/tempest.glsl:116-144` (flash scan), `lightningBoltPosition` | Fractal Lightning mod via Iris lightning layer | bolt raster + a point-light flash for sky/ground | RT-6 (partial) | PARTIAL |
| F20 | Rain puddles, snowy world, wetness (`RAIN_PUDDLES 1`, `puddle_img` r8ui 128x128) | `lib/materials/materialMethods/snowyWorld.glsl:4`, `shaders.properties:223`, rt_look.glsl wetness stub | biome precip, rain factor | material roughness/albedo modulation in the G-buffer resolve | none | GAP |
| F21 | Water look: style 2, colour mode 2 (`WATERCOLOR_B 120`), alpha 170, fog 130, caustics style 1, waves, foam, refraction | `lib/materials/specificMaterials/translucents/water.glsl:21`, `lib/materials/materialMethods/refraction.glsl:1`, `program/gbuffers_water.glsl`, `program/composite2.glsl:336` | biome water colour | dielectric water, Beer-Lambert medium, wave normals, refraction | RT-6, RT-7 (`water.glsl`/`medium.glsl`) | PARTIAL (physics yes; Navidson's colours/alpha/fog constants and underwater tint `(0.80,0.87,0.97)` not carried) |
| F22 | Water caustics (light pattern on the floor, `WATER_CAUSTIC_STYLE 1`) | `water.glsl` caustic block, composite pass | sun, water surface | RT caustics (High) | RT-7 "caustic term" in Caustica water.slang is named in the reuse table only | GAP (High level names caustics, no card delivers a caustic pass) |
| F23 | Underwater distortion, vignette, chroma aberration, image sharpening | `program/final.glsl:55`, options `UNDERWATER_DISTORTION`, `IMAGE_SHARPENING 5`, `VIGNETTE_R false`, `CHROMA_ABERRATION 0` | | post pass after FSR | none | REUSE (post; GAP only for the underwater distortion) |

### 1c. Materials, geometry, entities
| # | feature | where | depends on | RT provision | RT card | status |
|---|---|---|---|---|---|---|
| F24 | LabPBR 1.3 from resource packs (`RP_MODE 3` in tuned config), emission, porosity/SSS | `lib/materials/materialHandling/customMaterials.glsl`, `lib/materials/materialMethods/customEmission.glsl:4` | resource pack | decode LabPBR into G-buffer B | RT-4a, RT-4b | COVERED |
| F25 | Integrated PBR per block (no resource pack): tables keyed by `block.properties` ids (5xxx block entities, 1xxxx terrain, 3xxxx translucent), glowing ores, emissive redstone/lapis/amethyst, coated textures, generated normals (`GENERATED_NORMAL_MULT 120`) | `lib/materials/materialHandling/terrainIPBR.glsl` (2775 lines, base), `irisIPBR.glsl:1`, `block.properties` (base + 7 RU vine ids in `block.11009`), `entity.properties`/`item.properties` (base, unchanged) | block ids | own per-block material rules (tags, name rules) in `materials.json`; auto-PBR for generated normals | RT-4a (tag heuristics), RT-4c (auto normals; Q2 proposes OFF by default) | PARTIAL (table is base: cannot be copied; parity needs the owner's look of glowing ores/emissives re-authored; Navidson default has generated normals ON) |
| F26 | Parallax occlusion mapping (`POM_QUALITY 64`, distance 24) | `lib/materials/materialMethods/pomEffects.glsl:3,17` | height maps | ray-march heights in the G-buffer pass (primary only) | none | GAP (primary-surface raster feature; owner may drop it) |
| F27 | Waving foliage / leaves / water / lava / lily pad / rain waving | `lib/materials/materialMethods/wavingBlocks.glsl`, options `WAVING_*` | vertex shader, time | gbuffer.vert applies the same waves (primary); RT BLAS stays static, so shadows/GI/reflections of leaves lag the raster pose | none | GAP (decide: accept the static-BLAS mismatch) |
| F28 | Entity materials, glowing entities, armor glint, spider eyes, player ray tracer, entity shadows | `lib/materials/materialHandling/entityIPBR.glsl:1` (ids 50000-50128), `gbuffers_entities.glsl`, `lib/materials/materialMethods/playerRayTracer.glsl` | entity ids | entity part BLAS + G-buffer B | RT-5, RT-4a | COVERED (entity shadow option `ENTITY_SHADOW -1` = off in tuned config) |
| F29 | Connected/fancy glass, tinted glass mirror, translucent colour tint (31xxx ids) | `lib/materials/materialMethods/connectedGlass.glsl`, `translucentTweaks.glsl`, `translucentIPBR.glsl` | block ids | stained-glass transmittance for shadow rays, dielectric glass | RT-6 | COVERED |
| F30 | Reflections: SSR + world-space reflections (`WATER_REFLECT_QUALITY 1`, `BLOCK_REFLECT_QUALITY 1`, `WSR` off by default), sky reflection incl. stars | `lib/materials/materialMethods/reflections.glsl:34`, `worldSpaceRef.glsl:61`, `program/composite.glsl:71`, `reflectionBackground.glsl:24` | roughness | RT reflections with sky miss shader (needs F10-F14 in the miss) | RT-3b refl.comp, RT-4c GGX | COVERED for geometry; sky content depends on F10-F14 |

### 1d. Post FX and colour
| # | feature | where | depends on | RT provision | RT card | status |
|---|---|---|---|---|---|---|
| F31 | Tone map, exposure (tuned 0.95), contrast (1.20), saturation (0.70), vibrance, dark desaturation, white path | `program/composite5.glsl:36 DoCompTonemap, :91 DoBSLColorSaturation` (base), `TM_*`, `T_*` | HDR colour | own tone map in `post.comp`, calibrated to Navidson's output | RT-PLAN 2.1 names "tone map, Navidson grade, bloom (post.comp)", no card owns the Navidson curve | GAP |
| F32 | Colour grading matrix (`COLORGRADING`, `GR_*`) | `program/composite5.glsl:233` | | 3x3 + offset in post | none | GAP (small) |
| F33 | Elemental regions grade `NvElementGrade`: four 90-degree wedges (Air N, Water E, Fire S, Earth W, centre neutral), per-pixel by world position, tints the picture, light and sky | `lib/navidson/elemental.glsl:12,34,47,56,82` (owner), `program/composite5.glsl:293-298`, light/sky tint via `NV_ELEMENT_LIGHT .6`, `NV_ELEMENT_SKY .7` | world XZ of each pixel (reconstruct from depth), Citadels layout | apply `NvElementGrade` after the RT tone map; tint sun/sky radiance by camera weights | none | GAP (owner MUST #4) |
| F34 | Bloom (`BLOOM_STRENGTH .19`) + light-bloom halo on far-brighter-than-surroundings light (`NV_LIGHT_BLOOM 1.2`, night 1.2, threshold .6) | `program/composite5.glsl:149-175` (halo), bloom tiles base | HDR colour | own bloom + halo in post; the R-NV-BLOOM attempt was reverted (tap spacing) | RT-PLAN 2.1 names bloom only | GAP |
| F35 | Motion blur (ON in tuned config, strength .55), world blur / DoF (off), TAA (-1, off) , FXAA (1) | `program/composite3.glsl:11`, `composite4.glsl:13`, `composite6.glsl:27`, `composite7` | motion vectors | motion blur on RT output reusing Iroh motion vectors; TAA replaced by FSR; FXAA N/A | none (FSR-Q for TAA) | REUSE (motion blur GAP in cost: 2/3 of composite4); DoF N/A (off) |
| F36 | Nether look (nether storm, view limit, atmosphere colours), End look (beam, stars, flashes `endFlashIntensityM`), cave darkness | `lib/atmospherics/netherStorm.glsl:1`, `enderStars.glsl`, `END_*`, `NETHER_*`, `world-1/`, `world1/`, `program.world-1/composite1.enabled=false` | dimension, biome fades `inCrimsonForest` etc. | per-dimension `RtLookModel` palettes (sky/fog/ambient), nether storm and End pieces in sky/fog | rt_look.glsl "three dimensions" scene (RT-13) | PARTIAL |
| F37 | Outside of Time (Citadels dimension `citadels:outside_of_time`): timeline sky (void, branches, nebulae, galaxies), cataclysm worlds colliding and fracture, `world2/` | `lib/navidson/timeline.glsl:258-302`, `cataclysm.glsl:238-438` (owner), `lib/atmospherics/sky.glsl:14`, `dimension.properties` | dimension id | dedicated sky pass for that dimension, owner files re-used | none | GAP (owner MUST for that dimension) |

## 2. Mod integrations and pack hooks

| # | hook | where | contract | RT provision | RT card | status |
|---|---|---|---|---|---|---|
| M1 | Tempest five maps (all 256x256, 32 blocks/texel, repeat 8192, mod `(camera mod 8192 + offset - mapShift)/8192`): `nvTempestClouds` (R cover .5 = clear, G storm, B ember, A dust haze), `nvTempestGround` (R dust, G snow, B rain, A ash), `nvTempestTint` (R frost, G supercell, B anvil, A wall cloud), `nvTempestShape` (R cumulus, G deck, B tower, A heap), `nvTempestData` (32x1; texel 15 = `TEMP`) | `shaders.properties:276-282`, `lib/navidson/tempestData.glsl:11-20`, `tempest.glsl:73-96` | `tempest:dynamic/*` via `IrohTextures.createDynamic`; `hasConsumers()` must be true or Tempest does not rasterise (owner-mods.md T6, vista.md) | RT sky/cloud/volume passes sample the same five textures; Iroh must register as a consumer when an RT level is active | RT-14 (Tempest row) lists only precipitation + cloud shadows | PARTIAL (consumer registration for RT: GAP) |
| M2 | Tempest clouds with funnel in the march: layers around `CLOUD_ALT1` (heaps, storm deck, tower to 150+300*tower, anvil 380-458, wall cloud -140..-20), two noise octaves, drift with wind; funnel grows from the wall cloud, placed where Tempest's `Funnel` lifts players | `lib/navidson/tempestClouds.glsl:45-226` (owner, 494 lines), funnel `:96-218`, `mainClouds.glsl:103` | data texels 0, 13, 16-19 (wind, funnel count, base/top), server clock texel 23 | cloud march as a sky/compose pass, funnel kept | none | GAP |
| M3 | Tempest volumes: sand wall, rain curtains, snow white-wall, ash, ember glow, lightning flash; `NvTempestFunnel` when Tempest clouds are off | `lib/navidson/tempest.glsl:139-345`, `program/composite2.glsl:398-403`, vista copy `lib/vista/tempest.glsl:6-22` | ground/cloud maps, flashes | world-anchored volume pass in compose (never screen-space: owner rejected it 2026-10-09) | none | GAP |
| M4 | Tempest weather clock: all animation uses the server game clock (`nvWeatherClock`), maps shift (`mapShift`) | `tempest.glsl:40-100` | data texels 18, 23 | RtFrameData carries the same clock | none | GAP (small, part of M2/M3 card) |
| M5 | `elemore_core:dynamic/lights` (16x2 texels, `DYNL` signature at row 1 texel 15): other players' held light; falls like the player's own held light | `lib/navidson/dynlights.glsl:15-43`, `lib/lighting/mainLighting.glsl:444`, `shaders.properties:272-273` | colour 0 torch / 1 soul / 2 redstone / 3 cool white; light level x17 | feed the 16 lights into the RT light list each frame | none | GAP. Verified 2026-11-25: the pack's dynlights are STILL dead: `NV_DYNLIGHTS` (navidson.glsl:115) has no bare `#ifdef` (shaders.properties:272 tests it, but Iris only registers an option that has a bare `#ifdef`; the pack itself tests only `#if defined NV_DYNLIGHTS && ...` at dynlights.glsl:11). RT must not inherit this bug: it is a feature the owner wants working. |
| M6 | Vista far terrain: programs `vista_terrain`, `vista_terrain_early`, `vista_water`, SSBO bindings 21-26, 19-name `vista*` uniform contract, `VISTA` macro (aliases `dh*` names, `lib/common.glsl:15`), coverage/fade rules `lib/vista/fragment.glsl:34-53`, Tempest on LODs `dh_terrain.glsl:149,163`, planned `vista_shadow/weather/figure` | `program/vista_terrain.glsl`, `world{0,-1,1}/vista_*`, `lib/vista/*` | Vista draws LOD with its own G-buffer-like output | under RT: Vista LOD stays raster past BLAS residency and composites with the far-field blend; LOD water and fog must match RT water/fog; mountain fog applies | RT-3b far-field blend, RT-M, RT-14 (Vista row) | PARTIAL |
| M7 | The two approved Navidson edits (DECISIONS Q5): (1) `program/dh_water.glsl:109` reads `depthtex0` while it is the attachment (switch to `depthtex1`); (2) mountain fog gate `|| defined VISTA` | `dh_water.glsl:109`, `mainFog.glsl:127` | | Verified: (1) NOT yet applied in `~/Projects/navidson`; (2) not applied but moot while `VISTA` defines `DISTANT_HORIZONS` (common.glsl:15) | n/a to RT | OPEN (GLSL path only) |
| M8 | elemore / citadel / bending visuals: no block/entity/item ids for pack mods exist in Navidson (V: namespaces in `block.properties` are only vanilla + 7 `regions_unexplored` vine/moss ids in `block.11009`; `entity.properties`, `item.properties` equal the base). Pack mods therefore get generic iPBR/emission, or `block.10028` modded-emissive handling is NOT used. Elemental regions follow the Citadels wedge layout by world XZ; Citadels is the only dimension-specific mod hook (`world2`) | `block.properties`, `dimension.properties` | | modded blocks: default material + the emission heuristic; bending particles/spell visuals are raster particles composited after RT | RT-3a ("modded emitters get default colour"), RT-4a, RT-6 | COVERED for what exists; RT-14 sweep to confirm alexscaves/polaris rows |
| M9 | Iris-provided uniforms and defines the pack reads: `cameraPositionInt/Fract`, `eyePosition`, `lightningBoltPosition`, `thunderStrength`, `biome`, `biome_category`, `biome_precipitation`, `currentRenderedItemId`, `endFlashIntensity`, `cloudHeight`; custom uniforms: `framemod2/4/8/600`, `isEyeInCave`, `inDry/inRainy/inSnowy` (smooth 20/10), `rainFactor`, `thunderFactor` (smooth 3/3), 7 nether-biome fades (smooth 15/15), `maxBlindnessDarkness`, `endFlashIntensityM`, `starter`, `frameTimeSmooth`, `eyeBrightnessM(2)` | `shaders.properties:285-325` | `CustomEval` | `RtFrameData`/`RtLookModel` must carry the same smoothed scalars (rain, thunder, in-cave, precip biome, nether biome fades, end flash, blindness/darkness) | RT-10/RT-11 mention a look model but not this list | GAP |
| M10 | Defines: `IRIS_FEATURE_FADE_VARIABLE` (Iroh does not serve it; Navidson has a fallback), `IS_IRIS`, `MC_ANISOTROPIC_FILTERING` must stay undefined, `shadow.culling reversed` | `shaders.properties:99-100` | | GLSL path only | iris-loader | N/A for RT |
| M11 | Fractal Lightning mod draws bolts through Iris's lightning layer | `gbuffers_lightning.glsl`, `lightningBolt.glsl` | | RT-6 raster composite must keep a bolt layer | RT-6 | PARTIAL |
| M12 | Distant Horizons / Voxy branches (61 + others) | many | | not needed (Vista is the only LOD) | - | N/A |
| M13 | `v2/` (owner from-scratch rewrite scaffold, untracked, no programs: `lib/navidson/{timeline,cataclysm}.glsl` copies, `lib/sky/atmosphere.glsl`, `clouds.glsl`, `frame.glsl`, 3132 lines) | `~/Projects/navidson/v2` | | candidate source of owner-only formulas for the sky/cloud passes | owner question | OPEN |

## 3. Navidson options the RT levels' fixed defaults must honour

Low/Medium/High default to constants (editable only through Advanced Options, section 3b). To match "RT High looks like Navidson", the fixed look values come from
the OWNER-TUNED config (`Navidson.txt`; the copy found is the Proctor clone's, saved 2026-11-14,
`~/.cache/proctor/centre/instance/shaderpacks/Navidson.txt`; U whether the owner's live file equals it), falling back to the pack
default where the tuned file is silent. Table = value the RT defaults take (tuned / pack default) and which RT component reads it.
All of these live in `RtLookModel` constants; section 3b exposes a subset.

| group | options (tuned value / pack default) | RT component | note |
|---|---|---|---|
| Elemental grade | `NV_ELEMENT_STRENGTH` 1, `_MODE` 1, `_GRADE` 1, `_LIGHT` .6, `_SKY` .7 (tuned) / .5, `_BLEND` 600, `_CENTRE` 1200, `_CENTRE_FADE` 1500, `_REACH` 768, `_ROTATION` 0; per element `I/R/G/B/SATURATION/CONTRAST`: Air 1/1.08/.98/1.10/.92/.95, Water 1/.86/1/1.14/1.04/1.05, Fire 1/1.18/.96/.80/1.08/1.10, Earth I .70 (tuned) /.98/1.06/.84/1.08/1.05 | F33 | `navidson.glsl:7-50` |
| Night | `NV_NIGHT_LIGHT` 1.00 (tuned) / .25, `_AMBIENT` .40 / .20, `_SKY` .30 / .35, `_CLOUD` .35 / .45, `_SKY_TINT` .30 / .50, `CAVE_LIGHTING` 10 / 100, `MOON_PHASE_INF_LIGHT` on | F6 | owner rule: nights stay dark, a night storm darker than a clear night |
| Fantasy sky and stars | `NV_FANTASY_SKY` .70, `NV_FANTASY_SUNSET` .80, `NV_STARFIELD` 1 (tuned) / 2, size .8, twinkle .25, colour .7, `NV_GALAXY` on, brightness .6, width 1, tilt 55, dust .7, colour (.62,.72,1.0), `NIGHT_STAR_AMOUNT` 1, `NIGHT_NEBULAE` -1 (off), `AURORA_STYLE` 1, `SUN_MOON_STYLE` 1 | F10-F13 | |
| Clouds | `CLOUD_STYLE_DEFINE` 3, `CLOUD_ALT1` 720 default (U: the owner remembers 575, open question), `NV_CLOUD_DENSITY` 1.3, `_DETAIL` 1.0, `_DEPTH_SHADING` .2, `_SILVER_LINING` .4, `NV_STORM_DARKEN` .9, `_THICKEN` 1.0, `CLOUD_UNBOUND_RAIN_ADD` 1.5, `_SIZE_MULT` 95, `CLOUD_SHADOWS` off | F14, M2 | |
| Tempest | `NV_TEMPEST` on, `_CLOUDS` on, `_CLOUD_ALT_FROM_PACK` on, `_CLOUD_STEPS` 48, `_CLOUD_CUBE` 6 and `_CLOUD_PIXEL` 6 (pixelated clouds, tuned) / 4 and 1, `_VOLUME` .25, `_SAND` 1.5, `_EMBER` 2.0, `_FLASH` .5, `_FUNNEL` .5, `NV_STORM_GLOOM` .75 | M1-M3 | |
| Storms | `NV_STORM_FOG` .6, `_FOG_DAY` .5, `_NIGHT_DARKNESS` .8, `_VISIBILITY_RAIN` 128, `_VISIBILITY_SNOW` 8, `NV_BLIZZARD` 1.2, `_WIND` 1.25, `_FOG_DISTANCE` 128, `_NIGHT_FOG_DISTANCE` 32, `NV_STORM_GUSTS` .75, `_LIGHT_GLOW` 1.5, `_LIGHT_REACH` 4, `_LIGHT_BLOOM` 1.0, `NV_LIGHTNING_BRIGHTNESS` 8, `WEATHER_TEX_OPACITY` 140, `RAIN_STYLE` 2 | F17-F19 | |
| Fog | `NV_MOUNTAIN_FOG` 1, opacity .5, height 24, density 1, patches .6, `NV_DISTANCE_HAZE` .15, `NV_FOG_WALL` 0 (off), `ATM_FOG_MULT` .8, `_DISTANCE` 200, `_ALTITUDE` 69, `BORDER_FOG` off | F15, F16 | |
| Light | `COLORED_LIGHTING` 128, `COLORED_LIGHT_FOG_I` .85, `COLORED_LIGHT_SATURATION` 120, `XLIGHT_I` 1.5, `XLIGHT_CURVE` 1.5, `SHADOW_QUALITY` 1, `shadowDistance` 96, `SSAO_I` 140, `VANILLAAO_I` 140, `PIXELATED_AO`/`PIXELATED_SHADOWS` on, `ENTITY_SHADOW` -1 | F1-F7 | pixelated shadows/AO: texel-snapped look (U how it reads in RT) |
| Colour | `TM_EXPOSURE` .95, `TM_CONTRAST` 1.20, `T_SATURATION` .70, `T_VIBRANCE` 1.0, `TM_DARK_DESATURATION`/`TM_WHITE_PATH` default, `BLOOM_STRENGTH` .19, `NV_LIGHT_BLOOM` 1.2 / `_NIGHT` 1.2 / `_THRESHOLD` .6 / `_RADIUS` 1.25 / `_WARMTH` .15, `MOTION_BLUR_EFFECT` 1 strength .55, `VIGNETTE_R` off, `CHROMA_ABERRATION` 0, `IMAGE_SHARPENING` 5, `COLORGRADING` default | F31-F35 | calibration targets for the RT tone map |
| Water | `WATER_STYLE_DEFINE` 2, `WATERCOLOR_MODE` 2, `WATERCOLOR_B` 120, `WATER_ALPHA_MULT` 170, `WATER_FOG_MULT` 130, `WATER_CAUSTIC_STYLE_DEFINE` 1, `WATER_REFLECT_QUALITY` 1, `BLOCK_REFLECT_QUALITY` 1 | F21, F22, F30 | |
| Materials | `RP_MODE` 3, `GENERATED_NORMALS`/`COATED_TEXTURES` on, `GENERATED_NORMAL_MULT` 120, `NORMAL_MAP_STRENGTH` 100, `POM` on (`POM_QUALITY` 64, `POM_DISTANCE` 24, `POM_LIGHTING_MODE` 1), `WAVING_*` on, `RAIN_PUDDLES` 1, `GLOWING_ORE_MASTER` 1, `IPBR_EMISSIVE_MODE` 1, `DETAIL_QUALITY` 2 | F24-F27 | |
| Outside of Time | `NV_TIMELINE_*` (tuned): brightness .30, speed 1.25, branches 4, branch height 1.4, tilt 4, curtains 3.0, stars .5, nebula 1.0, giant nebulae .75, galaxies .75, blink .25, blink speed .25, void .5, light 1.4, worlds 1.25, worlds speed .5, fracture .25 | F37 | |

Per-time-of-day colour multipliers (`LIGHT_MORNING/NOON/NIGHT/RAIN/NETHER/END_R/G/B/I`, `ATM_*`) are at pack defaults in the tuned
file; the RT sky/sun palette needs a day / dusk / night / rain / nether / end table (re-derived; the base multiplier tables may not
be copied).

### 3b. Proposed RT "Advanced Options" list (Navidson option -> RT control; default = the level's value; editing any marks the level "Custom")
Grouped as screens. "L/M/H" = default per level (- = control disabled at that level). Defaults for look values are the section 3 values at every level.
1. Effects (per level default L/M/H): RT sun/moon shadows on/on/on; shadow softness (penumbra size) .5 (maps SHADOW_QUALITY/`SHADOW_SMOOTHING`); block-light shadows off/on/on; reflections -/on/on, reflection roughness cutoff -/.3/.6 (WATER_/BLOCK_REFLECT_QUALITY); GI -/-/on, GI bounces 1/1/2 and GI distance; AO -/-/on, AO radius and strength (SSAO_I 140); caustics -/-/on, strength (WATER_CAUSTIC_STYLE); volumetric light shafts off/off/on, strength (LIGHTSHAFT_*); emissive light strength 1.0 (XLIGHT_I 1.5, XLIGHT_CURVE 1.5); coloured light saturation 120 (COLORED_LIGHT_SATURATION); coloured light fog .85.
2. Quality and budget: rays/samples per pixel tier, ReSTIR on/off, light-grid radius (COLORED_LIGHTING 128 analogue), residency radius, checkerboard half rate, performance budget target (ms), dynamic resolution link (FSR).
3. Denoising: denoiser per signal (shadows / reflections / GI: own, FFX 1.1.4, OIDN once the A/B picks) and strength per signal.
4. Light and sky: sun intensity, moon intensity, sky/ambient intensity, night light/ambient/sky/cloud multipliers (NV_NIGHT_*), moon phase influence, cave floor (CAVE_LIGHTING), fantasy sky and sunset strength, star field on/brightness/size/twinkle, galaxy on/brightness, sun/moon style, aurora style, nebulae.
5. Atmosphere and weather: cloud density/detail/depth shading/silver lining/altitude (CLOUD_ALT1)/pixelated cube, Tempest on/volume/sand/ember/flash/funnel/gloom, storm fog/darkness/visibility rain+snow, blizzard strength/wind/fog distance, lightning brightness, mountain fog on/opacity/height/density/patches, distance haze, atmospheric fog mult/distance/altitude, fog wall distance.
6. Water: water colour mode and RGB, alpha, fog density, foam, wave bumpiness/speed/size, refraction strength, underwater tint.
7. Colour: exposure (auto/manual, TM_EXPOSURE .95), contrast 1.20, saturation .70, vibrance, bloom strength .19, light-bloom strength/threshold/night/radius/warmth, elemental regions on, strength, grade/light/sky amounts, per-element tint/saturation/contrast, motion blur on/strength .55, sharpening 5, vignette, chromatic aberration.
8. Materials: LabPBR on, generated normals strength 120, emission intensity, glowing ores, normal-map strength, waving on/speed, rain puddles/wetness, POM if kept.
9. Outside of Time: the `NV_TIMELINE_*` set (brightness, speed, branches, curtains, stars, nebulae, galaxies, fracture, light) shown only while in that dimension.
Not offered (no RT meaning): shadow map resolution/distance, SSR/WSR resolution, TAA/FXAA, DH/Voxy options, profile list (the level IS the profile).

## 4. What the Iris-style Shader Settings menu must support for Navidson's `shaders.properties`

Navidson is 69 `screen.*` definitions, 8 profiles, about 274 slider names, `lang/en_US.lang` 2723 lines (V). The menu (GLSL mode
only) must handle:
1. **Option discovery from GLSL**: `#define NAME value //[v1 v2 ...]` slider/cycle lists (115 NV options in `navidson.glsl`, about 426 in
   the pack) and bare toggle `#define NAME` / `//#define NAME`. Iris registers a boolean only when a bare `#ifdef NAME` exists
   somewhere (trap: this is why `NV_DYNLIGHTS` never appeared; the menu must report such options as "has no effect" in the log
   rather than silently dropping them, and the loader must define them from the file value regardless).
2. **`sliders=`** one long list: only the named options render as sliders, the rest as cycle buttons.
3. **Screens**: `screen=` root, nested `screen.NAME=`, `[SUBSCREEN]` references, `<empty>` spacer entries, `<profile>`, `<empty>
   <empty>` pairs as column breaks, `screen.NAME.columns=N` (used for `NV_GALAXY_COLOR`), 4-level nesting (Navidson > Elemental > Air).
4. **Profiles**: `profile.POTATO .. COMPLEMENTARY` (8), each a space-separated `NAME=VALUE` list including `shadowDistance` (a
   non-define Iris option), plus the `<profile>` widget showing "Custom" when settings diverge.
5. **Conditional properties**: `#if/#ifdef` on option values inside `shaders.properties` (COLORED_LIGHTING picks image sizes,
   `SHADOW_QUALITY == -1`, `IRIS_FEATURE_*`, `DISTANT_HORIZONS`, `MC_OS_MAC`), evaluated after options are applied.
6. **Language file**: `option.NAME`, `option.NAME.comment`, `value.NAME.v`, `screen.NAME`, `profile.X` from `lang/<locale>.lang`, with Minecraft `§` colour codes; fall back to the raw name.
7. **Options file**: the owner's settings live in `shaderpacks/Navidson.txt` (Java properties, `NAME=value`); per-pack file name
   = pack name + `.txt`; changing a value that changes `#define`s forces a recompile (macro-driven, so it needs `iris-loader`'s
   program cache key to include option values).
8. **Pipeline properties that depend on options**: `size.buffer.*`, `scale.composite1/deferred`, `image.*`, `bufferObject.*`,
   `program.world-1/composite1.enabled`, `blend.*`, `alphaTest.*`, `customTexture.*` (conditional), `iris.features.optional`.
9. **Custom uniforms**: `uniform.float.*`, `variable.float.*` with `smooth(id, expr, up, down)`, `if()`, `in()`, `min/max/sqrt/abs`,
   biome constants (`BIOME_*`), 31 expressions; a trailing `# comment` on one of these lines must be tolerated or reported (Iris
   fails to parse it).
10. **Extra programs by name** (`vista_*`) and per-dimension folders `world0 / world-1 / world1 / world2` with
    `dimension.properties` mapping; the pack list must show packs that need features Iroh lacks (greyed with the reason).
11. **Add own packs** (folder or zip via drag-and-drop): the same discovery must work for packs that do not use these features;
    the menu must never assume Complementary naming.

## 5. Raster-only post effects the RT levels can reuse unchanged (in algorithm; Iroh implements its own pass)
Image sharpening (final.glsl:55), vignette/chroma (both off in the tuned file), FXAA (replaced by FSR), motion blur (needs Iroh
motion vectors; owner-ON, so keep), underwater distortion, the light-bloom halo and bloom (operate on HDR colour + depth), the
elemental grade (colour only plus world XZ from depth), colour grading matrix, lens flare (off), precipitation overlays (particles).
These do not need rays; they are cheap post passes after FSR or before it (tone map before FSR per iris-loader seam notes).

## 6. Screenshot-pair test scenes (Navidson via GLSL level vs RT High; same seed, camera, FSR setting; RT Low and Medium compared for the effects they own)
All captured through Proctor by the orchestrator (implementers never launch). Fixed: 1440p, vanilla clone for geometry-only rows, pack clone for mod rows, 60 fps cap.

| # | scene | time / weather | mods | compare |
|---|---|---|---|---|
| S1 | Village on a hill, flat grass field | noon, clear | none (vanilla clone) | sun shadow edges, grass luminance ratio, tone map, sky palette, elemental tint at two camera headings (Air N, Fire S) |
| S2 | Same spot, dusk and dawn | sunset | none | fantasy sunset palette, shadow length, fog colour |
| S3 | Open field | night, new moon and full moon | none | night darkness, stars/galaxy, moon light, ambient floor ("never brighten a night") |
| S4 | Torch-lit cave, lantern and soul torch room, redstone lamp | any | elemore (modded emitters) | coloured light hues, block shadows, AO, `CAVE_LIGHTING` floor |
| S5 | Held torch / lantern in a dark tunnel, then a second player holding one | night | elemore-core dynlights | own and other-player held light radius, colour, falloff |
| S6 | Lake with glass wall and stained glass | noon | none | water colour/alpha/fog, reflections of sky/blocks, refraction, caustics on the lake floor, stained glass tint |
| S7 | Underwater at depth 6 and 20 | noon | none | underwater tint, distortion, fog, caustics |
| S8 | Mountain valley, camera on a peak looking at far terrain | morning | Vista (far terrain) | mountain fog banks, far-field blend, distant shadows, LOD water/fog match |
| S9 | Rain at the Overworld edge, then thunderstorm | day and night | Tempest, Fractal Lightning | storm gloom, storm fog, rain visibility 128, bolt colour/brightness, flash light, cloud look |
| S10 | Tornado funnel in a supercell | day | Tempest | cloud layers, wall cloud, funnel, dust ring, sand wall, anvil |
| S11 | Blizzard in a snowy biome | day and night | Tempest | snow visibility 8, world-anchored flakes, white-out, fog distance |
| S12 | Ember/ash weather (Nether-side or Tempest ash) and dust storm | day | Tempest | ember glow, ash, sand wall |
| S13 | Elemental border walk across the Air/Water seams and the neutral centre (x=z=0, radius 1200) | noon | Citadels layout | grade blend width, centre fade, light/sky tint |
| S14 | Citadel interior and a boss arena | noon | Citadels, elemore-bosses | geometry-light, emissive materials, entity lighting |
| S15 | Outside of Time shrine | fixed | Citadels | timeline sky, cataclysm worlds, branches, nebulae, fracture; dimension colours |
| S16 | Nether (crimson forest, basalt, soul valley) and End (beam, flashes) | fixed | none | per-dimension palette, nether storm, End stars/flash, biome fades |
| S17 | Forest with waving leaves and flowers, rain puddles, snow-covered ground | noon then rain | none | waving mismatch (F27), puddles/wetness (F20), snowy world, leaf shadows |
| S18 | Ore wall with deepslate ores, glowing amethyst, lava, glow lichen (no resource pack) and the same with a LabPBR pack | any | none | iPBR emissives (F25), LabPBR (F24), POM bricks (F26) |
| S19 | Entity group: armoured player, spider eyes, glowing squid, boss | night | GeckoLib/AzureLib bosses | entity materials, glow, glint, entity shadows |
| S20 | Bending/spell effect and fire particles at night | night | elemore, bending mods | particle compositing after RT, bloom/halo behaviour |

## 7. Gap summary and proposed cards
GAP count: see section 8 line "Totals". Suggested cards to add to RT-PLAN (all depend on RT-3b look path; Sonnet unless noted):
- NP-1 look constants and grade: F6, F10, F31, F32, F33, F34, F36 palettes + `RtLookModel` constants of section 3 (the owner's
  `elemental.glsl` after the relicense decision; other pieces re-derived). Acceptance: S1-S3, S13 pairs.
- NP-2 sky pass: F11-F14, F37 (owner files `starfield.glsl`, `timeline.glsl`, `cataclysm.glsl`; cloud march own). S3, S15.
- NP-3 Tempest: M1-M4, F9, F17, F19 (consumer registration, five maps as GPU textures, cloud march + funnel, volumes, storm term). S9-S12.
- NP-4 lights: F5, M5 (held and other-player lights into the RT light list), plus F4 coloured light colour check. S4, S5. Fix the pack's
  `NV_DYNLIGHTS` bare `#ifdef` too (owner decision, GLSL path).
- NP-5 volumetrics, AO, caustics: F7, F8, F22 (High-level features with no card today). S4, S6.
- NP-6 surface parity: F20, F26, F27 decisions (puddles/snow, POM, waving) and F25 per-block material authoring (own table). S17, S18.
- NP-7 look-model uniforms: M9 (rain/thunder/in-cave/biome fades/end flash smoothed scalars in `RtFrameData`).
Owner questions: relicense of owner files (blocks NP-1/NP-2/NP-3 re-use), POM and waving in RT (accept loss?), generated normals default (Navidson ON vs RT-4c Q2 OFF), `CLOUD_ALT1` 720 vs 575, which `Navidson.txt` is the live one, `v2/` intent.

## 8. Tally
GAP rows (status begins with GAP): F5, F7, F8, F10, F11, F12, F13, F14, F22, F26, F27, F20, F31, F32, F33, F34, F37, F17, M2, M3, M4, M5, M9 = 23.
PARTIAL rows: F2, F3, F4, F6, F9, F15, F19, F21, F25, F36, M1, M6, M11 = 13. COVERED: F1, F16, F18, F24, F28, F29, F30, M8 = 8.
REUSE/N/A: F23, F35, M10, M12; OPEN: M7, M13.
Totals: 23 GAP, 13 PARTIAL.
