package com.timeagent.fish

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.widget.ImageView
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.timeagent.core.*
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
private fun imageFrames(references: List<String>): List<Drawable>? {
    val context = LocalContext.current
    val frames by produceState<List<Drawable>?>(null, references) {
        value = withContext(Dispatchers.IO) { references.map { reference ->
            val bytes = if (reference.startsWith("asset:")) context.assets.open(reference.removePrefix("asset:")).use { it.readBytes() }
                else File(reference).readBytes()
            if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val ratio = 512f / maxOf(info.size.width, info.size.height)
                if (ratio < 1) decoder.setTargetSize((info.size.width * ratio).toInt().coerceAtLeast(1), (info.size.height * ratio).toInt().coerceAtLeast(1))
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / inSampleSize > 512) inSampleSize *= 2
                }
                android.graphics.drawable.BitmapDrawable(context.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options))
            }
        } }
    }
    return frames
}

@Composable
private fun DrawableImage(drawable: Drawable?, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            if (view.drawable !== drawable) {
                (view.drawable as? Animatable)?.stop()
                view.setImageDrawable(drawable); (drawable as? Animatable)?.start()
            }
        }, onRelease = { (it.drawable as? Animatable)?.stop() })
}

@Composable
fun FishAssetImage(reference: String, modifier: Modifier = Modifier) {
    DrawableImage(imageFrames(listOf(reference))?.firstOrNull(), modifier)
}

@Composable
fun FishAssetSprite(asset: FishAsset, modifier: Modifier = Modifier) {
    val frames = imageFrames(asset.files)
    var frame by remember(asset.id) { mutableIntStateOf(0) }
    LaunchedEffect(asset.id, frames) {
        if (frames != null && frames.size > 1) {
            val start = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                frame = ((elapsed / asset.frameMs) % frames.size).toInt()
                delay(asset.frameMs - elapsed % asset.frameMs)
            }
        }
    }
    val motion = rememberInfiniteTransition(label = "fish-breathing")
    val breathe by motion.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breath")
    DrawableImage(frames?.get(frame % frames.size), modifier.graphicsLayer {
        translationY = -3f * breathe; scaleX = 1f + .008f * breathe; scaleY = 1f + .008f * breathe
    })
}

@Composable
fun CharacterSprite(session: FishSession, modifier: Modifier = Modifier) {
    val config by session.companion.settings.collectAsStateWithLifecycle()
    val presence by session.companion.presence.collectAsStateWithLifecycle()
    val voice by session.voice.status.collectAsStateWithLifecycle()
    val outfit by session.character.outfit.collectAsStateWithLifecycle()
    val outfits by session.character.outfits.collectAsStateWithLifecycle()
    val actions by session.character.actions.collectAsStateWithLifecycle()
    val preview by session.character.preview.collectAsStateWithLifecycle()
    val trigger = if (voice.speaking) "SPEAKING" else if (presence.activity == FishActivity.IDLE && presence.mood == FishMood.HAPPY) "HAPPY" else presence.activity.name
    val action = preview ?: actions.lastOrNull { it.trigger == trigger && it.appearance == config.appearance.name }
    val asset = action ?: outfits.firstOrNull { it.id == outfit && it.appearance == config.appearance.name }
    if (asset != null) FishAssetSprite(asset, modifier)
    else FishSprite(config.appearance, presence, modifier)
}
