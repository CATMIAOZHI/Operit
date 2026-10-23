package com.ai.assistance.operit.ui.features.orbit

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.OrbitPreferences
import com.ai.assistance.operit.features.orbit.OrbitSoulRepository
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class OrbitIdentityDates(val soul: Long, val memory: Long, val user: Long)

internal suspend fun loadOrbitIdentityDates(context: Context, orbitId: String): OrbitIdentityDates =
    withContext(Dispatchers.IO) {
        val folder = OrbitPreferences.getInstance(context).orbitDir(orbitId)
        // The mirror's mtime can change after an interrupted write; use the actual Soul revision.
        val soulTime = try {
            OrbitSoulRepository(context, orbitId).snapshot().current.createdAt
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0L
        }
        OrbitIdentityDates(soulTime, File(folder, "memory/memory.md").lastModified(), File(folder, "user.md").lastModified())
    }

/** Pastel color clouds; their motion and color blending invalidate drawing only. */
@Composable
internal fun OrbitIdentityCard(
    title: String,
    icon: ImageVector,
    hue: Int,
    modifiedAt: Long?,
    animated: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val still = remember { mutableFloatStateOf(0f) }
    // Hidden pages and background apps have no infinite animation running.
    val drift = if (animated) {
        rememberInfiniteTransition(label = "identity-clouds").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7_000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "drift",
        )
    } else still
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(modifiedAt, locale) {
        modifiedAt?.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(it)) }
    }
    val dateLabel = date ?: if (modifiedAt == null) "—" else stringResource(R.string.orbit_identity_no_date)
    val dateDescription = stringResource(R.string.orbit_identity_modified, dateLabel)
    // The palette is always light, including in dark mode; keep labels consistently readable.
    val ink = Color(0xFF303846)
    Box(
        modifier.clip(RoundedCornerShape(28.dp))
            .drawWithCache {
                val base = Brush.linearGradient(listOf(
                    Color.hsl(hue.toFloat(), 0.60f, 0.91f),
                    Color.hsl(((hue + 35) % 360).toFloat(), 0.65f, 0.87f),
                ))
                val next = Brush.linearGradient(listOf(
                    Color.hsl(((hue + 80) % 360).toFloat(), 0.60f, 0.91f),
                    Color.hsl(((hue + 140) % 360).toFloat(), 0.65f, 0.87f),
                ))
                val radius = size.maxDimension * 0.95f
                val first = Brush.radialGradient(
                    listOf(Color.hsl(((hue + 65) % 360).toFloat(), 0.70f, 0.84f), Color.Transparent),
                    center = Offset.Zero, radius = radius,
                )
                val second = Brush.radialGradient(
                    listOf(Color.hsl(((hue + 315) % 360).toFloat(), 0.60f, 0.88f), Color.Transparent),
                    center = Offset.Zero, radius = radius,
                )
                onDrawBehind {
                    val p = drift.value
                    drawRect(base)
                    drawRect(next, alpha = p)
                    translate(size.width * (0.1f + 0.8f * p), size.height * (0.2f + 0.5f * p)) {
                        drawRect(first, Offset(-radius, -radius), Size(radius * 2, radius * 2))
                    }
                    translate(size.width * (0.95f - 0.6f * p), size.height * (0.9f - 0.75f * p)) {
                        drawRect(second, Offset(-radius, -radius), Size(radius * 2, radius * 2))
                    }
                }
            }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        // Minimum height, rather than a fixed aspect ratio: large fonts can grow without overlap.
        Column(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = if (compact) 150.dp else 148.dp)
                .padding(if (compact) 18.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = ink)
            Text(stringResource(R.string.orbit_profile_card_caution),
                style = MaterialTheme.typography.bodyMedium, color = ink)
            Spacer(Modifier.heightIn(min = if (compact) 28.dp else 24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(dateLabel, style = MaterialTheme.typography.labelMedium, color = ink,
                    modifier = Modifier.weight(1f).padding(end = 6.dp).semantics { contentDescription = dateDescription })
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(26.dp))
            }
        }
    }
}
