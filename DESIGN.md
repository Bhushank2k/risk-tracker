# Risk Tracker — Design System

## Visual direction
A disciplined, premium trading-terminal interface: dark-first, data-dense but readable, compact, precise and calm. Use the same visual language on every destination.

## Palette
- Dark canvas: #111418
- Dark surface: #1B2026
- Raised surface: #242B33
- Dark border: #303841
- Primary text: #F1F5F9
- Secondary text: #929DAA
- Emerald accent / positive: #35B77A
- Negative: #E16B70
- Warning: #E5AC52
- Light canvas: #F5F7F6
- Light surface: #FFFFFF

Use Emerald as the only decorative accent. Red and amber are semantic only. Never rely on color alone to communicate status.

## Typography
Use the app's Material typography with a clear hierarchy: page title 22–24sp semibold, section heading 16–18sp semibold, key financial value 26–32sp bold, body 14sp and metadata 12sp. Use tabular numerals for prices, balances, percentages and timestamps.

## Icons
Use the existing Material Icons Extended family consistently. Navigation icons 24dp, page icons 22dp, card icons 18–20dp and inline status icons 16dp. Use semantic mappings; avoid decorative or mixed icon families.

## Cards
Use 18dp corners, 16dp internal padding, subtle 1dp borders and no default shadow. Reserve cards for meaningful groups. For dense statistics, use rows and dividers rather than nested cards. Align labels and values consistently.

## Page hierarchy
Use a compact context-aware top app bar with page icon, title and only relevant actions. Avoid redundant page title blocks. Use consistent 18dp horizontal page gutters and 12–18dp section spacing. Keep financial values visually dominant.

## Navigation
Bottom navigation has four primary destinations: Overview, Journal, Analytics and Ideas. Use icon-above-label, visible labels and a clear emerald selected state. Secondary destinations live in Explore, grouped into Home, Market Opportunities, Analytics, Risk Management, Binance & Data, and Reports & App. Keep every existing destination and route.

## Status and feedback
- Success / up to date: emerald check.
- Warning / incomplete: amber warning.
- Failure: red error.
- Progress: restrained progress indicator.
- Information: neutral information icon.
Show timestamps and retry actions when relevant. Never display success before the underlying operation succeeds.

## Motion and accessibility
Use restrained 150–250ms transitions, tactile press feedback and layout-matched skeletons. Minimum 48dp touch targets, readable contrast, system font scaling, no clipped financial values and proper Android system-bar insets.

## Non-negotiable preservation
Visual changes must not alter trading calculations, sync cadence or behavior, API credentials, local records, import/export semantics, persistence keys or navigation destinations.
