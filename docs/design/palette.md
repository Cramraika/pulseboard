# Pulseboard — Palette

Dark-mode-first (IT audience). Light mode exists but is the minority path.

## Primary — Signal cyan
```
--brand-primary:        #0FC9E3  /* cyan network pulse */
--brand-primary-hover:  #2BD6EE
--brand-primary-muted:  #0A8796
```

## Accent — Lime heartbeat
```
--brand-accent:         #B8E94A  /* lime, for healthy-pulse indicators */
--brand-accent-muted:   #89B236
```

## Semantic (network-health native)
```
--status-healthy:   #4ADE80   /* p99 <100ms, loss <1% */
--status-degraded:  #FBBF24   /* p99 100-300ms, loss 1-5% */
--status-poor:      #F87171   /* p99 >300ms or loss >5% */
--status-offline:   #64748B   /* no sample in last hour */
```

## Neutrals (dark-mode-first scale)
```
--neutral-950:  #0A0D14     /* app background */
--neutral-900:  #121826     /* surface raised */
--neutral-850:  #1A2233     /* card background */
--neutral-700:  #2D3A52
--neutral-500:  #6B7B9C
--neutral-300:  #9CAAC8
--neutral-100:  #E7ECF5
--neutral-50:   #F8FAFD     /* light-mode base */
```

## Android mapping (values/colors.xml)

```xml
<color name="brand_primary">#0FC9E3</color>
<color name="brand_primary_hover">#2BD6EE</color>
<color name="brand_accent">#B8E94A</color>
<color name="status_healthy">#4ADE80</color>
<color name="status_degraded">#FBBF24</color>
<color name="status_poor">#F87171</color>
<color name="status_offline">#64748B</color>
<color name="neutral_950">#0A0D14</color>
<!-- etc -->
```

## Material 3 role mapping

- `colorPrimary` → `--brand-primary`
- `colorSecondary` → `--brand-accent`
- `colorError` → `--status-poor`
- `colorSurface` → `--neutral-900`
- `colorSurfaceContainer` → `--neutral-850`
- `colorOnSurface` → `--neutral-100`
- `colorOnSurfaceVariant` → `--neutral-300`

## Launcher icon
Cyan pulse wave on dark charcoal ground. Single-color logotype compatible with Android adaptive icons (foreground + background layer).
