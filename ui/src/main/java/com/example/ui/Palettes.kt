package com.example.ui

/**
 * One palette per product. BassBuzz is warm paper with a single red-orange, the LDAF scope is an amber-on-black
 * instrument, the ultrasonic generator is a cool grey bench with one cobalt. Text contrast is at least 4.5:1
 * everywhere and the controls' tracks are at least 3:1 against the page.
 */
object Palettes {
    val paper = Palette(
        bg = 0xFFF4F0E8.toInt(),
        surface = 0xFFE7E1D3.toInt(),
        ink = 0xFF1C1A16.toInt(),
        inkSoft = 0xFF5E584B.toInt(),
        line = 0xFF8F8672.toInt(),
        accent = 0xFFC23B22.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        danger = 0xFFA32613.toInt(),
    )

    val instrument = Palette(
        bg = 0xFF0D100E.toInt(),
        surface = 0xFF1A1F1C.toInt(),
        ink = 0xFFECEFEA.toInt(),
        inkSoft = 0xFFA3ADA6.toInt(),
        line = 0xFF5B665F.toInt(),
        accent = 0xFFFFB224.toInt(),
        onAccent = 0xFF1A1100.toInt(),
        danger = 0xFFFF7A6B.toInt(),
    )

    val bench = Palette(
        bg = 0xFFF1F2F4.toInt(),
        surface = 0xFFE1E4E9.toInt(),
        ink = 0xFF14171C.toInt(),
        inkSoft = 0xFF4B5360.toInt(),
        line = 0xFF7C8594.toInt(),
        accent = 0xFF1E4FD8.toInt(),
        onAccent = 0xFFFFFFFF.toInt(),
        danger = 0xFFB3261E.toInt(),
    )

    /** For the launchers: near-black panels that sit on a wallpaper. */
    val night = Palette(
        bg = 0xFF000000.toInt(),
        surface = 0xFF1C1C1E.toInt(),
        ink = 0xFFFFFFFF.toInt(),
        inkSoft = 0xFFAEAEB2.toInt(),
        line = 0xFF636366.toInt(),
        accent = 0xFF30D158.toInt(),
        onAccent = 0xFF00240A.toInt(),
        danger = 0xFFFF6961.toInt(),
    )
}
