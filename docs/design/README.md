# Design surface — Pulseboard

**Tier A** per `~/.claude/conventions/design-system.md` §2. Source of truth for palette, typography, components, voice.

Any palette or typography value that appears in code (Kotlin compose themes, resource XML, launcher icons) MUST match this directory (drift-trigger per universal-claudemd.md §40 doc-vs-code drift).

## Files

- `brand.md` — Brand identity, story, mission, voice summary
- `palette.md` — Color tokens (CSS + Tailwind + Android values/colors.xml mapping)
- `typography.md` — Font stack, type scale, weights
- `components.md` — Android component choices (Material 3 + custom widgets)
- `voice.md` — Tone, do/don't, sample copy
- `references/stitch.md` — Stitch project pointer
- `references/figma.md` — Figma file pointer
- `assets/` — Logo, launcher icons, marketing PNGs (commissioned separately)
- `screens/` — Generated screen mockups (from Stitch)

## Audience primary

IT/DevOps teams, sysadmins, SREs on Android. OSS fork-and-rebrand users also consume this design system when white-labeling.
