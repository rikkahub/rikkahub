---
name: find-hugeicons
description: Use this skill when the user wants to find or search for HugeIcons by keyword, or when you need to look up what icon names are available in the HugeIcons library before using them in Compose code.
---

# Find HugeIcons

Use this skill to search for available icons in the local HugeIcons Compose library.

## When to use

- The user asks to find an icon by keyword (e.g., "find a search icon", "what icon should I use for settings?")
- You need to verify an icon name before writing Compose code
- The user asks what icons are available for a certain concept

## Icon Library Location

Icons are extracted from the compiled dependency JAR in the Gradle cache. Do NOT look at the source project.

## Workflow

1. Find the HugeIcons JAR in the Gradle cache:

```bash
find ~/.gradle/caches -path "*hugeicons-compose*/jars/classes.jar" | head -1
```

2. Search for icons by keyword in the JAR:

```bash
jar -tf <jar_path> | grep -i "<keyword>" | grep "stroke/.*Kt.class" | sed 's|me/rerere/hugeicons/stroke/||;s|Kt.class||'
```

Or as a single pipeline:

```bash
jar -tf $(find ~/.gradle/caches -path "*hugeicons-compose*/jars/classes.jar" | head -1) | grep -i "<keyword>" | grep "stroke/.*Kt.class" | sed 's|me/rerere/hugeicons/stroke/||;s|Kt.class||'
```

3. Present a list of matching icon names to the user.

4. Show the correct Compose usage for each result.

## Compose Usage Pattern

```kotlin
// Import each icon used
import me.rerere.hugeicons.stroke.AiMagic

// Use in Compose
Icon(HugeIcons.AiMagic, contentDescription = null)
```

## Constraints

- Icon names are PascalCase (e.g., `GlobalSearch`, `Settings03`, `AiMagic`).
- All icons live under the `stroke` package.
- Do not guess icon names — always verify by searching the directory first.
- Present results clearly with both the property name and the import path.
