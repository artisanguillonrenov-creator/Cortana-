package io.github.artisanguillonrenov.cortana.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.github.artisanguillonrenov.cortana.core.chat.WebResultItem
import io.github.artisanguillonrenov.cortana.core.chat.WebResults
import io.github.artisanguillonrenov.cortana.core.media.RemoteImages
import io.github.artisanguillonrenov.cortana.ui.theme.Symbols
import okhttp3.OkHttpClient

/**
 * What the web-result views may use: pictures through [RemoteImages] (guarded client, https, image types,
 * disk cache) and, for inline video, the same guarded client (every stream request and redirect goes
 * through the SSRF guard). Null members: pictures and inline playback are simply not offered.
 */
class WebMediaAccess(
    val images: RemoteImages? = null,
    val streamClient: OkHttpClient? = null,
    /** Test hook: pictures without network. */
    val imageOverride: (suspend (String, Int) -> ImageBitmap?)? = null,
) {
    suspend fun image(url: String, maxPx: Int): ImageBitmap? =
        imageOverride?.invoke(url, maxPx) ?: images?.bitmap(url, maxPx)?.asImageBitmap()
}

val LocalWebMedia = staticCompositionLocalOf { WebMediaAccess() }

/**
 * Rich web results inside Cortana's answer (never Markdown, never HTML): an image gallery, video cards
 * (inline player for direct streams, the platform's page otherwise — YouTube opens in its app or site),
 * and page cards. Everything here is external content: labelled as such, opened only through [onOpen]
 * (sanitised https/http links).
 */
@Composable
fun WebResultsView(items: List<WebResultItem>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val images = items.filter { it.type == WebResults.IMAGE }
    val videos = items.filter { it.type == WebResults.VIDEO }
    val pages = items.filter { it.type == WebResults.WEB }
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Résultats web" }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (images.isNotEmpty()) ImageGallery(images, onOpen)
        videos.forEach { VideoCard(it, onOpen) }
        pages.forEach { WebCard(it, onOpen) }
        Text("Contenu web externe, non vérifié · ${items.mapNotNull { it.provider }.distinct().joinToString().ifEmpty { "recherche web" }}",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteImage(url: String?, description: String, modifier: Modifier, maxPx: Int = 480, contentScale: ContentScale = ContentScale.Crop) {
    val media = LocalWebMedia.current
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { image = url?.let { runCatching { media.image(it, maxPx) }.getOrNull() } }
    val img = image
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (img != null) Image(img, contentDescription = description, modifier = Modifier.matchParentSize(), contentScale = contentScale)
        else Icon(painterResource(Symbols.Language), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ImageGallery(images: List<WebResultItem>, onOpen: (String) -> Unit) {
    var zoomed by rememberSaveable { mutableStateOf<Int?>(null) }
    val shape = RoundedCornerShape(10.dp)
    if (images.size == 1) {
        val i = images.first()
        val ratio = if (i.width != null && i.height != null) (i.width.toFloat() / i.height).coerceIn(0.6f, 2.2f) else 1.5f
        RemoteImage(i.thumbnailUrl ?: i.mediaUrl, "Image : ${i.title}. Touchez pour agrandir.",
            Modifier.fillMaxWidth().aspectRatio(ratio).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).clickable(onClickLabel = "Agrandir") { zoomed = 0 })
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.semantics { contentDescription = "Galerie de ${images.size} images" }) {
            items(images.size) { k ->
                val i = images[k]
                Column(Modifier.width(150.dp)) {
                    RemoteImage(i.thumbnailUrl ?: i.mediaUrl, "Image ${k + 1} sur ${images.size} : ${i.title}. Touchez pour agrandir.",
                        Modifier.size(150.dp).clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).clickable(onClickLabel = "Agrandir") { zoomed = k })
                    Text(i.source ?: i.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
    zoomed?.let { k -> images.getOrNull(k)?.let { ImageViewer(it, onOpen) { zoomed = null } } }
}

@Composable
private fun ImageViewer(i: WebResultItem, onOpen: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Surface(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(8.dp)) {
                Box(Modifier.heightIn(max = 560.dp).fillMaxWidth().clipToBounds()
                    .pointerInput(Unit) { detectTransformGestures { _, pan, z, _ -> scale = (scale * z).coerceIn(1f, 6f); offset += pan } }) {
                    RemoteImage(i.mediaUrl ?: i.thumbnailUrl, i.title, Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), maxPx = 1600, contentScale = ContentScale.Fit)
                }
                Text(i.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text(i.source ?: WebResults.hostOf(i.url).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    TextButton(onClick = { onOpen(i.url) }) { Text("Ouvrir la source") }
                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("100 %") }
                    TextButton(onClick = onClose) { Text("Fermer") }
                }
            }
        }
    }
}

@Composable
private fun VideoCard(v: WebResultItem, onOpen: (String) -> Unit) {
    val media = LocalWebMedia.current
    val kind = v.metadata["videoKind"] ?: WebResults.videoKind(v.url, v.mediaUrl)
    val inline = kind == WebResults.DIRECT && v.mediaUrl != null && media.streamClient != null
    var playing by rememberSaveable(v.url) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .semantics { contentDescription = "Vidéo : ${v.title}" }) {
        if (playing && inline) InlinePlayer(v.mediaUrl!!, media.streamClient!!, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable(onClickLabel = if (inline) "Lire la vidéo" else "Ouvrir la vidéo") {
            if (inline) playing = true else onOpen(v.url)
        }) {
            RemoteImage(v.thumbnailUrl, "Miniature : ${v.title}", Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            Box(Modifier.align(Alignment.Center).size(52.dp).clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                Icon(painterResource(if (inline) Symbols.PlayArrowFill else Symbols.OpenInNew), contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
            }
            v.durationSeconds?.let { d ->
                Text(WebResults.formatDuration(d), style = MaterialTheme.typography.labelSmall, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp))
            }
        }
        Column(Modifier.clickable(onClickLabel = "Ouvrir la page de la vidéo") { onOpen(v.url) }.padding(10.dp)) {
            Text(v.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(v.source, when (kind) { WebResults.YOUTUBE -> "YouTube"; WebResults.DIRECT -> "lecture intégrée"; else -> null }).distinct().joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Media3 player for a direct stream (mp4, webm, HLS), fetched through the guarded client; released with the card. */
@OptIn(UnstableApi::class)
@Composable
private fun InlinePlayer(url: String, client: OkHttpClient, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(client)))
            .build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player } }, modifier = modifier)
}

@Composable
private fun WebCard(w: WebResultItem, onOpen: (String) -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(Modifier.fillMaxWidth().clip(shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(onClickLabel = "Ouvrir la page") { onOpen(w.url) }.padding(10.dp)
        .semantics { contentDescription = "Page web : ${w.title}, ${w.source ?: ""}" },
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (w.thumbnailUrl != null) RemoteImage(w.thumbnailUrl, "Image de la page", Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)), maxPx = 240)
        Column(Modifier.weight(1f)) {
            Text(w.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(w.source ?: WebResults.hostOf(w.url).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            w.snippet?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
