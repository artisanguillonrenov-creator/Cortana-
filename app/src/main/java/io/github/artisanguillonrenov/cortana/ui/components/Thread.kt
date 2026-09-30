package io.github.artisanguillonrenov.cortana.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaDimens
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaPalette
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

// ------------------------------------------------------------------ owner messages

/** The owner's message: right-aligned, avatar, text 14.5/24, time and "delivered" check (spec 3.1). */
@Composable
fun UserBubble(time: String, modifier: Modifier = Modifier, delivered: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(
            Modifier.widthIn(max = CortanaDimens.UserBubbleMaxWidth).clip(CortanaShapes.Bubble).background(c.userBubble)
                .border(1.dp, c.userBubbleBorder, CortanaShapes.Bubble).topHighlight(c.userBubbleHighlight.copy(alpha = 0.08f), 18.dp)
                .padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(c.avatarBackground).semantics { heading(); contentDescription = "Vous" }, contentAlignment = Alignment.BottomCenter) {
                Symbol(Symbols.PersonFill, c.avatarIcon, 28.dp, Modifier.offset(y = 5.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                content()
                Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(time, style = CortanaType.Caption.copy(fontSize = 11.5.sp), color = c.userTime)
                    if (delivered) Symbol(Symbols.DoneAll, c.accentIcon, 16.dp, contentDescription = "Reçu")
                }
            }
        }
    }
}

@Composable
fun BubbleText(text: String) = Text(text, style = CortanaType.Bubble, color = Cortana.colors.userText)

/** A message waiting for the running task (spec 2.6); "Retirer" until it leaves. */
@Composable
fun QueuedBubble(text: String, delivered: Boolean, onRemove: (() -> Unit)?, modifier: Modifier = Modifier, label: String? = null) {
    val c = Cortana.colors
    Column(modifier.fillMaxWidth().enter(text), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.widthIn(max = CortanaDimens.QueuedBubbleMaxWidth).clip(CortanaShapes.QueuedBubble).background(c.userBubble)
                .border(1.dp, c.userBubbleBorder, CortanaShapes.QueuedBubble).padding(horizontal = 14.dp, vertical = 10.dp),
        ) { Text(text, style = CortanaType.Bubble.copy(lineHeight = 22.sp), color = c.userText) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Symbol(if (delivered) Symbols.DoneAll else Symbols.Schedule, if (delivered) c.accentIcon else c.textTertiary, 15.dp)
            Text(label ?: if (delivered) "Transmis à Cortana" else "En file · envoyé après la tâche en cours", style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textTertiary)
            if (!delivered && onRemove != null) {
                val src = remember { MutableInteractionSource() }
                TouchTarget(Modifier.tap(src, onRemove).semantics { contentDescription = "Retirer ce message de la file" }, Modifier.padding(start = 6.dp)) {
                    Text("Retirer", style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.accentLink)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Cortana's messages

/** Cortana's turn: 44 dp ring avatar (pulsing only while the task runs), name and time, content 640 dp max. */
@Composable
fun AssistantMessage(time: String, running: Boolean, modifier: Modifier = Modifier, name: String = "Cortana", content: @Composable ColumnScope.() -> Unit) {
    val c = Cortana.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        CortanaRing(CortanaDimens.AvatarRing, 3.5.dp, 14.dp, 9.dp, pulsePeriod = if (running) CortanaMotion.AvatarGlowPeriod else null)
        Column(Modifier.weight(1f, fill = false).widthIn(max = CortanaDimens.AssistantMaxWidth)) {
            Row(Modifier.padding(top = 2.dp).semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(name, style = CortanaType.Control.copy(fontWeight = FontWeight.SemiBold), color = c.accentText)
                Text(time, style = CortanaType.Caption.copy(fontSize = 12.sp), color = c.textMuted)
            }
            content()
        }
    }
}

@Composable
fun BodyText(text: String, modifier: Modifier = Modifier) = Text(text, style = CortanaType.Body, color = Cortana.colors.textBody, modifier = modifier)

/** Live status line of Cortana, with the three dots while the task runs. */
@Composable
fun LiveStatusLine(text: String, running: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text, style = CortanaType.Body, color = Cortana.colors.textBody, modifier = Modifier.weight(1f, fill = false))
        if (running) StreamingDots()
    }
}

// ------------------------------------------------------------------ plan

/**
 * "Plan d'exécution" (spec 9.5: operational steps, never private reasoning). [flashKey] changes when
 * "Voir le plan" asks the card to flash its border for 1.4 s.
 */
@Composable
fun PlanCard(steps: List<PlanStep>, statusLabel: String, eta: String, modifier: Modifier = Modifier, flashKey: Int = 0, onMenu: (() -> Unit)? = null) {
    val c = Cortana.colors
    val reduce = Cortana.reduceMotion
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashKey) {
        if (flashKey == 0) return@LaunchedEffect
        if (reduce) { flash.snapTo(1f); kotlinx.coroutines.delay(CortanaMotion.PlanFlash.toLong()); flash.snapTo(0f); return@LaunchedEffect }
        flash.snapTo(0f)
        flash.animateTo(1f, tween((CortanaMotion.PlanFlash * 0.15f).toInt(), easing = LinearEasing))
        flash.animateTo(0f, tween((CortanaMotion.PlanFlash * 0.85f).toInt(), easing = androidx.compose.animation.core.LinearOutSlowInEasing))
    }
    Box(modifier.padding(top = 12.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(CortanaShapes.Lg).background(c.planBackground).border(1.dp, c.planBorder, CortanaShapes.Lg)
                .padding(start = 18.dp, top = 9.dp, end = 16.dp, bottom = 12.dp),
        ) {
            Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Symbol(Symbols.ListAlt, c.planIcon, 22.dp)
                Text("Plan d’exécution", style = CortanaType.SectionTitle, color = c.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Symbol(Symbols.Schedule, c.textSecondary, 17.dp)
                    Text(statusLabel, style = CortanaType.Secondary, color = c.textSecondary)
                }
                if (eta.isNotEmpty()) Text(eta, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.textSecondary, modifier = Modifier.padding(start = 0.dp))
                if (onMenu != null) {
                    val src = remember { MutableInteractionSource() }
                    TouchTarget(Modifier.tap(src, onMenu).semantics { contentDescription = "Actions du plan" }, Modifier.padding(start = 4.dp)) { Symbol(Symbols.MoreVert, c.planKebab, 22.dp) }
                }
            }
            Column(Modifier.padding(top = 6.dp)) { steps.forEachIndexed { i, s -> StepRow(i + 1, s) } }
        }
        if (flash.value > 0f) Canvas(Modifier.matchParentSize()) {
            val a = flash.value
            drawGlow(c.accentBar.copy(alpha = 0.18f * a), 0.dp, 14.dp, spread = 4.dp)
            drawRoundRect(c.accentBar.copy(alpha = a), cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()), style = Stroke(1.5.dp.toPx()))
        }
    }
}

@Composable
fun StepRow(number: Int, step: PlanStep, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val active = step.state == StepState.Running || step.state == StepState.Waiting
    val state = when (step.state) { StepState.Done -> "faite"; StepState.Running -> "en cours"; StepState.Waiting -> "en attente"; StepState.Upcoming -> "à venir"; StepState.Failed -> "échouée" }
    Row(
        modifier.height(CortanaDimens.PlanStepHeight).semantics(mergeDescendants = true) { contentDescription = "Étape $number, $state : ${step.title}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) { StepIcon(step.state) }
        Text("$number. ${step.title}", style = CortanaType.Step, color = if (active) c.navTextActive else c.stepText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The step's state icon: filled check, spinner (0.9 s), amber ring, upcoming ring. */
@Composable
fun StepIcon(state: StepState, size: Dp = 17.dp) {
    val c = Cortana.colors
    when (state) {
        StepState.Done -> Symbol(Symbols.CheckCircleFill, c.success, 21.dp)
        StepState.Running -> {
            val angle by loop(CortanaMotion.SpinnerPeriod, rest = 0f, to = 360f)
            Canvas(Modifier.size(size)) {
                val sw = 2.dp.toPx()
                drawGlow(c.accentBar.copy(alpha = 0.75f), 8.dp, circle = true)
                drawInnerGlow(c.accentBar.copy(alpha = 0.5f), 4.dp, circle = true)
                drawCircle(c.accentBar, radius = this.size.minDimension / 2 - sw / 2, style = Stroke(sw))
                rotate(angle) {
                    // CSS arc: the top quarter of the border (border-top-color) of the same ring.
                    drawArc(c.spinnerArc, 225f, 90f, false, topLeft = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2),
                        size = androidx.compose.ui.geometry.Size(this.size.width - sw, this.size.height - sw), style = Stroke(sw))
                }
            }
        }
        StepState.Waiting -> Canvas(Modifier.size(size)) {
            val sw = 2.dp.toPx()
            drawGlow(c.warning.copy(alpha = 0.5f), 8.dp, circle = true)
            drawCircle(c.warning, radius = this.size.minDimension / 2 - sw / 2, style = Stroke(sw))
        }
        StepState.Failed -> Symbol(Symbols.Block, c.dangerText, 20.dp)
        StepState.Upcoming -> Canvas(Modifier.size(size)) {
            val sw = 1.5.dp.toPx()
            drawCircle(c.stepUpcoming, radius = this.size.minDimension / 2 - sw / 2, style = Stroke(sw))
        }
    }
}

// ------------------------------------------------------------------ code

private val kotlinTokens = Regex("""(@\w+)|("[^"]*")|\b(abstract|class|fun|companion|object|private|var|val|return|synchronized)\b|\b(null|this|it|\d+)\b|\b([A-Z][a-z]\w*)\b""")

/** Syntax highlighting of the prototype (keywords, types, annotations, strings, literals); ALL_CAPS stay plain. */
fun highlightKotlin(line: String, c: CortanaPalette): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in kotlinTokens.findAll(line)) {
        if (m.range.first > last) withStyle(SpanStyle(color = c.syntaxPlain)) { append(line.substring(last, m.range.first)) }
        val color = when {
            m.groups[1] != null -> c.syntaxAnnotation
            m.groups[2] != null -> c.syntaxString
            m.groups[3] != null -> c.syntaxKeyword
            m.groups[4] != null -> c.syntaxLiteral
            else -> c.syntaxType
        }
        withStyle(SpanStyle(color = color)) { append(m.value) }
        last = m.range.last + 1
    }
    if (last < line.length) withStyle(SpanStyle(color = c.syntaxPlain)) { append(line.substring(last)) }
}

/**
 * Code block: header (tile, file name, path, language, Copier, Agrandir), numbered lines, 232 dp high
 * (360 dp expanded), scrollable both ways. [highlight] colors Kotlin-like languages.
 */
@Composable
fun CodeBlock(
    code: String,
    modifier: Modifier = Modifier,
    fileName: String? = null,
    path: String? = null,
    language: String? = null,
    expanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
    copied: Boolean = false,
    onCopy: (() -> Unit)? = null,
    highlight: Boolean = language?.lowercase() in setOf("kotlin", "kt", "kts", "java"),
    lineNumbers: Boolean = true,
) {
    val c = Cortana.colors
    val lines = remember(code) { code.trimEnd('\n').lines() }
    val rendered = remember(code, highlight, c) { lines.map { if (highlight) highlightKotlin(it, c) else AnnotatedString(it) } }
    val shape = CortanaShapes.Md
    Column(modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).background(c.sunken).border(1.dp, c.planBorder, shape)) {
        Row(
            Modifier.fillMaxWidth().height(40.dp).background(c.codeHeader).drawWithContent {
                drawContent(); drawLine(c.codeHeaderDivider, androidx.compose.ui.geometry.Offset(0f, size.height - 0.5.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width, size.height - 0.5.dp.toPx()), 1.dp.toPx())
            }.padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)).background(c.codeTile), contentAlignment = Alignment.Center) { Symbol(Symbols.DataObject, c.onAccent, 15.dp) }
            if (fileName != null) Text(fileName, style = CortanaType.Bubble.copy(fontWeight = FontWeight.SemiBold, lineHeight = 18.sp), color = c.textPrimary, maxLines = 1)
            Text(path.orEmpty(), style = CortanaType.Code.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = c.logRunIcon, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (language != null) Tag(language.replaceFirstChar { it.uppercase() }, c.codeChipText, bg = c.badge, border = c.badgeBorder, height = 24.dp,
                textStyle = CortanaType.Caption.copy(fontSize = 12.sp))
            if (onCopy != null) CodeHeaderButton(if (copied) Symbols.Check else Symbols.ContentCopy, if (copied) c.successText else c.iconAction, if (copied) "Copié" else "Copier le code", onCopy)
            if (onToggleExpand != null) CodeHeaderButton(if (expanded) Symbols.CloseFullscreen else Symbols.OpenInFull, c.iconAction, if (expanded) "Réduire le code" else "Agrandir le code", onToggleExpand)
        }
        val maxH = if (expanded) CortanaDimens.CodeExpandedMaxHeight else CortanaDimens.CodeCollapsedMaxHeight
        SelectionContainer {
            Row(
                Modifier.fillMaxWidth().heightIn(max = maxH).let { if (Cortana.reduceMotion) it else it.animateContentSize(tween(250)) }
                    .verticalScroll(rememberScrollState()).padding(top = 6.dp, bottom = 8.dp),
            ) {
                if (lineNumbers) Column(Modifier.width(44.dp).padding(end = 14.dp), horizontalAlignment = Alignment.End) {
                    lines.indices.forEach { i -> Text("${i + 1}", style = CortanaType.Code, color = c.syntaxLineNumber, maxLines = 1) }
                }
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    rendered.forEach { l -> Text(if (l.isEmpty()) AnnotatedString(" ") else l, style = CortanaType.Code, color = c.syntaxPlain, softWrap = false, maxLines = 1) }
                }
            }
        }
    }
}

@Composable
private fun CodeHeaderButton(@DrawableRes icon: Int, tint: Color, label: String, onClick: () -> Unit) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    TouchTarget(Modifier.tap(src, onClick).semantics { contentDescription = label }) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (src.active()) c.badge else Color.Transparent), contentAlignment = Alignment.Center) {
            Symbol(icon, tint, 19.dp)
        }
    }
}

// ------------------------------------------------------------------ approval

/**
 * Approval card (spec 9.3): what, which tool, which target, which risk. Allowing is never done by the
 * card itself: [onAllow] opens the secure approval screen of the PolicyEngine (law WORKSPACE-2).
 */
@Composable
fun ApprovalCard(
    approval: Approval,
    title: String,
    tool: String,
    target: String,
    risk: String,
    onRefuse: () -> Unit,
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
    grantedLabel: String = "Autorisation accordée une fois",
    refusedLabel: String = "Action refusée",
) {
    val c = Cortana.colors
    when (approval) {
        Approval.Pending -> Column(
            modifier.padding(top = 16.dp).fillMaxWidth().enter("approval", 300).clip(CortanaShapes.Lg).background(c.approvalBg)
                .border(1.dp, c.warningBorder, CortanaShapes.Lg).padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics(mergeDescendants = false) { contentDescription = "Approbation requise : $title" },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.warning.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Symbol(Symbols.GppMaybe, c.warningText, 22.dp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Approbation requise", style = CortanaType.ItemTitle, color = c.warningTitle, modifier = Modifier.semantics { heading() })
                    Text(title, style = CortanaType.Control.copy(fontWeight = FontWeight.Normal), color = c.textControl)
                }
                Tag("Politique sécurité", c.warningSoft, border = c.warning.copy(alpha = 0.35f), height = 24.dp, textStyle = CortanaType.Caption.copy(fontSize = 12.sp))
            }
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(Modifier.padding(start = 50.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val meta = CortanaType.Caption
                Text(buildAnnotatedString { append("Outil "); withStyle(SpanStyle(fontFamily = CortanaType.Code.fontFamily, color = c.textBody)) { append(tool) } }, style = meta, color = c.metaText)
                Text(buildAnnotatedString { append("Cible "); withStyle(SpanStyle(fontFamily = CortanaType.Code.fontFamily, color = c.textBody)) { append(target) } }, style = meta, color = c.metaText)
                Text(buildAnnotatedString { append("Risque "); withStyle(SpanStyle(color = c.warningSoft)) { append(risk) } }, style = meta, color = c.metaText)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                CortanaButton("Refuser", onRefuse, kind = ButtonKind.Secondary, height = 40.dp, corner = 10.dp, padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                    textStyle = CortanaType.Control.copy(color = c.textBody), border = c.refuseBorder)
                CortanaButton("Autoriser une fois", onAllow, kind = ButtonKind.Primary, icon = Symbols.Check, height = 40.dp, corner = 10.dp,
                    padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp), gap = 8.dp, iconSize = 18.dp,
                    textStyle = CortanaType.Control.copy(fontWeight = FontWeight.SemiBold), shadow = true)
            }
        }
        Approval.Granted -> ResultChip(Symbols.VerifiedUser, c.successText, grantedLabel, c.approvedText, c.success.copy(alpha = 0.3f), c.success.copy(alpha = 0.07f), modifier, meta = "· $tool")
        Approval.Refused -> ResultChip(Symbols.Block, c.dangerText, refusedLabel, c.refusedText, c.dangerText.copy(alpha = 0.35f), c.dangerText.copy(alpha = 0.07f), modifier)
        Approval.None -> {}
    }
}

@Composable
private fun ResultChip(@DrawableRes icon: Int, iconColor: Color, text: String, fg: Color, border: Color, bg: Color, modifier: Modifier, meta: String? = null) {
    val c = Cortana.colors
    Row(
        modifier.padding(top = 16.dp).height(38.dp).clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, border, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Symbol(icon, iconColor, 19.dp)
        Text(text, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = fg)
        if (meta != null) Text(meta, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.Normal), color = c.approvedMeta)
    }
}

// ------------------------------------------------------------------ artifacts

/** Artifact chip at the end of a task (46 dp): tinted tile, name, meta. */
@Composable
fun ArtifactChip(@DrawableRes icon: Int, tint: Color, name: String, meta: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.height(46.dp).clip(CortanaShapes.Md).background(c.codeHeader).border(1.dp, c.artifactChipBorder, CortanaShapes.Md)
            .let { if (onClick != null) it.tap(src, onClick, label = "Ouvrir $name") else it }.padding(start = 8.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) { Symbol(icon, tint, 19.dp) }
        Text(name, style = CortanaType.ControlSmall.copy(fontWeight = FontWeight.SemiBold), color = c.textDefault, maxLines = 1)
        Text(meta, style = CortanaType.Caption, color = c.textMuted, maxLines = 1)
    }
}

/** Outlined link chip ("Ouvrir sur le Pixel 8 ↗"). */
@Composable
fun LinkChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Cortana.colors
    val src = remember { MutableInteractionSource() }
    Row(
        modifier.height(46.dp).clip(CortanaShapes.Md).background(if (src.active()) c.linkButtonHover else Color.Transparent)
            .border(1.dp, c.linkButtonBorder, CortanaShapes.Md).tap(src, onClick, label = label).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = CortanaType.ControlSmall, color = c.accentLinkHover, maxLines = 1)
        Symbol(Symbols.OpenInNew, c.accentLinkHover, 18.dp)
    }
}
