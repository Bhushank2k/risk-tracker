# Risk Tracker — Quiet Trading Desk Design System

## Product posture
Calm, precise and candid. This is a crypto-futures risk tracker, journal and calculator, not an exchange terminal. The app does not place trades or execute orders. Never imply that a score guarantees safety, a check approves a trade, or historical results predict returns.

## Semantic palette

### Light
| Role | Token | Hex |
|---|---|---|
| Background | Cloud Canvas | #F5F7F8 |
| Surface | Paper Surface | #FFFFFF |
| Raised surface | Raised Surface | #EEF2F3 |
| Primary text | Charcoal Ink | #202A32 |
| Secondary text | Slate Ink | #586570 |
| Metadata | Quiet Ink | #7C8993 |
| Divider | Structural Line | #DCE3E6 |
| Primary accent | Evergreen Accent | #356859 |
| Accent container | Evergreen Tint | #E5F0EB |
| Critical / destructive | Critical Red | #B42332 |
| High risk | High Amber | #A85B18 |
| Caution / incomplete | Caution Gold | #8A6B12 |
| Positive outcome | Positive Green | #287A54 |
| Imported / information | Information Blue | #456D9B |

### Dark
| Role | Token | Hex |
|---|---|---|
| Background | Night Canvas | #111719 |
| Surface | Night Surface | #192124 |
| Raised surface | Raised Night Surface | #222D31 |
| Primary text | Night Ink | #E8EEEF |
| Secondary text | Muted Night Ink | #A6B3B8 |
| Metadata | Quiet Night Ink | #829197 |
| Divider | Night Structural Line | #344247 |
| Primary accent | Evergreen Accent | #83B6A3 |
| Accent container | Evergreen Night Tint | #243B34 |
| Critical | Critical Red | #FF7A83 |
| High risk | High Amber | #EBA76C |
| Caution / incomplete | Caution Gold | #E3C46C |
| Positive outcome | Positive Green | #80C9A2 |
| Imported / information | Information Blue | #9AB9E5 |

Use semantic roles rather than hard-coded screen colors. Red is reserved for critical/destructive states; amber/gold communicate high/caution states. Positive values always include a leading plus sign. Color is never the only status signal: pair every severity color with a readable label or icon.

Compose definitions live in `DesignSystem.kt` as `RiskTrackerLightColors`, `RiskTrackerDarkColors`, `RiskTrackerDesign` and `RiskTrackerTypography`.

## Typography
- Interface: Geist where licensed font assets are bundled; current explicit Android fallback is `FontFamily.SansSerif`.
- Financial values: Geist Mono where bundled; current explicit fallback is `FontFamily.Monospace`.
- Screen title: 26–30sp semibold.
- Section title: 18–20sp semibold.
- Card title: 15–17sp.
- Body: 14–16sp with generous line height.
- Supporting copy: 12–14sp.
- Primary KPI: 28–36sp monospace; dense values 12–14sp monospace.

Respect system font scaling. Values, units, status labels and warnings must wrap or reflow rather than clip.

## Layout and surfaces
- 16dp horizontal page padding; 8dp spacing grid; 16dp item gaps; 24dp section gaps.
- Cards: 16dp radius and 16–20dp internal padding.
- Inputs and compact controls: 12dp radius; chips fully rounded.
- Touch targets at least 48dp.
- Prefer whitespace and thin dividers over stacked elevated cards. Elevation is for dialogs, menus and sheets.
- One primary action per screen region. Destructive controls are visually separated.
- Do not use an equal three-column KPI card wall as the default dashboard.

## Navigation
Bottom navigation: Overview (`dashboard`), Journal (`receipt_long`), Risk (`fact_check`), Analytics (`monitoring`). Each item always has an icon and text label. Specialist destinations remain reachable from More/Explore: Calculator, Scenario Lab, Reconciliation, Plans & Reviews, Settings and the other existing feature screens.

## Charts and data provenance
Every chart has a title, period, units and a plain-language takeaway. Include a zero baseline when it changes interpretation; label truncated axes. Keep missing values distinct from zero. Label imported, manual, estimated and incomplete data at the point of use. Missing history is `Incomplete`, never zero profit. Show assumptions (fees, funding, multipliers, leverage) beside the values they affect. Sample/demo values must carry a visible `SAMPLE DATA` label.

## Shared UI states
- Empty: one benefit sentence and one meaningful action.
- Loading: skeletons matching final dimensions; long syncs expose the current step.
- Error: name the failure, state whether local data is safe, and offer recovery inline rather than only in a toast.
- Success: report exact accepted/skipped/rejected counts only after the operation succeeds.
- Delete, reset and restore require a confirmation dialog naming the exact affected data.

## App icon
Flat vector semicircular gauge, three separated segments (two evergreen, one amber), one upper-right charcoal needle, rounded 6px stroke. No gradients, shadows, text or chart/candlestick imagery. Adaptive foreground artwork stays within the 66dp safe circle of the 108dp layer. Supply a Cloud Canvas background, monochrome Android 13+ layer and night artwork. SVG masters live under `assets/`; Android vector layers live under `app/src/main/res`.

## Motion and accessibility
Use restrained 140–220ms pressed/selected motion and 180–280ms sheets. Charts animate once on entry. Respect reduced-motion settings. Maintain WCAG AA contrast where possible, content descriptions, logical TalkBack order, font scaling and system insets.

## Prohibited visual/copy patterns
No neon, purple glow, gradient CTAs, glassmorphism, blurred data backgrounds, rainbow metric cards, full-screen red/green fills, candlestick/rocket clichés, emoji navigation/status icons, or the phrases “Elevate”, “Seamless”, “Unleash” and “Next-gen”.

## Non-negotiable preservation
Do not alter order execution behavior (there is none), trading calculations, read-only Binance permissions, API credentials, sync cadence, local records, import/export semantics, persistence keys, migrations or navigation route IDs as a side effect of visual work.
