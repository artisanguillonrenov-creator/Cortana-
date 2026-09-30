package io.github.artisanguillonrenov.cortana.ui.components

import android.graphics.BlurMaskFilter
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType

/**
 * A Material Symbol of the design at the prototype's glyph size ("font: 300 25px" → 25 dp).
 * [contentDescription] is French; null for a decorative icon next to its text.
 */
@Composable
fun Symbol(@DrawableRes icon: Int, tint: Color, size: Dp, modifier: Modifier = Modifier, contentDescription: String? = null, rotation: Float = 0f) {
    Icon(
        painterResource(icon), contentDescription, tint = tint,
        modifier = modifier.size(size).let { if (rotation != 0f) it.rotate(rotation) else it },
    )
}

/**
 * CSS `box-shadow: x y blur spread color` for glows and drop shadows of the design (outer only).
 * The blur follows CSS (a Gaussian of sigma = blur / 2).
 */
fun Modifier.glow(color: Color, blur: Dp, corner: Dp = 0.dp, spread: Dp = 0.dp, offsetY: Dp = 0.dp, circle: Boolean = false): Modifier =
    drawBehind { drawGlow(color, blur, corner, spread, offsetY, circle) }

fun DrawScope.drawGlow(color: Color, blur: Dp, corner: Dp = 0.dp, spread: Dp = 0.dp, offsetY: Dp = 0.dp, circle: Boolean = false, topLeft: Offset = Offset.Zero, area: Size? = null) {
    if (color.alpha == 0f) return
    val sigma = blur.toPx() / 2f
    val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
    val s = spread.toPx(); val dy = offsetY.toPx()
    drawIntoCanvas { canvas ->
        val paint = Paint().apply { this.color = color }
        paint.asFrameworkPaint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            if (blur > 0.dp) maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        if (circle) canvas.drawCircle(Offset(size.width / 2, size.height / 2 + dy), size.minDimension / 2 + s, paint)
        else {
            val r = (corner.toPx() + s).coerceAtLeast(0f)
            val w = area?.width ?: size.width; val h = area?.height ?: size.height
            canvas.nativeCanvas.drawRoundRect(topLeft.x - s, topLeft.y - s + dy, topLeft.x + w + s, topLeft.y + h + s + dy, r, r, paint.asFrameworkPaint())
        }
    }
}

/** CSS `inset 0 0 blur color`: a glow along the inside edge of a rounded shape (logo, avatar). */
fun DrawScope.drawInnerGlow(color: Color, blur: Dp, circle: Boolean, corner: Dp = 0.dp, inset: Dp = 0.dp) {
    val sigma = blur.toPx() / 2f
    val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(0.1f)
    val w = blur.toPx()
    val i = inset.toPx()
    drawIntoCanvas { canvas ->
        val fp = Paint().asFrameworkPaint().apply {
            isAntiAlias = true; this.color = color.toArgb(); style = android.graphics.Paint.Style.STROKE; strokeWidth = w
            maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        }
        val nc = canvas.nativeCanvas
        nc.save()
        if (circle) {
            val path = android.graphics.Path().apply { addCircle(size.width / 2, size.height / 2, size.minDimension / 2 - i, android.graphics.Path.Direction.CW) }
            nc.clipPath(path)
            nc.drawCircle(size.width / 2, size.height / 2, size.minDimension / 2 - i + w / 2, fp)
        } else {
            val r = corner.toPx()
            val path = android.graphics.Path().apply { addRoundRect(i, i, size.width - i, size.height - i, r, r, android.graphics.Path.Direction.CW) }
            nc.clipPath(path)
            nc.drawRoundRect(i - w / 2, i - w / 2, size.width - i + w / 2, size.height - i + w / 2, r + w / 2, r + w / 2, fp)
        }
        nc.restore()
    }
}

/** Pressed or hovered (mouse, trackpad): the design's hover state on a touch tablet. */
@Composable
fun MutableInteractionSource.active(): Boolean {
    val pressed by collectIsPressedAsState()
    val hovered by collectIsHoveredAsState()
    return pressed || hovered
}

/** Click without ripple: every control draws its own pressed/hover color, as in the prototype. */
fun Modifier.tap(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    role: Role = Role.Button,
    label: String? = null,
    enabled: Boolean = true,
): Modifier = clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClickLabel = label, onClick = onClick)

/** `filter: brightness(1.08)` of the filled buttons: a light veil drawn over the (already clipped) content. */
fun Modifier.brighten(on: Boolean, veil: Color, amount: Float = 0.08f): Modifier =
    if (!on) this else drawWithContent { drawContent(); drawRect(veil.copy(alpha = amount)) }

/**
 * 48 dp touch target (README "Accessibilité") that overflows the control instead of growing the layout,
 * so the visual keeps the prototype's size. Place it on an overlay sized like the control
 * (`Modifier.matchParentSize()`), never inside a clipped parent.
 */
/**
 * CSS `line-height` below the font's own height (the design's `line-height:1` titles): the box takes
 * [height] and the glyphs stay centred in it, overflowing as in CSS. Compose never shrinks a single
 * line under the font's ascent + descent, so the box is set here.
 */
fun Modifier.lineBox(height: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
    val h = height.roundToPx()
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

fun Modifier.touchArea(min: Dp = 48.dp): Modifier = layout { measurable, constraints ->
    val m = min.roundToPx()
    val w = constraints.maxWidth; val h = constraints.maxHeight
    val cw = maxOf(w, m); val ch = maxOf(h, m)
    val p = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(cw, ch))
    layout(w, h) { p.place((w - cw) / 2, (h - ch) / 2) }
}

/**
 * A control whose visual [content] keeps its size while [overlay] (click, toggle, select and the
 * semantics) receives a touch target of at least 48 dp. The visual is hidden from accessibility: the
 * overlay carries the label and role.
 */
@Composable
fun TouchTarget(overlay: Modifier, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Box(modifier) {
        androidx.compose.foundation.layout.Box(Modifier.clearAndSetSemantics { }) { content() }
        androidx.compose.foundation.layout.Box(Modifier.matchParentSize().touchArea().then(overlay))
    }
}

/** Scale on press (`transform: scale(.985)` on the large STOP, `.96` on Send). */
fun Modifier.pressScale(pressed: Boolean, scale: Float, reduceMotion: Boolean): Modifier =
    if (!pressed || reduceMotion) this else graphicsLayer { scaleX = scale; scaleY = scale }

/** Entry of a new element (logs, approval card, queued message, artifacts): alpha 0→1 and Y 6 dp→0. */
@Composable
fun Modifier.enter(key: Any? = Unit, duration: Int = CortanaMotion.Enter): Modifier {
    val reduce = Cortana.reduceMotion
    val progress = remember(key) { Animatable(if (reduce) 1f else 0f) }
    LaunchedEffect(key, reduce) { if (!reduce) progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) else progress.snapTo(1f) }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 6.dp.toPx()
    }
}

/** Infinite animation that stops (at [rest]) when the owner reduces motion. */
@Composable
fun loop(period: Int, rest: Float = 0f, from: Float = 0f, to: Float = 1f, linear: Boolean = true, reverse: Boolean = false, delay: Int = 0): State<Float> {
    val reduce = Cortana.reduceMotion
    if (reduce) return remember { androidx.compose.runtime.mutableFloatStateOf(rest) }
    val t = rememberInfiniteTransition(label = "loop")
    return t.animateFloat(
        from, to,
        infiniteRepeatable(
            tween(period, delayMillis = 0, easing = if (linear) LinearEasing else FastOutSlowInEasing),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart,
            initialStartOffset = androidx.compose.animation.core.StartOffset(delay),
        ),
        label = "loop",
    )
}

/**
 * The Cortana ring (no image): circle, accent stroke, radial core, outer and inner glow, thin rim
 * (README "Logo Cortana"). [pulse] animates the glow (sidebar: 3.2 s; thread avatar: 2.4 s while running).
 */
@Composable
fun CortanaRing(size: Dp, stroke: Dp, outerGlow: Dp, innerGlow: Dp, modifier: Modifier = Modifier, pulsePeriod: Int? = null) {
    val c = Cortana.colors
    val phase by (if (pulsePeriod != null) loop(pulsePeriod, rest = 0f, linear = false, reverse = true) else remember { androidx.compose.runtime.mutableFloatStateOf(0f) })
    Canvas(modifier.size(size)) {
        // glow: 0 0 1px rim + outer glow; the pulse goes from the rest values to the "50%" keyframe.
        drawGlow(c.accent.copy(alpha = 0.6f + 0.35f * phase), outerGlow + (outerGlow * 0.45f * phase), circle = true)
        val r = this.size.minDimension / 2
        drawCircle(Brush.radialGradient(0.55f to c.logoCore, 1f to c.logoEdge, center = center, radius = r), r)
        drawInnerGlow(c.accent.copy(alpha = 0.7f + 0.2f * phase), innerGlow, circle = true, inset = stroke)
        drawCircle(c.accent, r - stroke.toPx() / 2, style = Stroke(stroke.toPx()))
        drawCircle(c.logoRim.copy(alpha = 0.3f + 0.2f * phase), r + 0.5.dp.toPx(), style = Stroke(1.dp.toPx()))
    }
}

/** Section overline: 11.5/600, UPPERCASE, tracking 0.1em. */
@Composable
fun Overline(text: String, color: Color = Cortana.colors.textMuted, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = CortanaType.Overline, color = color, modifier = modifier, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Status dot (8–10 dp) with an optional CSS glow. */
@Composable
fun Dot(color: Color, size: Dp, modifier: Modifier = Modifier, glow: Color? = null, glowBlur: Dp = 8.dp) {
    Canvas(modifier.size(size)) {
        if (glow != null) drawGlow(glow, glowBlur, circle = true)
        drawCircle(color)
    }
}

internal fun DrawScope.roundRect(color: Color, corner: Dp, topLeft: Offset = Offset.Zero, size: Size = this.size) =
    drawRoundRect(color, topLeft, size, CornerRadius(corner.toPx()))
