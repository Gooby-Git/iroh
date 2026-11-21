Synthetic core-shader override pack for the offline harness (RQ-14 test bed). Never loaded by the game.
Run: ./gradlew shaderHarness -Ppack=<dir>[,<dir>...]   (several packs: later wins; see ShaderHarness.packMode)
Expected (P1-11): rendertype_solid, rendertype_entity_cutout (own include), rendertype_lightning (unchanged copy),
rendertype_text (undeclared uniform GameTime -> "zero GameTime"), synth_new, synth_legacy (#version 120) PASS;
rendertype_eyes (syntax error) FAIL [compile-fsh]; rendertype_leash (JSON names a missing fsh) FAIL [load-fsh].
Survey pack ~/Projects/proctor/scenarios/iroh/vanilla/corepack/ParityCore: 4 of 4 PASS.
