package io.github.artisanguillonrenov.cortana.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.artisanguillonrenov.cortana.core.chat.AttachmentRef
import io.github.artisanguillonrenov.cortana.core.chat.ProducedImages
import io.github.artisanguillonrenov.cortana.core.dev.ArtifactService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnails of local image artifacts, shared by the Workspace and the classic conversation screen. */
object ArtifactImages {
    /**
     * A bounded thumbnail: the real MIME of the stored artifact is checked (a PDF or an arbitrary binary
     * is never decoded as a picture), the bitmap is subsampled to about [maxPx] per side, never decoded whole.
     */
    suspend fun thumbnail(artifacts: ArtifactService, artifactId: String, maxPx: Int = 640): ImageBitmap? = withContext(Dispatchers.IO) {
        val a = artifacts.get(artifactId) ?: return@withContext null
        val f = artifacts.file(a)
        if (!f.isFile || !ProducedImages.isBitmap(a.mime)) return@withContext null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / sample > maxPx * 2 || bounds.outHeight / sample > maxPx * 2) sample *= 2
        runCatching { android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap() }.getOrNull()
    }
}

/** A local image artifact as a picture (classic screen); its name when it cannot be decoded. */
@Composable
fun ArtifactThumbnail(artifacts: ArtifactService, ref: AttachmentRef, modifier: Modifier = Modifier) {
    var image by remember(ref.artifactId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(ref.artifactId) { mutableStateOf(false) }
    LaunchedEffect(ref.artifactId) { image = ArtifactImages.thumbnail(artifacts, ref.artifactId); failed = image == null }
    val img = image
    when {
        img != null -> Image(img, contentDescription = (ref.note ?: "Image") + " : " + ref.name, contentScale = ContentScale.Crop,
            modifier = modifier.size(180.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)))
        failed -> Text("🖼 ${ref.name} (aperçu indisponible)", style = MaterialTheme.typography.labelMedium)
    }
}
