package com.example.expressive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * M3 EXPRESSIVE ANTI-PATTERNS
 *
 * This file demonstrates common mistakes when implementing M3 Expressive
 * and shows the correct patterns to use instead.
 */

// ============================================================================
// ANTI-PATTERN 1: Removing Text Labels
// Research shows usability drops without labels on critical actions
// ============================================================================

// ❌ BAD: Icon-only buttons for critical actions
@Composable
fun AntiPattern_IconOnlyButtons() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // These icon-only buttons remove essential context
        // Users must guess what each icon means
        IconButton(onClick = {}) {
            Icon(Icons.Default.ShoppingCart, contentDescription = "Add to cart")
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.Favorite, contentDescription = "Save")
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.Share, contentDescription = "Share")
        }
    }
}

// ✅ GOOD: Buttons with labels preserved
@Composable
fun CorrectPattern_LabelsPreserved() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Primary action with icon + label
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Add to cart")
        }
        // Secondary actions can use smaller buttons but still need labels
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {}) {
                Icon(Icons.Default.Favorite, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Save")
            }
            Button(onClick = {}) {
                Icon(Icons.Default.Share, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Share")
            }
        }
    }
}

// ============================================================================
// ANTI-PATTERN 2: Too Many Hero Moments
// Expressive should highlight 1-2 elements, not everything
// ============================================================================

// ❌ BAD: Every element is "expressive" - nothing stands out
@Composable
fun AntiPattern_TooManyHeroes() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // All buttons are large, bold, and colorful
        // When everything is a hero, nothing is
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
                // Replace with token-derived values from references/m3-*-specs-tokens.md where applicable.
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Primary Action", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
                // Replace with token-derived values from references/m3-*-specs-tokens.md where applicable.
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("Secondary Action", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
                // Replace with token-derived values from references/m3-*-specs-tokens.md where applicable.
                .height(56.dp),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
        ) {
            Text("Tertiary Action", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
        }
    }
}

// ✅ GOOD: Clear hierarchy with 1 hero moment
@Composable
fun CorrectPattern_SingleHero() {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ONE clear hero action - expressive styling
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth()
                // Replace with token-derived values from references/m3-*-specs-tokens.md where applicable.
                .height(56.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Text("Complete Purchase", fontWeight = FontWeight.Bold)
        }
        // Supporting actions - subtle, standard styling
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {},
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Save for Later")
            }
            Button(
                onClick = {},
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text("Continue Shopping")
            }
        }
    }
}

// ============================================================================
// ANTI-PATTERN 3: Hardcoded Color Values
// Breaks dynamic color and accessibility
// ============================================================================

// ❌ BAD: Hardcoded colors instead of semantic tokens
@Composable
fun AntiPattern_HardcodedColors() {
    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(
            // Hardcoded colors break dynamic color theming
            // May not meet contrast requirements
            containerColor = Color(0xFF6200EE), // Hardcoded purple
            contentColor = Color.White
        )
    ) {
        Text("Hardcoded Button")
    }
}

// ✅ GOOD: Semantic color tokens from MaterialTheme
@Composable
fun CorrectPattern_SemanticColors() {
    Button(
        onClick = {},
        colors = ButtonDefaults.buttonColors(
            // Uses semantic tokens that adapt to:
            // - Light/dark mode
            // - Dynamic color from wallpaper
            // - High contrast accessibility settings
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Text("Semantic Button")
    }
}

// ============================================================================
// ANTI-PATTERN 4: Insufficient Touch Targets
// Must be at least 48dp for accessibility
// ============================================================================

// ❌ BAD: Small touch targets
@Composable
fun AntiPattern_SmallTouchTargets() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // These 24dp icons are too small to tap reliably
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add", Modifier.size(16.dp), tint = Color.White)
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", Modifier.size(16.dp), tint = Color.White)
        }
    }
}

// ✅ GOOD: 48dp minimum touch targets
@Composable
fun CorrectPattern_AccessibleTouchTargets() {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // 48dp touch targets meet accessibility guidelines
        IconButton(
            onClick = {},
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add item")
        }
        IconButton(
            onClick = {},
            modifier = Modifier.size(48.dp)
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete item")
        }
    }
}

// ============================================================================
// ANTI-PATTERN 5: Missing Content Descriptions
// Screen readers cannot announce purpose without them
// ============================================================================

// ❌ BAD: No content descriptions for interactive elements
@Composable
fun AntiPattern_MissingContentDescriptions() {
    Row {
        IconButton(onClick = {}) {
            // contentDescription = null with no accompanying text
            // VoiceOver/TalkBack will say "Button" with no context
            Icon(Icons.Default.Edit, contentDescription = null)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.Delete, contentDescription = null)
        }
    }
}

// ✅ GOOD: Meaningful content descriptions
@Composable
fun CorrectPattern_AccessibleDescriptions() {
    Row {
        IconButton(onClick = {}) {
            // Clear, action-oriented descriptions
            Icon(Icons.Default.Edit, contentDescription = "Edit profile")
        }
        IconButton(onClick = {}) {
            Icon(Icons.Default.Delete, contentDescription = "Delete account")
        }
    }
}

// ============================================================================
// ANTI-PATTERN 6: No Reduced Motion Alternative
// Users with vestibular disorders need motion alternatives
// ============================================================================

/*
 * ❌ BAD: Expressive animations with no fallback
 *
 * // Only provides animated version
 * AnimatedVisibility(
 *     visible = expanded,
 *     enter = expandVertically() + fadeIn(),
 *     exit = shrinkVertically() + fadeOut()
 * ) { content() }
 *
 * ✅ GOOD: Respect system animation scale
 *
 * val reducedMotion = LocalReducedMotion.current
 *
 * if (reducedMotion) {
 *     // Instant transition, no animation
 *     if (expanded) content()
 * } else {
 *     // Expressive animated transition
 *     AnimatedVisibility(
 *         visible = expanded,
 *         enter = expandVertically() + fadeIn(),
 *         exit = shrinkVertically() + fadeOut()
 *     ) { content() }
 * }
 */

// ============================================================================
// SUMMARY TABLE
// ============================================================================
/*
 * | Anti-Pattern               | Problem                        | Fix                              |
 * |----------------------------|--------------------------------|----------------------------------|
 * | Icon-only buttons          | Usability drops                | Always include text labels       |
 * | Too many hero moments      | Nothing stands out             | Max 1-2 expressive per screen    |
 * | Hardcoded colors           | Breaks theming/accessibility   | Use semantic tokens              |
 * | Small touch targets        | Hard to tap, fails WCAG        | Minimum 48dp                     |
 * | Missing descriptions       | Screen readers can't announce  | Add contentDescription           |
 * | No motion fallback         | Triggers vestibular issues     | Check system animation scale     |
 */
