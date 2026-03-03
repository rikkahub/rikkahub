package com.example.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * COMPARISON: Standard M3 Button vs M3 Expressive Button
 *
 * This example demonstrates the key differences between a standard Material 3
 * button and one enhanced with M3 Expressive principles.
 */

// ============================================================================
// STANDARD M3 BUTTON
// Uses default Material 3 styling with no expressive enhancements
// ============================================================================
@Composable
fun StandardM3Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
        // Uses default shape: RoundedCornerShape(100) - fully rounded ends
        // Uses default colors: primary container
        // Uses default typography: labelLarge
        // No icon, minimal visual hierarchy
    ) {
        Text(text = "Add to cart")
    }
}

// ============================================================================
// M3 EXPRESSIVE BUTTON
// Enhanced with expressive hierarchy, shape contrast, and emotional intent
// ============================================================================
@Composable
fun ExpressiveM3Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // EXPRESSIVE: Generous touch target (56dp vs default 40dp)
            // Improves accessibility and creates visual prominence
            // Replace with token-derived values from references/m3-*-specs-tokens.md where applicable.
            .height(56.dp),
        // EXPRESSIVE: Shape contrast using XL corner radius (18dp)
        // Creates visual interest while maintaining brand recognition
        // See: m3-shape-corner-radius-scale.md for scale values
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(
            // Uses primary color from dynamic color system
            // EXPRESSIVE: Rich color emphasis for primary actions
            containerColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // EXPRESSIVE: Leading icon reinforces action intent
            // Icon + label combo improves scannability
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null // Decorative, text provides meaning
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Add to cart",
                // EXPRESSIVE: Type hierarchy with increased weight
                // titleMedium + Bold creates emphasis without size increase
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// ============================================================================
// SIDE-BY-SIDE COMPARISON
// Shows both buttons together to highlight the differences
// ============================================================================
@Composable
fun ButtonComparisonDemo() {
    Column(
        modifier = Modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Standard button - functional but not expressive
        Column {
            Text(
                text = "Standard M3 Button",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            StandardM3Button(onClick = {})
        }

        // Expressive button - enhanced visual hierarchy
        Column {
            Text(
                text = "M3 Expressive Button",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            ExpressiveM3Button(onClick = {})
        }
    }
}

// ============================================================================
// KEY DIFFERENCES SUMMARY
// ============================================================================
/*
 * | Aspect          | Standard M3           | M3 Expressive                    |
 * |-----------------|-----------------------|----------------------------------|
 * | Shape           | Fully rounded (100dp) | Rounded XL corners (18dp)        |
 * | Height          | 40dp default          | 56dp generous touch target       |
 * | Typography      | labelLarge            | titleMedium + Bold               |
 * | Icon            | None                  | Leading icon reinforces intent   |
 * | Width           | Hug content           | Full width for primary action    |
 * | Visual weight   | Medium                | High - clear hero element        |
 *
 * WHEN TO USE EXPRESSIVE:
 * - Primary action in a flow (max 1-2 per screen)
 * - Conversion-critical buttons (checkout, subscribe, confirm)
 * - When emotional engagement matters
 *
 * WHEN TO USE STANDARD:
 * - Secondary actions
 * - Dense UIs with multiple buttons
 * - Settings or utility screens
 */
