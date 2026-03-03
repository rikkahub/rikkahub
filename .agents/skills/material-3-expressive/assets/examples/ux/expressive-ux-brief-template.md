# Expressive UX Brief Template

## Template

**Product or feature:**

**Primary audience:**

**Primary tasks:**

**Emotional intent:**

**Expressive intensity:**

**Constraints:**

**Platform:**

**Success criteria:**

### Hierarchy Plan

- **Primary action:**
- **Secondary actions:**
- **Supporting information:**

### Expressive Levers

- **Color:**
- **Shape:**
- **Size and typography:**
- **Motion:**
- **Containment:**

### Accessibility Checks

- **Contrast targets:**
- **Touch target sizes:**
- **Motion sensitivity:**

### Implementation Notes

- **Compose components to use:**
- **Tokens or theme adjustments:**

---

## Filled Example: Travel App Booking Screen

**Product or feature:** Flight booking confirmation screen for Wanderlust travel app

**Primary audience:** Leisure travelers aged 25-45 who book 2-4 trips per year; mix of mobile-first and desktop
switchers

**Primary tasks:**

1. Review flight details and price
2. Confirm booking with payment
3. Add trip to calendar/wallet

**Emotional intent:** Excitement and confidence—users should feel the thrill of an upcoming adventure while trusting
that their booking is secure

**Expressive intensity:** Excellent (stronger color, type, and shape while preserving familiar booking patterns)

**Constraints:**

- Must load within 2 seconds on 3G
- Payment form requires PCI compliance positioning
- Cannot obscure price or flight details
- Must work in offline mode (show cached data with sync indicator)

**Platform:** Android (Jetpack Compose), supporting Compact to Expanded window size classes

**Success criteria:**

- Booking completion rate ≥ 85%
- Time to confirm < 30 seconds
- Accessibility audit passes WCAG 2.1 AA
- User satisfaction score ≥ 4.5/5 for "ease of booking"

### Hierarchy Plan

- **Primary action:** "Confirm & Pay" button—full-width, 56dp height, primary container color, XL corner radius (18dp),
  bold titleMedium text
- **Secondary actions:** "Add to Calendar" and "Save to Wallet" as outlined buttons below primary; "Edit Details" as
  text button in header
- **Supporting information:** Flight itinerary card (destination, dates, passengers), price breakdown expandable
  section, terms link at bottom

### Expressive Levers

- **Color:** Primary container for CTA, tertiary container for destination highlight chip, surface variants for
  itinerary card; dynamic color enabled
- **Shape:** XL corners (18dp) on primary button and itinerary card; medium corners (12dp) on secondary buttons and
  chips
- **Size and typography:** headlineMedium + ExtraBold for destination city; titleLarge for price; bodyLarge for details;
  generous 24dp padding
- **Motion:** Shape morph on button press (rounded → slightly more rounded); subtle parallax on destination image;
  expandable price breakdown with spring animation; all motion respects system animation scale (Remove animations)
- **Containment:** Elevated card (2dp) for flight details creates clear grouping; price section uses surface color
  change instead of border

### Accessibility Checks

- **Contrast targets:** 4.5:1 for all text; 3:1 for button containers against background; verified with both light and
  dark themes
- **Touch target sizes:** All interactive elements ≥ 48dp; "Confirm & Pay" is 56dp for emphasis
- **Motion sensitivity:** Spring animations replaced with instant transitions when `LocalReducedMotion.current` is true;
  no auto-playing animations

### Implementation Notes

- **Compose components to use:**
  - `ElevatedCard` with `RoundedCornerShape(18.dp)` for itinerary
  - `Button` with custom `ButtonDefaults.buttonColors` for primary CTA
  - `OutlinedButton` for secondary actions
  - `AssistChip` with `tertiaryContainer` for destination tag
  - `AnimatedVisibility` for price breakdown (with reduced motion check)

- **Tokens or theme adjustments:**
  - Shape: Override `MaterialTheme.shapes.large` to 18dp for this screen
  - Color: Use `primaryContainer` / `onPrimaryContainer` for CTA
  - Typography: Apply `fontWeight = FontWeight.ExtraBold` to destination text
  - Motion: Use `md.sys.motion.duration.medium2` (300ms) for expand/collapse
  - Reference files: `m3-buttons-specs-tokens.md`, `m3-shape-corner-radius-scale.md`, `m3-motion-foundation-tokens.md`
