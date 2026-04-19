# Pulseboard — Components

Android-native. Jetpack Compose (Material 3).

## Framework

- **Jetpack Compose** with Material 3 theming
- **Accompanist** for permissions + system UI controller
- **Icons**: Material Symbols (outlined variant)
- **Charts**: **Compose Multiplatform charts** OR **Vico Compose** for pulse-waveform viz

## Custom components (Pulseboard-specific)

### 1. `MetricCell`
The fundamental 6-cell dashboard unit (AVG / MAX / P99 / JITTER / LOSS / NETWORK).
- Large monospaced metric + small label below
- Color-coded by `--status-*` tokens
- Tap → detail sheet with last 24h trend

### 2. `PulseWave`
Animated heartbeat viz on the main screen.
- Stroke width = jitter (thicker when unstable)
- Color = health status
- Frequency = sample cadence (1Hz — always)

### 3. `NetworkBadge`
Current connection type (WiFi name, or "Cellular", or "Offline").
- Icon + label
- Subtle ambient glow if offline

### 4. `SheetStatusRow`
Last upload timestamp + success/fail + "Tap to force sync" action.
- Primary affordance on settings screen

### 5. `FabricChip`
Filter chip for selecting which metrics to display.
- Multi-select, animates at 150ms

## Inherited Material 3 components

Use as-is without override:
- `TopAppBar` (Centered variant)
- `Card` (Outlined variant on dark surface)
- `NavigationBar` (if adding tabs later)
- `Switch`, `Slider`, `Checkbox`
- `Snackbar`

## Icons

**Material Symbols (outlined, 24dp)**. Key icons mapped:
- `sensors` → main dashboard
- `network_check` → connection status
- `grid_view` → metric grid
- `tune` → settings
- `share` → share Sheet URL
- `auto_graph` → trends (future)

Custom icons (commissioned separately):
- `logo/pulseboard.svg` — launcher foreground
- `logo/pulseboard-wordmark.svg` — splash + about

## Animation

**Motion durations (follow Material 3 standard motion):**
- Fast: 100ms (button press)
- Standard: 250ms (screen transition)
- Slow: 400ms (pulse-wave breathe cycle)

**Easing:** `FastOutSlowInEasing` (Material 3 default) for most; `LinearEasing` only for continuous pulse wave.

## Spacing & layout

- Base unit: 4dp
- Card padding: 16dp
- Section gap: 24dp
- Screen margins: 16dp (portrait) / 32dp (landscape, if ever)
- Touch target min: 48dp

## Dark-mode first, light-mode available

Both themes defined in `res/values/themes.xml` + `res/values-night/themes.xml`. User follows system setting by default.
