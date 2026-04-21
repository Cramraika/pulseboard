# Design History — Pulseboard

## 2026-04-20 — Port spec to code (colors.xml + themes.xml)

Per `~/.claude/specs/2026-04-20-per-repo-design-audit.md` + Track 2 port.

**Situation**: Pulseboard docs/design/ was the most complete in the fleet (9/9 scaffold + `screens/` dir) — but zero spec values had been ported to Android resources. `colors.xml` held only default Material 2 purple/teal; `themes.xml` hardcoded `colorPrimary:#4F46E5` indigo.

**Action**:
- `app/src/main/res/values/colors.xml` — added full brand palette (cyan `#0FC9E3` primary, lime `#B8E94A` accent, network-health semantic scale `healthy/degraded/poor/offline`, dark-mode-first neutral ramp 50–950). Preserved legacy Material 2 `purple_*` + `teal_*` entries as unused-but-referenced (may appear in old layouts).
- `app/src/main/res/values/themes.xml` — swapped hardcoded `#4F46E5` + `#F5F5F5` for theme attrs pointing at brand_primary + brand_primary_muted + brand_accent + neutral_50. Preserved `Theme.MaterialComponents.Light.NoActionBar` parent + light-only posture note (DayNight caused white-on-white onboarding regression per prior comment).
- `app/src/main/res/values-night/themes.xml` — created. Mirror of day theme with dark-mode-first neutrals (`neutral_950` background). Additive — won't take effect until layouts use `?attr/colorSurface` instead of hardcoded white, but the infrastructure is now in place.

**Not touched**: Kotlin/Java source, layouts, drawables, launcher mipmaps — only the color + theme XML layer.

**Playstore gap**: feature graphic (1024×500), 5 phone screenshots, 7-inch + 10-inch tablet screenshots, 512×512 high-res icon still missing from `docs/design/assets/`. Track 4+ follow-up.
