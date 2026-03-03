---
name: material-3-expressive
description: Android UI/UX design or review with Material 3 Expressive (M3 Expressive / Material You Expressive). Use when selecting expressive component tokens, defining expressive hierarchy, motion, color, shape, typography, or reviewing Android UI against expressive guidance. Not for non-Android platforms, non-UI Android tasks, or implementation-only requests without design decisions.
---

# Material 3 Expressive (Android UI/UX)

## Overview

Design and review Android UI/UX using Material 3 Expressive. Prioritize component-token requests for dialogs, sheets,
modals, toolbars, and buttons, with guidance plus token references by default.

## Intake Questions

- Target device class (phone, tablet, foldable, Wear OS)
- Window size class (Compact, Medium, Expanded)
- Brand constraints and dynamic color usage
- Primary action(s) and desired hero moments (1-2 max)
- Motion constraints and reduced-motion expectations
- Accessibility targets (contrast, touch target)

## Default Assumptions

- Intensity level: Foundational
- Window size class: Compact
- Dynamic color: enabled
- Standard navigation patterns preserved

## Quick Workflow

1. Confirm intensity level + hero moments
2. Confirm device class + window size class
3. Load Tier 1 index and output template
4. Load component overview + token spec files
5. If theming needed, load Tier 3 foundation tokens
6. Output: guidance + token refs (see template)

## Default Output Format (Guidance + Token Refs)

Use `references/m3-component-token-output-template.md` verbatim as the base output shell.

- Context and intent (1-2 lines)
- Intensity level + hero moments (count + location)
- Window size class + device class
- Component + variant
- Token references: file paths + token groups to use (quote minimal key values only if needed)
- Compose mapping reference (optional): `references/compose-mapping.md`
- Behavior or interaction constraints (from overview)
- Reduced-motion fallback note
- Accessibility and performance checks
- Optional: Compose mapping notes

## Expressive Tactics

| Lever          | Application                                  |
|----------------|----------------------------------------------|
| Shape contrast | Bold corners on primary, subtle on secondary |
| Rich color     | Primary/secondary containers for emphasis    |
| Type hierarchy | Size + weight variation                      |
| Motion         | Shape morph on press/select                  |

**Constraint:** Max 1-2 hero moments per flow

## Expressive Intensity Levels

- **Foundational:** Clarity and familiarity first
- **Excellent:** Stronger color, type, and shape while preserving patterns
- **Transformative:** Bold layouts and motion with strict usability and accessibility

## Common Mistakes

| Mistake                                 | Fix                                                                                                   |
|-----------------------------------------|-------------------------------------------------------------------------------------------------------|
| Removing text labels for "cleaner" look | Keep labels; usability drops without them                                                             |
| Too many hero moments per screen        | Limit to 1-2 per flow                                                                                 |
| Breaking navigation patterns            | Preserve standard nav behaviors                                                                       |
| No reduced-motion fallback              | Honor system animation scale; provide reduced-motion or instant transitions when animation scale is 0 |
| Hardcoding token values                 | Use semantic tokens (`md.sys.color.*`)                                                                |
| Ignoring window size classes            | Test Compact, Medium, Expanded                                                                        |

## Android-Specific Guidance

- Preserve navigation and component behaviors
- Keep touch targets generous and platform-consistent
- Honor system animation scale; provide reduced-motion alternatives or instant transitions when animation scale is 0
- Apply window size classes for tablets and foldables

## Review Checklist

### Hierarchy

- [ ] Primary action identifiable within 2 seconds
- [ ] Max 1-2 hero moments per screen
- [ ] Labels preserved on all interactive elements

### Accessibility

- [ ] Color contrast: 4.5:1 text, 3:1 non-text
- [ ] Touch targets: 48dp minimum
- [ ] Motion: reduced-motion alternative provided
- [ ] Screen reader: content descriptions present

### Expressive Compliance

- [ ] Intensity level consistent across feature
- [ ] Standard patterns preserved
- [ ] Dynamic color tokens used
- [ ] Window size classes tested

## Reference Tiers (Load in Order of Need)

### Tier 1: Always Load First

| Need                   | File                                               |
|------------------------|----------------------------------------------------|
| Component token lookup | `references/m3-expressive-specs-tokens-index.md`   |
| Output format template | `references/m3-component-token-output-template.md` |
| New/updated components | `references/m3-expressive-components.md`           |

### Tier 2: Component-Specific (Load When Requested)

| Component           | Overview                    | Tokens                                                                |
|---------------------|-----------------------------|-----------------------------------------------------------------------|
| Buttons             | `m3-buttons.md`             | `m3-buttons-specs-tokens.md`                                          |
| Button Groups       | `m3-button-groups.md`       | `m3-button-groups-specs-tokens.md`                                    |
| Dialogs             | `m3-dialogs.md`             | `m3-dialogs-specs-tokens.md`                                          |
| Sheets              | `m3-sheets.md`              | `m3-bottom-sheets-specs-tokens.md` / `m3-side-sheets-specs-tokens.md` |
| Toolbars            | `m3-toolbars.md`            | `m3-toolbars-specs-tokens.md`                                         |
| FABs                | `m3-fabs.md`                | `m3-fabs-specs-tokens.md`                                             |
| Extended FAB        | `m3-extended-fab.md`        | `m3-extended-fab-specs-tokens.md`                                     |
| FAB Menu            | `m3-fab-menu.md`            | `m3-fab-menu-specs-tokens.md`                                         |
| Icon Buttons        | `m3-icon-buttons.md`        | `m3-icon-buttons-specs-tokens.md`                                     |
| Split Button        | `m3-split-button.md`        | `m3-split-button-specs-tokens.md`                                     |
| Navigation Bar      | `m3-navigation-bar.md`      | `m3-navigation-bar-specs-tokens.md`                                   |
| Navigation Rail     | `m3-navigation-rail.md`     | `m3-navigation-rail-specs-tokens.md`                                  |
| App Bars            | `m3-app-bars.md`            | `m3-app-bars-specs-tokens.md`                                         |
| Carousel            | `m3-carousel.md`            | `m3-carousel-specs-tokens.md`                                         |
| Progress Indicators | `m3-progress-indicators.md` | `m3-progress-indicators-specs-tokens.md`                              |
| Loading Indicator   | `m3-loading-indicator.md`   | `m3-loading-indicator-specs-tokens.md`                                |

### Tier 3: Foundation Tokens (Load for Theming)

| Foundation   | File                                                                                                                     |
|--------------|--------------------------------------------------------------------------------------------------------------------------|
| Color system | `m3-color-system.md`, `m3-color-foundation-tokens.md`                                                                    |
| Typography   | `m3-typography.md`, `m3-typography-foundation-tokens.md`, `m3-typography-fonts.md`, `m3-typography-type-scale-tokens.md` |
| Shape        | `m3-shape.md`, `m3-shape-foundation-tokens.md`, `m3-shape-corner-radius-scale.md`, `m3-shape-morph.md`                   |
| Motion       | `m3-motion-physics.md`, `m3-motion-foundation-tokens.md`, `m3-motion-specs.md`                                           |
| Elevation    | `m3-elevation.md`, `m3-elevation-specs-tokens.md`                                                                        |
| State        | `m3-state-foundation-tokens.md`                                                                                          |

### Tier 4: Evidence & Research (Load for Justification)

| Resource              | File                                     |
|-----------------------|------------------------------------------|
| Research findings     | `references/expressive-research.md`      |
| Testing guidance      | `references/m3-testing-material-3.md`    |
| Expressive blog       | `references/m3-expressive-blog.md`       |
| Expressive guidelines | `references/m3-expressive-guidelines.md` |
| UX article            | `references/medium-ux-article.md`        |

### Tier 5: Wear OS (Load Only for Wearables)

| Resource             | File                                            |
|----------------------|-------------------------------------------------|
| Benefits             | `references/wear-expressive-benefits.md`        |
| Levels of expression | `references/wear-levels-of-expression.md`       |
| Design language      | `references/wear-expressive-design-language.md` |
| Blog                 | `references/wear-expressive-blog.md`            |
| Compose Material3    | `references/wear-compose-material3.md`          |

### Supporting Resources

| Resource              | File                                  |
|-----------------------|---------------------------------------|
| Android UI design hub | `references/android-ui-design-hub.md` |
| Expressive catalog    | `references/expressive-catalog.md`    |
| Compose mapping       | `references/compose-mapping.md`       |

## Maintenance

- Refresh references with `skills/material-3-expressive/scripts/update_m3_expressive_refs.py`.
- Requires Playwright and Chromium (`.venv/bin/python -m playwright install chromium` if needed).
- CI runs weekly; local runs are for urgent changes.

## Search Tips

- Find a token prefix: `rg "md.comp.button" skills/material-3-expressive/references/m3-buttons-specs-tokens.md`
- Find navigation tokens: `rg "md.comp.navigation" skills/material-3-expressive/references/m3-.*navigation.*.md`

## Examples

- Use `assets/examples/ui/ExpressiveHomeScreen.kt` as a Compose starting point
- Use `assets/examples/ui/ExpressiveButtonComparison.kt` to see standard vs expressive button differences
- Use `assets/examples/ui/ExpressiveAntiPatterns.kt` to learn common mistakes to avoid
- Use `assets/examples/ux/expressive-ux-brief-template.md` to draft an expressive UX brief
- Examples are illustrative; adapt sizes/colors to expressive tokens and project constraints.
