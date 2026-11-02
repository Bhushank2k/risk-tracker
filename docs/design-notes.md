# Design System: Risk Tracker

A native Android design system for a crypto-futures risk and performance companion built with Kotlin and Jetpack Compose. Use this document as the visual source of truth when generating screens in Google Stitch and when implementing them in Compose.

## 1. Visual Theme & Atmosphere

**Design direction: Quiet Trading Desk.** The product should feel like a disciplined financial instrument: calm, precise, data-dense where useful, and never like a casino or a live-trading terminal. The interface helps a trader understand exposure, evaluate habits, review outcomes, and plan risk. It does not execute trades.

- **Density:** 7/10 on analytics and registers; 4/10 on forms and guided flows.
- **Variance:** 5/10. Use a strong left-aligned hierarchy, purposeful asymmetry in analytical sections, and consistent alignment for numbers.
- **Motion:** 3/10. Short, tactile transitions; no constant movement that makes financial values feel live when they are not.
- **Emotional tone:** composed, trustworthy, analytical, candid about data limitations.
- **Primary surface:** clean light mode with a fully considered dark counterpart. Honor Android System / Light / Dark appearance preference and the app's custom palette setting.
- **Platform:** native Android phone first. Respect system bars, edge-to-edge layout, gesture navigation, accessibility font scaling, and Material interaction expectations without looking like a stock component demo.

Never imply that a risk score guarantees safety or that historical performance predicts future returns. Label imported, estimated, manual, and incomplete data at the point where it is used.

## 2. Color Palette & Roles

Use one muted evergreen accent across the whole product. Severity colors are semantic data colors, not additional brand accents. Pair every severity color with a readable text label or icon; never communicate risk by color alone.

### Light theme

- **Cloud Canvas** `#F5F7F8` — Main screen background.
- **Paper Surface** `#FFFFFF` — Primary cards, sheets, menus, and dialogs.
- **Raised Surface** `#EEF2F3` — Secondary panels, selected rows, subtle input fills.
- **Charcoal Ink** `#202A32` — Main text and high-priority financial values. Never use pure black.
- **Slate Ink** `#586570` — Secondary labels and explanatory copy.
- **Quiet Ink** `#7C8993` — Timestamps and tertiary metadata; do not use for essential small text.
- **Structural Line** `#DCE3E6` — Dividers, outlines, table rules, and chart guides.
- **Evergreen Accent** `#356859` — Primary actions, selected navigation, focus state, progress, and positive brand emphasis.
- **Evergreen Tint** `#E5F0EB` — Accent container and selected-state background.
- **Critical Red** `#B42332` — Liquidation danger, severe risk, destructive action.
- **High Amber** `#A85B18` — High risk, drawdown warnings, and urgent review.
- **Caution Gold** `#8A6B12` — Moderate risk, incomplete reconciliation, and caution.
- **Positive Green** `#287A54` — Profitable outcome and verified positive movement. Do not rely on this color without a plus sign or explicit label.
- **Information Blue** `#456D9B` — Informational notices and neutral imported-source markers; never use as a competing brand accent.

### Dark theme

- **Night Canvas** `#111719` — Main background.
- **Night Surface** `#192124` — Primary cards and sheets.
- **Raised Night Surface** `#222D31` — Secondary panels, inputs, selected rows.
- **Night Ink** `#E8EEEF` — Primary text and financial values.
- **Muted Night Ink** `#A6B3B8` — Secondary labels and explanatory text.
- **Quiet Night Ink** `#829197` — Tertiary metadata.
- **Night Structural Line** `#344247` — Dividers, outlines, and chart guides.
- **Evergreen Accent** `#83B6A3` — Dark-mode accent; preserve the same semantic role as light mode.
- **Evergreen Night Tint** `#243B34` — Accent containers and selected-state backgrounds.
- **Critical Red** `#FF7A83` — Critical values and errors.
- **High Amber** `#EBA76C` — High-risk states.
- **Caution Gold** `#E3C46C` — Caution states.
- **Positive Green** `#80C9A2` — Positive movement.
- **Information Blue** `#9AB9E5` — Informational notices.

### Color behavior

- Use semantic tokens, not hand-picked one-off hex values in individual screens.
- Keep the main accent to evergreen. No purple/blue neon aesthetic, gradient CTAs, glowing charts, or rainbow metric cards.
- Red means danger or a destructive action, not a generic attention grabber. Amber and gold indicate risk or uncertainty. Green is reserved for positive outcomes and accent identity.
- For P&L, show a sign and currency/unit consistently. Avoid color-only distinctions and clarify whether a number is net of fees.
- In charts, use the accent for the main series, neutral tones for context, and semantic colors only for meaningful thresholds or annotated events.
- Meet WCAG AA contrast wherever possible, especially for small numeric labels and severity chips.

## 3. Typography Rules

Use **Geist** for interface text and **Geist Mono** for tabular numbers and compact data. If unavailable in the native build, use a well-supported Android sans-serif fallback with matching metrics; never block rendering while fonts load.

- **Screen title:** 26–30sp, semibold, tight but comfortable line height.
- **Section title:** 18–20sp, semibold.
- **Card title:** 15–17sp, semibold.
- **Body:** 14–16sp, regular, line height 1.4–1.55.
- **Supporting copy:** 12–14sp, normal contrast; critical caveats must not be tiny.
- **Overline / compact label:** 11–12sp, medium weight, modest tracking; use sparingly.
- **Primary KPI:** 28–36sp Geist Mono, medium/semibold, tabular figures.
- **Dense register values:** 12–14sp Geist Mono with stable digit alignment.
- **Buttons and controls:** 14–16sp, medium weight.

Use weight and spacing for hierarchy rather than oversized headlines. Keep explanatory copy to short line lengths and allow system font scaling. Never truncate balances, P&L values, dates, liquidation prices, or risk warnings without a way to inspect the full value.

## 4. Layout & Navigation

### App shell

- Use a single top app bar with a clear page title, contextual action(s), and no more than one prominent primary action per screen.
- Keep bottom navigation for the most frequent destinations only. Use the app's established hybrid bottom navigation plus a full destination menu for secondary tools; don't cram every feature into the bottom bar.
- Maintain consistent safe-area insets and 16dp horizontal screen padding.
- Use 8dp spacing increments. Typical screen gaps are 16dp; major section gaps 24dp; card internal padding 16–20dp.
- Prefer thin structural dividers and whitespace over a stack of deeply elevated cards. Use elevation only when a surface is genuinely layered, such as a dialog, menu, or bottom sheet.
- Standard card corner radius: 16dp. Inputs and compact controls: 12dp. Chips: fully rounded. Avoid excessive pill-shaped containers.
- Keep tap targets at least 48dp in both dimensions, with clear pressed, focused, disabled, and selected states.

### Information hierarchy

1. What changed and whether attention is needed.
2. Current balance, net performance, and risk-relevant headline metrics.
3. Trend or breakdown that explains the headline.
4. Records and details needed to verify the conclusion.
5. Source, coverage, calculation, and limitations.

Never invent example figures that look like the user's live account data. In Stitch mockups, label values as **Sample data** and use realistic, internally consistent figures.

## 5. Core Screens & Component Styling

### A. Overview / Daily P&L

Make the first screen a calm daily performance briefing, not a wall of metrics.

- Top row: title, selected date/range, and a compact source/sync status.
- Feature one primary balance or net P&L metric with its date range and source. Keep supporting metrics secondary.
- Use a clear line or area chart with restrained gridlines, readable date ticks, and a visible selected-point detail. Don't smooth away meaningful spikes.
- Provide a source-labelled daily P&L list or ledger. When Binance and manual values overlap on a date, explain that manual entries take precedence so totals are not double-counted.
- Show a concise insight only when supported by available data; if the sample is too small, say so instead of fabricating a conclusion.
- Include a clear path to CSV export and data-source details without making export the dominant action.

### B. Trade Journal

Treat records like a ledger. Optimize scanability and auditability.

- Each row has date/time, instrument or symbol, long/short side, net P&L, and a compact result label.
- Use aligned numeric columns and consistent signs, precision, and currency.
- Make the entire row accessible, but keep any destructive action separate and confirm before deleting.
- The detail view separates entry, exit, quantity, fees, side, and calculation. Clearly state that the journal estimate excludes funding, slippage, and exchange-specific contract multipliers.
- Empty state should explain the benefit of recording closed trades and provide one obvious action to add the first trade.

### C. Analytics & Drawdown

- Use a primary trend chart and one supporting breakdown rather than a grid of identical metric cards.
- Clearly distinguish net P&L after fees, win rate, profit factor, average win/loss, and maximum peak-to-trough drawdown.
- Explain each metric through short supporting text or an accessible info affordance.
- Label sample size and date range beside win rate and behavioral insights. Don't imply predictive certainty.
- Drawdown charts should make peak, trough, and recovery legible without dramatic red fills or flashing alerts.
- Keep chart legends interactive with a visible selected state and screen-reader descriptions.

### D. Position Sizing & Risk Calculator

The calculator should feel like a careful tool, not an execution ticket.

- Place editable inputs in a vertical form with persistent labels above the fields: account balance, risk amount or percentage, entry price, stop price, side, and any supported contract details.
- Use numeric keyboards where appropriate, preserve entered values on validation errors, and show units on every field.
- Put the result in a visually distinct but restrained result panel: risk amount, suggested position size, and any assumptions used.
- Validate zero, negative, non-finite, missing, and inconsistent values. Explain the exact field that needs attention.
- Show formula and assumptions in an expandable explanation. Never silently assume leverage, contract multiplier, fees, or currency conversion.
- Keep reset secondary and make no network or trade action look like a calculation button.

### E. Trade Risk Gate & Saved Plans

This is an advisory pre-trade check only. It must never imply that the app places or approves trades.

- Use a clear checklist of configured risk rules with three states: passed, needs attention, and not evaluated.
- Summarize the reasons behind the assessment before showing the overall result.
- For a blocked or high-risk plan, show the specific exceeded threshold and the user's configured limit. Don't use an unexplained score alone.
- Use explicit language such as **Advisory check**, **Review required**, and **Not evaluated**. Avoid “guaranteed safe,” “approved trade,” or execution-like success messages.
- Saved plans should show timestamp, instrument, key assumptions, and status. Provide an accessible edit/review path.
- Make the limits of a pre-trade check visible: it is only as good as the entered values and configured rules.

### F. Target Planner

- Feature target balance and current progress with a simple progress indicator, plus estimated daily net-profit pace and estimated completion window when enough data exists.
- State the observation period and calculation basis next to any pace estimate.
- If the available data is insufficient or the pace is non-positive, show an explanation instead of a misleading completion date.
- Avoid celebratory gamification, streak pressure, and guaranteed outcome language.

### G. Risk Scenario Lab

- Use a focused scenario setup followed by a clear result summary and drawdown visualization.
- Separate deterministic consecutive-loss stress tests from seeded historical-outcome simulations; label the method in the result itself.
- Show assumptions, sample size, and limits near the output. Historical simulation is not a forecast.
- Use neutral chart framing and precise annotations. Never use casino styling, roulette metaphors, or dramatic flashing warnings.

### H. Behavioral Intelligence & Post-Trade Review

- Present strategy and mistake-tag breakdowns only when there is sufficient data. Show sample size and a data-quality caveat.
- Make post-trade review feel reflective and non-punitive: plan adherence, setup, pre-trade state, and lessons learned.
- Use clear multiline fields, optional prompts, and a save state that confirms local persistence without suggesting cloud sync.
- Avoid moralizing language or unsupported psychological claims.

### I. Binance Sync & Historical Reconciliation

Data provenance is a first-class UI element.

- Separate Spot estimates, Futures income history, manual entries, and imported CSV totals by source and year.
- Show whether a record is imported, estimated, manual, or incomplete near the relevant total.
- For year-wise import, provide a preview before confirmation: selected year, observed date range, row counts, daily totals, net total, duplicates skipped, invalid-row status, and date-level differences when available.
- Use a clear confirmation step before replacing a year's saved Futures totals. Explain what changes and what remains unchanged.
- Make restore of the pre-import snapshot discoverable but protected by a confirmation dialog that explains the effect.
- For reconciliation, label the compared currency, year, and income categories. A mismatch over 0.01 USDT can be called out when that is the configured threshold, but a matching total is not proof of row-level correctness or complete coverage.
- Explain that missing dates may be legitimate days without income; don't imply a gap-free daily ledger proves export completeness.
- Treat years without recoverable data as **Incomplete**, never as zero P&L.
- Explain that Binance export links may be temporary and that API keys must be read-only. Never request or display secrets in UI logs, analytics, screenshots, or sample designs.
- Do not make sync appear instantaneous if it requires an asynchronous export request, waiting for availability, downloading, and importing a CSV.

### J. Leverage / Liquidation Reference

- Use a compact, searchable reference table with clearly labelled leverage bands and any supported assumptions.
- Distinguish reference estimates from exchange-specific live liquidation calculations.
- Include a persistent disclaimer that actual liquidation depends on exchange rules, maintenance margin, fees, position mode, and other account details.
- Never style a reference price as a guaranteed liquidation price.

### K. Settings & Theme

- Provide System, Light, and Dark appearance selection plus the app's custom palettes where supported.
- Use a clear selected state and explain any setting whose effect isn't immediate.
- Group data, sync, export, appearance, and privacy controls into meaningful sections.
- Destructive reset or restore controls must be visually distinct from ordinary settings and always require confirmation.

## 6. Charts, Tables & Financial Data

- Every chart needs a title, period, units, legible axes, and an accessible text summary of its main takeaway.
- Use Geist Mono and tabular numerals for balances, percentages, prices, and P&L.
- Keep decimal precision consistent within a data series. Do not imply precision the source doesn't provide.
- Label currency (for example, USDT) wherever values could be ambiguous.
- Use a zero baseline when it materially affects interpretation; if a truncated axis is necessary, mark it clearly.
- Distinguish missing data from zero, and estimated values from verified or imported values.
- Don't animate financial values as slot-machine counters. On refresh, use a brief, stable state change and show the updated timestamp/source.
- Tables should preserve headers and column meaning on narrow screens; allow a dedicated detail view instead of forcing horizontal scrolling across the whole app.
- Every metric should have an explainable calculation basis and an identifiable source.

## 7. Forms, Buttons & Feedback

- **Primary button:** evergreen fill, high-contrast label, 48–52dp height, 12dp corner radius. One per screen or task region.
- **Secondary button:** tonal or outlined, same height, no competing accent.
- **Destructive action:** critical red, used only for deletion, reset, or other genuinely destructive actions.
- **Inputs:** persistent label above the field; supporting unit/helper text below; error text immediately beneath the relevant field. Never rely on placeholder text as the only label.
- **Focus:** clear evergreen outline or tonal state. Preserve keyboard navigation and visible focus.
- **Loading:** skeletons that match the content's final dimensions for initial content loads. For an explicit long-running sync, show a progress indicator with the current step and what the user can do next.
- **Empty state:** concise explanation plus one useful next action. No decorative illustration required for every screen.
- **Error state:** state what failed, whether local data is safe, and a recovery action. Do not hide errors in a toast alone.
- **Success state:** specific and truthful, such as “CSV imported: 214 rows accepted, 3 exact duplicates skipped.” Never claim a save, sync, or restore before it completes.
- **Dialogs:** state consequence in the title/body, name the affected period or data, and offer a clear cancel option.

## 8. Motion & Interaction

Motion should clarify state and preserve confidence in financial data.

- Use native Compose animation APIs and motion that respects system reduced-motion preferences where applicable.
- Typical transitions: 140–220ms for pressed/selected states; 180–280ms for sheets and screen transitions.
- Prefer opacity and small translation changes. No elastic overshoot on financial metrics, no perpetual shimmer after loading, no blinking danger states.
- Charts may animate into view once, but don't continuously pulse, bounce, or re-draw unchanged values.
- Press feedback should be immediate and tactile. Keep list updates stable to avoid rows jumping unexpectedly.
- Use haptics sparingly for meaningful confirmations only, never for ordinary value changes.
- Provide clear offline, loading, stale, success, and failure states for sync-dependent screens.

## 9. Accessibility & Trust

- Maintain strong text contrast and support Android font scaling without clipping or overlapping values.
- All controls need meaningful accessibility labels, state descriptions, and logical TalkBack traversal order.
- Charts need text summaries and accessible alternatives to color-coded series.
- Severity chips must contain words, not just colored dots.
- Respect screen readers, keyboard navigation where available, minimum 48dp touch targets, and system appearance settings.
- Never make the app resemble an exchange order-entry or trade-execution interface. It is a tracker, calculator, journal, and advisory tool.
- Clearly disclose local-only storage where relevant; distinguish it from optional Google Sheets sync.
- Do not expose API keys or secrets in logs, exported samples, screenshots, or mock data.
- Be candid about calculation limitations, historical coverage, sample sizes, and data quality.

## 10. Anti-Patterns (Never Do)

- No emojis as navigation icons or financial status indicators.
- No Inter, generic serif fonts, or inconsistent typefaces across screens.
- No pure black backgrounds or pure black text; use the defined charcoal/night tokens.
- No neon gradients, purple glow, glassmorphism, or decorative blur behind data.
- No rainbow metric cards or green/red fills across entire screens.
- No equal three-column card wall as the default dashboard layout.
- No fake or suspiciously precise metrics, fabricated account balances, or unlabelled sample data.
- No fake live tickers, blinking values, or claims of real-time exchange data when the screen is showing saved records.
- No hidden fees, silent calculation assumptions, or conflation of realized P&L with account balance.
- No treating missing/incomplete history as zero.
- No claims that a risk gate guarantees a safe trade or that historical scenarios predict future outcomes.
- No destructive action without a clear confirmation step.
- No placeholder-only labels, color-only status, tiny caveats, or unexplained risk scores.
- No AI copywriting clichés such as “Elevate,” “Seamless,” “Unleash,” or “Next-gen.”
- No generic filler text, decorative scroll prompts, or animations that distract from analysis.
- No horizontal overflow on phone screens and no clipped currency/price values.

## 11. Google Stitch Screen Generation Brief

Generate native-Android-style phone screens with consistent top bars, spacing, typography, semantic colors, and bottom navigation. Use realistic but explicitly labelled sample data. Prioritize these screens:

1. **Overview / Daily P&L:** balance and net P&L, selected period, readable trend chart, source-labelled daily ledger, and concise sync status.
2. **Trade Journal:** compact closed-trade ledger with long/short side, fees-aware net P&L, and a clean trade detail screen.
3. **Risk Calculator:** labelled form, visible assumptions, clear position-size result, and precise validation.
4. **Trade Risk Gate:** advisory checklist with transparent rule-by-rule results and saved-plan review.
5. **Analytics & Drawdown:** net performance trend, drawdown curve, win rate with sample size, and behavioral breakdown with data-quality caveats.
6. **Risk Scenario Lab:** scenario configuration, explicit simulation method, assumptions, and an annotated stress-test result.
7. **Binance Reconciliation:** year selector, source-separated totals, import preview, duplicate/invalid row summary, and protected replace/restore flow.
8. **Settings:** System / Light / Dark appearance, custom palettes, data management, and sync settings.

For each screen, generate a polished light theme and a matching dark theme. Keep the visual language calm and premium, with disciplined numeric alignment, restrained evergreen accent, explicit provenance labels, and realistic empty/loading/error states. Do not invent functionality beyond the product description. When the layout is dense, prefer clear hierarchy and dedicated detail screens over squeezing every metric into one viewport.

## 12. Implementation Handoff

- Implement with Kotlin and Jetpack Compose using semantic Material color and typography tokens that map to this document.
- Keep light and dark palettes aligned by semantic role rather than duplicating arbitrary colors per screen.
- Reuse components for KPI values, severity labels, source badges, chart headings, form fields, empty/error states, and confirmation dialogs.
- Keep financial calculations and data-source logic separate from visual components.
- Preserve the existing app navigation and feature behavior while applying the design system incrementally.
- Before shipping, verify light/dark themes, small phone widths, large font settings, TalkBack traversal, decimal/currency formatting, and long-running import/sync states.
