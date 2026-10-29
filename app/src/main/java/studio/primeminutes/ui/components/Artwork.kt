package studio.primeminutes.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import studio.primeminutes.online.ArtworkCache
import studio.primeminutes.ui.theme.LocalPrimeMinutesPalette

/** Decoded artwork kept in memory (a few dozen 256 px thumbnails at most), keyed by cache key + file stamp. */
private val memory = LruCache<String, ImageBitmap>(48)

/**
 * A channel's artwork, decorative (no content description: the row names the channel), from the on-device
 * cache ([ArtworkCache]; fetched once when missing and [url] is known). Missing: a Sky-tinted Podcasts tile.
 * A 1 dp hairline keeps a dark cover from bleeding into the dark surface.
 */
@Composable
fun ChannelArtwork(key: String?, url: String?, size: Dp, modifier: Modifier = Modifier, version: Int = 0) {
    val pal = LocalPrimeMinutesPalette.current
    val ctx = LocalContext.current
    val shape = MaterialTheme.shapes.medium
    val bmp by produceState(key?.let { k -> ArtworkCache.cached(ctx, k)?.let { memory.get("$k@${it.lastModified()}") } }, key, url, version) {
        val k = key ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val f = ArtworkCache.cached(ctx, k) ?: ArtworkCache.ensure(ctx, k, url) ?: return@withContext null
            val mk = "$k@${f.lastModified()}"
            memory.get(mk) ?: runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()?.also { memory.put(mk, it) }
        }
    }
    val m = modifier.size(size).clip(shape).border(1.dp, pal.Hairline, shape)
    val b = bmp
    if (b != null) Image(b, contentDescription = null, contentScale = ContentScale.Crop, modifier = m)
    else Box(m.background(pal.Sky.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Podcasts, contentDescription = null, tint = pal.Sky, modifier = Modifier.size(size * 0.55f))
    }
}
