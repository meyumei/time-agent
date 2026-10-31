package com.timeagent.workout

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Movie
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock

/** Android 8 has Movie but no ImageDecoder animated drawable. */
@Suppress("DEPRECATION")
internal class WorkoutGifDrawable(private val movie: Movie) : Drawable(), Animatable {
    private var running = false
    private var startedAt = 0L
    private val frame = Runnable { if (running) { invalidateSelf(); scheduleSelf(frameTick, SystemClock.uptimeMillis() + 50) } }
    private val frameTick: Runnable get() = frame
    override fun getIntrinsicWidth() = movie.width()
    override fun getIntrinsicHeight() = movie.height()
    override fun draw(canvas: Canvas) {
        movie.setTime(if (running) ((SystemClock.uptimeMillis() - startedAt) % (movie.duration().takeIf { it > 0 } ?: 1000)).toInt() else 0)
        val scale = minOf(bounds.width().toFloat() / movie.width().coerceAtLeast(1), bounds.height().toFloat() / movie.height().coerceAtLeast(1))
        val saved = canvas.save()
        canvas.translate(bounds.left + (bounds.width() - movie.width() * scale) / 2, bounds.top + (bounds.height() - movie.height() * scale) / 2)
        canvas.scale(scale, scale); movie.draw(canvas, 0f, 0f); canvas.restoreToCount(saved)
    }
    override fun start() { if (running) return; running = true; startedAt = SystemClock.uptimeMillis(); scheduleSelf(frame, startedAt); invalidateSelf() }
    override fun stop() { running = false; unscheduleSelf(frame) }
    override fun isRunning() = running
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
