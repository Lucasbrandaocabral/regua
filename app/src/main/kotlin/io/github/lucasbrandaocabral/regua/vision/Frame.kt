package io.github.lucasbrandaocabral.regua.vision

import kotlin.math.roundToInt

/**
 * Um quadro da tela. Cada pixel fica como o ImageReader do Android entrega em RGBA_8888 lido em
 * little-endian: vermelho no byte mais baixo (0xAABBGGRR).
 */
class Frame(val width: Int, val height: Int, val pixels: IntArray) {

    fun pixel(x: Int, y: Int): Int = pixels[y * width + x]

    fun isWhite(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height && isWhite(pixel(x, y))

    fun isWhite(x: Double, y: Double): Boolean = isWhite(x.roundToInt(), y.roundToInt())

    companion object {
        fun red(p: Int) = p and 0xFF
        fun green(p: Int) = (p shr 8) and 0xFF
        fun blue(p: Int) = (p shr 16) and 0xFF

        fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (b shl 16) or (g shl 8) or r

        /**
         * Branco "de jogo": claro e sem cor. Pega a bola branca (inclusive a parte sombreada) e as
         * linhas de mira, mas não o pano da mesa nem as linhas coloridas que a régua desenha.
         */
        fun isWhite(p: Int): Boolean {
            val r = red(p)
            val g = green(p)
            val b = blue(p)
            val min = minOf(r, g, b)
            return min >= 185 && maxOf(r, g, b) - min <= 70
        }

        /** Distância simples entre duas cores (soma das diferenças de cada canal). */
        fun distance(a: Int, b: Int): Int =
            kotlin.math.abs(red(a) - red(b)) + kotlin.math.abs(green(a) - green(b)) + kotlin.math.abs(blue(a) - blue(b))
    }
}
