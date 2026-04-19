# Stitch project for Pulseboard

- **Status**: Not yet created
- **To create**: `mcp__stitch__create_project(name="Pulseboard")`
- **Then**: create design system with tokens from `../palette.md` + `../typography.md` via `mcp__stitch__create_design_system`
- **Dashboard URL**: TBD (populate after create)
- **Design system ID**: TBD
- **Last sync**: never

## Screens to generate first (when ready)

1. Main dashboard (6 cells: avg/max/p99/jitter/loss/network)
2. Detail trend (24h chart for one metric)
3. Settings (endpoint URL, battery permission, sheet preview link)
4. Onboarding (3-step: deploy receiver, install, grant permissions)
5. Play Store listing screenshots (5 screens, portrait)

Generate via `mcp__stitch__generate_screen_from_text(...)` after design system is applied.
