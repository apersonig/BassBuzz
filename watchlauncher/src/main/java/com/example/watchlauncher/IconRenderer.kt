package com.example.watchlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable

/** Turns any app icon into a round bitmap, the way watch icons look. */
object IconRenderer {

    private const val LEGACY_BACKGROUND = 0xFF2C2C2E.toInt()

    fun render(icon: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        if (icon is AdaptiveIconDrawable) {
            // The two layers are 108dp wide with the visible part being the middle 72dp: 1.5x the icon size.
            val pad = (size * 0.25f).toInt()
            for (layer in listOf(icon.background, icon.foreground)) {
                layer ?: continue
                layer.setBounds(-pad, -pad, size + pad, size + pad)
                layer.draw(c)
            }
        } else {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LEGACY_BACKGROUND }
            c.drawCircle(size / 2f, size / 2f, size / 2f, paint)
            val inset = (size * 0.16f).toInt()
            icon.setBounds(inset, inset, size - inset, size - inset)
            icon.draw(c)
        }

        // Keep only the inside of the circle, with a smooth edge.
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        c.drawCircle(size / 2f, size / 2f, size / 2f, mask)
        return bmp
    }

    fun half(full: Bitmap): Bitmap = Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
}
