package io.github.artisanguillonrenov.cortana.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.ui.theme.Cortana
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaMotion
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaShapes
import io.github.artisanguillonrenov.cortana.ui.theme.CortanaType
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols

/**
 * Composer of the design (66 dp): Joindre, the field (52 dp, grows up to five lines), micro in the field,
 * Envoyer. Entrée sends and Maj+Entrée goes to the line (when [enterToSend]); Échap asks STOP (spec 2).
 * In voice mode the placeholder changes and four bars show that Cortana listens.
 */
@Composable
fun CortanaComposer(
    text: String,
    onText: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    listening: Boolean,
    modifier: Modifier = Modifier,
    enterToSend: Boolean = true,
    onEscape: (() -> Unit)? = null,
    focus: FocusRequester = remember { FocusRequester() },
    attachLabel: String = "Joindre, mode, commandes",
) {
    val c = Cortana.colors
    val fieldSrc = remember { MutableInteractionSource() }
    val focused by fieldSrc.collectIsFocusedAsState()
    // The text lives in the caller; the selection is kept here so that editing in the middle keeps the cursor.
    var local by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val value = if (local.text == text) local else TextFieldValue(text, TextRange(text.length))
    Row(
        modifier.fillMaxWidth().heightIn(min = 66.dp).clip(CortanaShapes.Xl).background(c.composer).border(1.dp, c.surfaceBorder, CortanaShapes.Xl)
            .padding(start = 6.dp, top = 7.dp, end = 8.dp, bottom = 7.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val attachSrc = remember { MutableInteractionSource() }
        Box(
            Modifier.size(48.dp).clip(CortanaShapes.Md).background(if (attachSrc.active()) c.controlHoverAlt else Color.Transparent)
                .tap(attachSrc, onAttach, label = attachLabel).semantics { contentDescription = attachLabel }.padding(bottom = 0.dp),
            contentAlignment = Alignment.Center,
        ) { Symbol(Symbols.AttachFile, c.attachIcon, 25.dp, rotation = 45f) }

        Box(Modifier.weight(1f).padding(bottom = 0.dp)) {
            BasicTextField(
                value = value,
                onValueChange = { local = it; onText(it.text) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 52.dp * 3)
                    .let { if (focused) it.glow(c.focusHalo, 0.dp, 13.dp, spread = 3.dp) else it }
                    .clip(CortanaShapes.Field).background(c.input).border(1.dp, if (focused) c.focusBorder else c.controlBorder, CortanaShapes.Field)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when {
                            e.key == Key.Escape && onEscape != null -> { onEscape(); true }
                            (e.key == Key.Enter || e.key == Key.NumPadEnter) && enterToSend && !e.isShiftPressed -> { onSend(); true }
                            else -> false
                        }
                    }
                    .semantics { contentDescription = "Message pour Cortana" },
                textStyle = CortanaType.Body.copy(lineHeight = CortanaType.Body.fontSize * 1.5f, color = c.textPrimary),
                interactionSource = fieldSrc,
                cursorBrush = SolidColor(c.accentIcon),
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = 52.dp).padding(start = 16.dp, end = 56.dp, top = 14.dp, bottom = 14.dp), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(
                            if (listening) "Je vous écoute… parlez naturellement" else "Posez votre question ou donnez une instruction…",
                            style = CortanaType.Body.copy(lineHeight = CortanaType.Body.fontSize * 1.5f), color = c.placeholder, maxLines = 1,
                        )
                        inner()
                    }
                },
            )
            if (listening) VoiceWave(Modifier.align(Alignment.BottomEnd).padding(end = 52.dp, bottom = 17.dp))
            val micSrc = remember { MutableInteractionSource() }
            TouchTarget(
                Modifier.tap(micSrc, onMic, label = if (listening) "Arrêter l'écoute" else "Dicter")
                    .semantics { contentDescription = "Dicter"; stateDescription = if (listening) "écoute en cours" else "inactive" },
                Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 6.dp),
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (micSrc.active()) c.controlPressed else Color.Transparent), contentAlignment = Alignment.Center) {
                    Symbol(Symbols.Mic, if (listening) c.accentText else c.iconAction, 24.dp)
                }
            }
        }

        val sendSrc = remember { MutableInteractionSource() }
        val pressed by sendSrc.collectIsPressedAsState()
        val hover = sendSrc.active()
        Box(
            Modifier.size(52.dp).pressScale(pressed, 0.96f, Cortana.reduceMotion).glow(c.sendGlow, 22.dp, 13.dp, spread = (-8).dp, offsetY = 8.dp)
                .clip(CortanaShapes.Field).background(c.accentButton).topHighlight(c.onAccent.copy(alpha = 0.2f), 13.dp).brighten(hover, c.onAccent, 0.1f)
                .tap(sendSrc, onSend, label = "Envoyer").semantics { contentDescription = "Envoyer" },
            contentAlignment = Alignment.Center,
        ) { Symbol(Symbols.SendFill, c.onAccent, 24.dp) }
    }
}

/** Four 3×18 dp bars (scaleY .3 → 1, 0.9 s, staggered by 150 ms); still when motion is reduced. */
@Composable
fun VoiceWave(modifier: Modifier = Modifier) {
    val c = Cortana.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            val t by loop(CortanaMotion.WavePeriod, rest = 0.5f, delay = i * CortanaMotion.WaveStagger)
            val k = if (t < 0.5f) t * 2 else (1 - t) * 2
            Box(Modifier.size(3.dp, 18.dp).graphicsLayer { scaleY = 0.3f + 0.7f * k }.clip(RoundedCornerShape(2.dp)).background(c.accentText))
        }
    }
}
