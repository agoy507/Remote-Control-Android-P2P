package com.agoy507.mdremote.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agoy507.mdremote.model.RemoteMouseButton

/**
 * Optional floating PC mouse buttons + vertical scroll strip (inspired by RealVNC Viewer's
 * mouse mode overlay), allowing separate thumb hold-to-drag and one-tap middle/right click.
 */
@Composable
fun MouseOverlayButtons(
    onMouseDown: (RemoteMouseButton) -> Unit,
    onMouseUp: (RemoteMouseButton) -> Unit,
    onMouseClick: (RemoteMouseButton) -> Unit,
    onScroll: (deltaX: Float, deltaY: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var leftPressed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .navigationBarsPadding()
            .padding(12.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Right-aligned vertical scroll rail
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier
                .width(48.dp)
                .height(140.dp)
                .pointerInput(Unit) {
                    var accY = 0f
                    val stepPx = 28f
                    detectVerticalDragGestures(
                        onDragStart = { accY = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            accY += dragAmount
                            while (accY >= stepPx) {
                                onScroll(0f, 1f)
                                accY -= stepPx
                            }
                            while (accY <= -stepPx) {
                                onScroll(0f, -1f)
                                accY += stepPx
                            }
                        },
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowUp,
                    contentDescription = "Gulir atas",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onScroll(0f, 1f) },
                )
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = "Strip gulir vertikal",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "Gulir bawah",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable { onScroll(0f, -1f) },
                )
            }
        }

        // Bottom mouse buttons row: Left (hold-capable) | Middle | Right
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (leftPressed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .width(84.dp)
                        .height(48.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                leftPressed = true
                                onMouseDown(RemoteMouseButton.LEFT)
                                try {
                                    waitForUpOrCancellation()
                                } finally {
                                    leftPressed = false
                                    onMouseUp(RemoteMouseButton.LEFT)
                                }
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Kiri (L)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (leftPressed) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .width(68.dp)
                        .height(48.dp)
                        .clickable { onMouseClick(RemoteMouseButton.MIDDLE) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Tengah",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .width(84.dp)
                        .height(48.dp)
                        .clickable { onMouseClick(RemoteMouseButton.RIGHT) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "Kanan (R)",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
