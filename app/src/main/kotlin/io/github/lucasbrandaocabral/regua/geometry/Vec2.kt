package io.github.lucasbrandaocabral.regua.geometry

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Ponto ou direção em pixels da tela (y cresce para baixo, como no Canvas). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    infix fun dot(o: Vec2) = x * o.x + y * o.y

    val length: Double get() = hypot(x, y)

    fun distanceTo(o: Vec2) = (this - o).length

    fun normalized(): Vec2 {
        val len = length
        return if (len < 1e-9) ZERO else Vec2(x / len, y / len)
    }

    companion object {
        val ZERO = Vec2(0.0, 0.0)

        fun ofAngle(radians: Double) = Vec2(cos(radians), sin(radians))
    }
}
