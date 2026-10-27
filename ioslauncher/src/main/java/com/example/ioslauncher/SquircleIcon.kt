package com.example.ioslauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Turns any app icon into a continuous-corner square, flat, with nothing added on top. */
object SquircleIcon {

    /** A superellipse, which is what gives iOS icons their smooth "continuous" corners. */
    fun shape(size: Float, n: Double = 5.0): Path {
        val a = size / 2f
        val path = Path()
        val steps = 120
        for (i in 0 until steps) {
            val t = 2.0 * PI * i / steps
            val ct = cos(t)
            val st = sin(t)
            val x = a + a * sign(ct).toFloat() * abs(ct).pow(2.0 / n).toFloat()
            val y = a + a * sign(st).toFloat() * abs(st).pow(2.0 / n).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    fun render(icon: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()

        if (icon is AdaptiveIconDrawable) {
            val pad = (size * 0.25f).toInt()
            for (layer in listOf(icon.background, icon.foreground)) {
                layer ?: continue
                layer.setBounds(-pad, -pad, size + pad, size + pad)
                layer.draw(c)
            }
        } else {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
            c.drawRect(0f, 0f, s, s, bg)
            icon.setBounds(0, 0, size, size)
            icon.draw(c)
        }

        val shape = shape(s)

        // Erase everything outside the shape, with a smooth edge.
        val outside = Path().apply {
            addPath(shape)
            fillType = Path.FillType.INVERSE_EVEN_ODD
        }
        val eraser = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        c.drawPath(outside, eraser)
        return bmp
    }
}
