package com.phoneintegration.app.ui.deals

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun DealShimmerPlaceholder() {
    val alphaAnim = rememberInfiniteTransition(label = "shimmer")
    val alpha = alphaAnim.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerAlpha"
    )

    val shimmerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
    ) {
        // Image placeholder with badge placeholders
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .alpha(alpha.value)
                    .background(shimmerColor)
            )

            // Discount badge placeholder
            Box(
                modifier = Modifier
                    .padding(8.dp)
                    .width(70.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .alpha(alpha.value)
                    .background(shimmerColor)
            )
        }

        // Content section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Title placeholder (2 lines)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(alpha.value)
                    .background(shimmerColor)
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .alpha(alpha.value)
                    .background(shimmerColor)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Price and rating row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Price placeholder
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .alpha(alpha.value)
                        .background(shimmerColor)
                )

                // Rating placeholder
                Box(
                    modifier = Modifier
                        .width(50.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .alpha(alpha.value)
                        .background(shimmerColor)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Button placeholder
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .alpha(alpha.value)
                    .background(shimmerColor)
            )
        }
    }
}
