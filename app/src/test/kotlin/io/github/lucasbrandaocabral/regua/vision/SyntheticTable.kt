package io.github.lucasbrandaocabral.regua.vision

import io.github.lucasbrandaocabral.regua.geometry.Table
import io.github.lucasbrandaocabral.regua.geometry.Vec2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Desenha quadros parecidos com os do 8 Ball Pool para testar o [AimDetector]: pano com degradê,
 * bolas, taco e as linhas de mira brancas com contorno escuro.
 */
class SyntheticTable(
    val width: Int = 1200,
    val height: Int = 560,
    val table: Table = Table(left = 100.0, top = 30.0, right = 1100.0, bottom = 530.0, ballRadius = 15.0),
) {
    val r = table.ballRadius
    val pixels = IntArray(width * height)

    // Linhas do jogo: miolo branco de ~3 px com contorno escuro (proporção medida num print).
    private val lineHalf = 0.09 * r
    private val outline = 0.11 * r

    init {
        val cx = (table.left + table.right) / 2
        val cy = (table.top + table.bottom) / 2
        for (y in 0 until height) {
            for (x in 0 until width) {
                val inside = x >= table.left && x <= table.right && y >= table.top && y <= table.bottom
                pixels[y * width + x] = if (inside) {
                    val t = min(1.0, hypot((x - cx) / (width / 2.0), (y - cy) / (height / 2.0)))
                    rgb(mix(127, 34, t), mix(198, 126, t), mix(218, 165, t))
                } else {
                    rgb(150, 30, 20)
                }
            }
        }
    }

    fun frame() = Frame(width, height, pixels.copyOf())

    fun ball(c: Vec2, r: Int, g: Int, b: Int, striped: Boolean = false) {
        fillDisc(c, this.r + 1, rgb(20, 20, 20))
        fillDisc(c, this.r) { x, y ->
            val stripe = striped && kotlin.math.abs(y - c.y) > 0.5 * this.r
            val shade = 1.0 - 0.35 * max(0.0, ((x - c.x) + (y - c.y)) / (2 * this.r))
            if (stripe) rgb((240 * shade).toInt(), (238 * shade).toInt(), (230 * shade).toInt())
            else rgb((r * shade).toInt(), (g * shade).toInt(), (b * shade).toInt())
        }
        // Círculo branco do número.
        fillDisc(c, 0.45 * this.r, rgb(245, 245, 240))
        fillDisc(c, 0.15 * this.r, rgb(10, 10, 10))
    }

    /** Listrada com o lado branco para cima: miolo branco e a cor só na borda. */
    fun stripeBallWhiteUp(c: Vec2, r: Int, g: Int, b: Int) {
        fillDisc(c, this.r + 1, rgb(20, 20, 20))
        fillDisc(c, this.r, rgb(r, g, b))
        fillDisc(c, 0.8 * this.r, rgb(250, 250, 248))
    }

    fun cueBall(c: Vec2) {
        fillDisc(c, r + 1, rgb(30, 30, 30))
        fillDisc(c, r) { x, y ->
            val t = max(0.0, ((x - c.x) + (y - c.y)) / (2 * r))
            rgb(mix(252, 200, t), mix(244, 206, t), mix(225, 216, t))
        }
    }

    /** Taco atrás da branca, apontando para [direction]. */
    fun cue(c: Vec2, direction: Vec2, shaft: Int = rgb(140, 70, 30)) {
        val tip = c - direction * (r + 2)
        val butt = tip - direction * (30 * r)
        stroke(tip, butt, 0.3 * r, shaft)
        stroke(tip, tip - direction * (0.6 * r), 0.3 * r, rgb(240, 240, 235))
    }

    /** Linha branca com contorno escuro, como as de mira do jogo. */
    fun guide(a: Vec2, b: Vec2) {
        stroke(a, b, lineHalf + outline, rgb(20, 30, 35))
        stroke(a, b, lineHalf, rgb(255, 255, 255))
    }

    fun ring(c: Vec2) {
        strokeRing(c, r, lineHalf + outline, rgb(20, 30, 35))
        strokeRing(c, r, lineHalf, rgb(255, 255, 255))
    }

    /**
     * Desenha tudo o que o jogo mostra quando a branca em [cue] mira em [direction] e acerta [target].
     * [objectLine] é o comprimento da linha da bola alvo, em raios (curta nos cortes finos).
     */
    fun aimAt(cue: Vec2, target: Vec2?, direction: Vec2, ghostOnCushion: Vec2? = null, objectLine: Double = 6.0) {
        cue(cue, direction)
        cueBall(cue)
        if (target == null) {
            val end = ghostOnCushion ?: (cue + direction * (12 * r))
            guide(cue, end)
            if (ghostOnCushion != null) ring(ghostOnCushion)
            return
        }
        // Onde a branca encosta: a 2 raios do centro do alvo, sobre a linha de mira.
        val m = cue - target
        val b = m dot direction
        val c = (m dot m) - 4 * r * r
        val t = -b - kotlin.math.sqrt(b * b - c)
        val ghost = cue + direction * t
        val n = (target - ghost).normalized()
        val tangent = (direction - n * (direction dot n)).normalized()
        guide(cue, ghost)
        ring(ghost)
        guide(ghost, ghost + tangent * (2.3 * r))
        guide(target, target + n * (objectLine * r))
    }

    private fun fillDisc(c: Vec2, radius: Double, color: Int) = fillDisc(c, radius) { _, _ -> color }

    private inline fun fillDisc(c: Vec2, radius: Double, color: (Double, Double) -> Int) {
        forBox(c.x - radius, c.y - radius, c.x + radius, c.y + radius) { x, y ->
            if (hypot(x - c.x, y - c.y) <= radius) pixels[y * width + x] = color(x.toDouble(), y.toDouble())
        }
    }

    // As linhas têm a borda suavizada, como no jogo: o pixel cortado pela borda mistura as duas cores.
    private fun stroke(a: Vec2, b: Vec2, half: Double, color: Int) {
        val ab = b - a
        val len2 = ab dot ab
        val pad = half + 1
        forBox(min(a.x, b.x) - pad, min(a.y, b.y) - pad, max(a.x, b.x) + pad, max(a.y, b.y) + pad) { x, y ->
            val p = Vec2(x.toDouble(), y.toDouble())
            val t = if (len2 == 0.0) 0.0 else (((p - a) dot ab) / len2).coerceIn(0.0, 1.0)
            paint(x, y, half + 0.5 - (a + ab * t).distanceTo(p), color)
        }
    }

    private fun strokeRing(c: Vec2, radius: Double, half: Double, color: Int) {
        val outer = radius + half + 1
        forBox(c.x - outer, c.y - outer, c.x + outer, c.y + outer) { x, y ->
            paint(x, y, half + 0.5 - kotlin.math.abs(hypot(x - c.x, y - c.y) - radius), color)
        }
    }

    private fun paint(x: Int, y: Int, coverage: Double, color: Int) {
        val alpha = coverage.coerceIn(0.0, 1.0)
        if (alpha == 0.0) return
        val old = pixels[y * width + x]
        fun blend(a: Int, b: Int) = (a + (b - a) * alpha).roundToInt()
        pixels[y * width + x] = rgb(
            blend(Frame.red(old), Frame.red(color)),
            blend(Frame.green(old), Frame.green(color)),
            blend(Frame.blue(old), Frame.blue(color)),
        )
    }

    private inline fun forBox(x0: Double, y0: Double, x1: Double, y1: Double, action: (Int, Int) -> Unit) {
        for (y in max(0, floor(y0).toInt())..min(height - 1, ceil(y1).toInt())) {
            for (x in max(0, floor(x0).toInt())..min(width - 1, ceil(x1).toInt())) action(x, y)
        }
    }

    private fun mix(a: Int, b: Int, t: Double) = (a + (b - a) * t).toInt()

    private fun rgb(r: Int, g: Int, b: Int) = Frame.rgb(r, g, b)
}
