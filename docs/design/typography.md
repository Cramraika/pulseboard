# Pulseboard — Typography

## Font stack

- **Display (headings + numbers)**: **JetBrains Mono** — technical, monospaced digits keep tabular alignment on the dashboard
- **Body**: **Inter** (system fallback: `-apple-system, sans-serif`)
- **Launcher/Brand mark**: custom SVG logotype (see `assets/`)

Android: bundle fonts via `assets/fonts/` or use Google Fonts Downloadable Fonts API.

## Scale (Material 3 type scale + custom digits)

| Role | Size sp | Weight | Line-height |
|---|---|---|---|
| Display Large | 48 | 500 | 56 |
| Display Medium | 34 | 500 | 40 |
| Headline Large | 28 | 600 | 36 |
| Headline Medium | 22 | 600 | 28 |
| Title Large | 18 | 600 | 24 |
| Title Medium | 16 | 600 | 24 |
| Body Large | 16 | 400 | 24 |
| Body Medium | 14 | 400 | 20 |
| Body Small | 13 | 400 | 18 |
| Label Large | 14 | 500 | 20 |
| Label Small | 11 | 500 | 16 |
| **Metric Large** (custom) | 32 | 500 | 36 (mono) |
| **Metric Small** (custom) | 14 | 500 | 20 (mono) |

## Weights

- Regular 400 (body)
- Medium 500 (metrics + labels)
- Semibold 600 (headings)

Avoid 700+ — app is dense with numbers, too-heavy weights create noise.

## Tabular digits

Every displayed metric (avg, max, p99, jitter, loss%) uses monospaced digits. Ensures column alignment when multiple devices' numbers stack in the Sheet preview.

## Monospace (code / raw-log panels)

Same JetBrains Mono as display, at Body size. Used in `debug_logs.xml` layouts only.
