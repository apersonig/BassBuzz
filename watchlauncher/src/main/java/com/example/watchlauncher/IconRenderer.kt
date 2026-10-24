package com.example.watchlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
            icon.setBounds(0, 0, size, size) // square icons fill the circle; the mask below rounds them
            icon.draw(c)
        }

        // Erase everything outside the circle. (A DST_IN circle would leave the corners alone, because a blend
        // only touches the pixels the shape covers, so the shape drawn is the outside.)
        val outside = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_EVEN_ODD
        }
        val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawPath(outside, eraser)
        return bmp
    }

    fun half(full: Bitmap): Bitmap = Bitmap.createScaledBitmap(full, full.width / 2, full.height / 2, true)
}
