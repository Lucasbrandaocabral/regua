package io.github.lucasbrandaocabral.regua.vision

import io.github.lucasbrandaocabral.regua.geometry.Table
import io.github.lucasbrandaocabral.regua.geometry.Vec2
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Acha num quadro da tela a bola branca, a linha de mira que o jogo desenha saindo dela e, quando a
 * linha termina em outra bola, essa bola alvo.
 *
 * Usa só o que o 8 Ball Pool desenha: a branca é o círculo mais branco da mesa; as linhas de mira são
 * traços brancos finos (com contorno escuro) e o ponto de choque é um anel branco do tamanho da bola.
 * As medidas são em pixels do quadro e escalam com o raio da bola da [Table].
 */
class AimDetector {

    class Result(
        /** Centro da bola branca. */
        val cue: Vec2,
        /** Direção da linha de mira do jogo (unitária). */
        val direction: Vec2,
        /** Até onde a linha do jogo aparece, medido a partir do centro da branca. */
        val guideLength: Double,
        /** Centro do anel que o jogo desenha onde a branca encosta na bola alvo. */
        val ghost: Vec2?,
        /** Centro da bola alvo. */
        val objectBall: Vec2?,
        /** Até onde aparece a linha que o jogo desenha saindo da bola alvo, medido do centro dela. */
        val objectGuideLength: Double,
    )

    /** Reta ajustada num traço: passa por [origin] na direção [direction] e o traço acaba em [end]. */
    private class Line(val origin: Vec2, val direction: Vec2, val end: Vec2)

    private var integral = IntArray(0)

    /**
     * [previous] é o resultado do quadro anterior: a branca quase nunca muda de lugar enquanto se mira
     * e a linha gira pouco de um quadro para o outro, então procura primeiro perto deles (bem mais
     * rápido) e só varre a mesa inteira se não achar.
     */
    fun detect(frame: Frame, table: Table, findObjectBall: Boolean = true, previous: Result? = null): Result? {
        val r = table.ballRadius
        if (r < 3.0) return null
        val roughCue = previous?.let { findCueBall(frame, table, near = it.cue) } ?: findCueBall(frame, table, near = null) ?: return null
        val guide = previous?.let { findGuide(frame, table, roughCue, aroundDegrees = Math.toDegrees(atan2(it.direction.y, it.direction.x))) }
            ?: findGuide(frame, table, roughCue, aroundDegrees = null)
            ?: return null
        var direction = guide.direction
        var cue = cueCenterOnLine(frame, guide.origin, direction, r)
        var guideLength = (guide.end - cue) dot direction
        var ring = findGhost(frame, cue, direction, guideLength, r)
        if (ring != null) {
            // Dentro do anel a linha se mistura com ele e com a linha de desvio da branca: refaz o
            // ajuste só com o trecho limpo, antes do anel.
            val ringDistance = (ring - cue) dot direction
            val clean = collectAndFit(frame, table, cue, direction, 1.5 * r, minGuideSamples(r), stopAt = ringDistance - 1.5 * r)
            if (clean != null) {
                direction = clean.direction
                cue = cueCenterOnLine(frame, clean.origin, direction, r)
                ring = findGhost(frame, cue, direction, ringDistance, r) ?: ring
            }
            guideLength = (ring - cue) dot direction
        }
        // Anel colado na tabela é a branca batendo na tabela, não numa bola.
        val ghost = ring?.takeIf { insideInner(table, it, 0.6 * r) }
        val objectBall = if (ghost != null && findObjectBall) findObjectBall(frame, table, ghost, direction) else null
        return Result(
            cue = cue,
            direction = direction,
            guideLength = guideLength,
            ghost = ghost,
            objectBall = objectBall?.origin,
            objectGuideLength = objectBall?.let { (it.end - it.origin) dot it.direction } ?: 0.0,
        )
    }

    /**
     * A branca é onde um quadrado do tamanho do miolo da bola fica mais cheio de branco. Com [near],
     * procura só em volta desse ponto.
     */
    private fun findCueBall(frame: Frame, table: Table, near: Vec2?): Vec2? {
        val r = table.ballRadius
        val half = max(2, (0.6 * r).roundToInt())
        val reach = 3 * r
        val x0 = max(0, max(table.minX, (near?.x ?: table.minX) - reach).toInt())
        val x1 = min(frame.width - 1, ceil(min(table.maxX, (near?.x ?: table.maxX) + reach)).toInt())
        val y0 = max(0, max(table.minY, (near?.y ?: table.minY) - reach).toInt())
        val y1 = min(frame.height - 1, ceil(min(table.maxY, (near?.y ?: table.maxY) + reach)).toInt())
        if (x1 <= x0 || y1 <= y0) return null

        // Imagem integral da máscara de branco, com margem para as caixas encostadas na borda.
        val ix0 = max(0, x0 - half)
        val iy0 = max(0, y0 - half)
        val w = min(frame.width - 1, x1 + half) - ix0 + 1
        val h = min(frame.height - 1, y1 + half) - iy0 + 1
        val stride = w + 1
        if (integral.size < stride * (h + 1)) integral = IntArray(stride * (h + 1))
        for (i in 0..w) integral[i] = 0
        for (y in 0 until h) {
            val row = (y + 1) * stride
            val src = (iy0 + y) * frame.width + ix0
            var rowSum = 0
            integral[row] = 0
            for (x in 0 until w) {
                if (Frame.isWhite(frame.pixels[src + x])) rowSum++
                integral[row + x + 1] = integral[row - stride + x + 1] + rowSum
            }
        }

        var best = 0
        var bestX = 0
        var bestY = 0
        for (cy in y0..y1 step 2) {
            val top = (cy - half - iy0).coerceIn(0, h) * stride
            val bottom = (cy + half - iy0 + 1).coerceIn(0, h) * stride
            for (cx in x0..x1 step 2) {
                val left = (cx - half - ix0).coerceIn(0, w)
                val right = (cx + half - ix0 + 1).coerceIn(0, w)
                val sum = integral[bottom + right] - integral[top + right] - integral[bottom + left] + integral[top + left]
                if (sum > best) {
                    best = sum
                    bestX = cx
                    bestY = cy
                }
            }
        }
        val side = 2 * half + 1
        if (best < MIN_CUE_FILL * side * side) return null

        var center = Vec2(bestX.toDouble(), bestY.toDouble())
        repeat(2) { center = whiteCentroid(frame, center, 0.85 * r) ?: center }
        return center
    }

    private fun whiteCentroid(frame: Frame, c: Vec2, radius: Double): Vec2? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        val r2 = radius * radius
        for (y in floor(c.y - radius).toInt()..ceil(c.y + radius).toInt()) {
            for (x in floor(c.x - radius).toInt()..ceil(c.x + radius).toInt()) {
                val dx = x - c.x
                val dy = y - c.y
                if (dx * dx + dy * dy <= r2 && frame.isWhite(x, y)) {
                    sx += x
                    sy += y
                    n++
                }
            }
        }
        return if (n > 0) Vec2(sx / n, sy / n) else null
    }

    /**
     * Acerta o centro da branca ao longo da linha de mira: média dos pixels brancos numa janela um
     * pouco maior que a bola (para as bordas dela puxarem de volta para o meio). A linha de mira (e o
     * contorno escuro dela) cobre metade da bola, então ignora uma faixa em volta da reta dos dois lados.
     */
    private fun cueCenterOnLine(frame: Frame, onLine: Vec2, dir: Vec2, r: Double): Vec2 {
        val n = Vec2(-dir.y, dir.x)
        val band = 0.35 * r
        val radius = 1.2 * r
        var center = onLine
        for (iteration in 0 until 8) {
            var sum = 0.0
            var count = 0
            for (y in floor(center.y - radius).toInt()..ceil(center.y + radius).toInt()) {
                for (x in floor(center.x - radius).toInt()..ceil(center.x + radius).toInt()) {
                    val d = Vec2(x - center.x, y - center.y)
                    if (d.length > radius || kotlin.math.abs(d dot n) < band || !frame.isWhite(x, y)) continue
                    sum += d dot dir
                    count++
                }
            }
            if (count == 0) break
            val shift = sum / count
            center += dir * shift
            if (kotlin.math.abs(shift) < 0.1) break
        }
        return center
    }

    /**
     * Procura a linha de mira saindo da branca: o raio (só perto de [aroundDegrees], se informado)
     * que segue um traço branco fino por mais tempo sem interrupção. Testa também raios paralelos
     * deslocados para os lados, porque o centro achado da branca pode estar uns pixels fora da linha
     * (a mão do "bola na mão" cobre parte dela). Depois ajusta a reta no trecho achado e, numa segunda
     * passada estreita, anda até onde a linha do jogo aparece.
     */
    private fun findGuide(frame: Frame, table: Table, cue: Vec2, aroundDegrees: Double?): Line? {
        val r = table.ballRadius
        val start = 1.5 * r
        val minSamples = minGuideSamples(r)
        val degrees = if (aroundDegrees == null) 0 until 360 else {
            val center = aroundDegrees.roundToInt()
            center - HINT_DEGREES..center + HINT_DEGREES
        }
        val offsets = doubleArrayOf(0.0, -0.5 * r, 0.5 * r, -r, r)
        var bestRun = 0.0
        var bestOrigin = cue
        var bestAngle = 0.0
        for (deg in degrees) {
            val angle = Math.toRadians(deg.toDouble())
            val u = Vec2.ofAngle(angle)
            val n = Vec2(-u.y, u.x)
            for (offset in offsets) {
                val from = cue + n * offset
                val run = runLength(frame, table, from, u, start)
                if (run > bestRun) {
                    bestRun = run
                    bestOrigin = from
                    bestAngle = angle
                }
            }
        }
        if (bestRun < minSamples) return null
        // O fim do trecho pode já entrar no anel: deixa a última parte de fora do primeiro ajuste.
        val stopAt = start + max(bestRun - r, 0.6 * bestRun)
        val wide = collectAndFit(frame, table, bestOrigin, Vec2.ofAngle(bestAngle), start, minSamples, stopAt, tolerance = 0.6 * r)
            ?: return null
        return collectAndFit(frame, table, wide.origin, wide.direction, start, minSamples) ?: wide
    }

    /** Até que distância (a partir de [start]) o raio segue um traço branco fino sem interrupção. */
    private fun runLength(frame: Frame, table: Table, origin: Vec2, u: Vec2, start: Double): Double {
        val r = table.ballRadius
        val window = max(1, (0.09 * r).roundToInt())
        val maxCore = max(3, (0.3 * r).roundToInt())
        val gap = max(8.0, 0.45 * r)
        var last = -1.0
        var d = start
        while (true) {
            val px = origin.x + u.x * d
            val py = origin.y + u.y * d
            if (!insideTable(table, px, py)) break
            if (thinWhiteAt(frame, px, py, -u.y, u.x, window, maxCore)) {
                last = d
            } else if (d - (if (last < 0) start else last) > gap) {
                break
            }
            d += 2.0
        }
        return if (last < 0) 0.0 else last - start
    }

    /**
     * Ajusta uma reta no traço branco que sai de perto de [origin] numa direção aproximada. A primeira
     * passada usa uma janela larga (a origem pode estar alguns pixels fora da linha, por exemplo com
     * a mão do "bola na mão" em cima da branca); a segunda, estreita e centrada na primeira reta.
     */
    private fun fitLine(frame: Frame, table: Table, origin: Vec2, approxAngle: Double, start: Double, minSamples: Int): Line? {
        val r = table.ballRadius
        val wide = collectAndFit(frame, table, origin, Vec2.ofAngle(approxAngle), start, minSamples, tolerance = 1.2 * r)
            ?: return null
        return collectAndFit(frame, table, wide.origin, wide.direction, start, minSamples) ?: wide
    }

    /**
     * Anda pela reta, um pixel inteiro por vez no eixo em que ela mais avança, juntando o meio de cada
     * corte do traço (só cortes estreitos, para não misturar o anel ou uma bola) até o traço acabar, e
     * ajusta uma reta por mínimos quadrados. Medir em pixels inteiros evita o erro de arredondamento
     * que entortaria retas quase horizontais ou verticais. O último trecho fica de fora do ajuste:
     * é onde o traço encontra o anel e a linha que sai dele.
     */
    private fun collectAndFit(
        frame: Frame,
        table: Table,
        origin: Vec2,
        u: Vec2,
        start: Double,
        minSamples: Int,
        stopAt: Double = Double.POSITIVE_INFINITY,
        tolerance: Double = max(2.0, 0.15 * table.ballRadius),
    ): Line? {
        val r = table.ballRadius
        val horizontal = kotlin.math.abs(u.x) >= kotlin.math.abs(u.y)
        val major = if (horizontal) u.x else u.y
        val slope = if (horizontal) u.y / u.x else u.x / u.y
        val originMajor = if (horizontal) origin.x else origin.y
        val originMinor = if (horizontal) origin.y else origin.x
        val stretch = 1.0 / kotlin.math.abs(major)
        val tol = (tolerance * stretch).roundToInt()
        val maxCore = (max(3.0, 0.3 * r) * stretch).roundToInt()
        val gap = max(8.0, 0.45 * r)
        fun white(m: Int, k: Int) = if (horizontal) frame.isWhite(m, k) else frame.isWhite(k, m)

        val capacity = (table.width + table.bottom - table.top).toInt() + 1
        val along = DoubleArray(capacity)
        val xs = DoubleArray(capacity)
        val ys = DoubleArray(capacity)
        var points = 0
        var lastWhite = -1.0
        val step = if (major > 0) 1 else -1
        var m = (originMajor + major * start).roundToInt()
        while (true) {
            val d = (m - originMajor) / major
            if (d > stopAt) break
            val minor = originMinor + (m - originMajor) * slope
            val x = if (horizontal) m.toDouble() else minor
            val y = if (horizontal) minor else m.toDouble()
            if (!insideTable(table, x, y)) break
            val c = minor.roundToInt()
            val hit = (0..2 * tol).map { if (it % 2 == 0) c + it / 2 else c - (it + 1) / 2 }.firstOrNull { white(m, it) }
            if (hit != null) {
                lastWhite = d
                var lo = hit
                while (hit - lo <= maxCore && white(m, lo - 1)) lo--
                var hi = hit
                while (hi - lo < maxCore && white(m, hi + 1)) hi++
                if (hi - lo + 1 <= maxCore && points < capacity) {
                    val center = (lo + hi) / 2.0
                    along[points] = d
                    xs[points] = if (horizontal) m.toDouble() else center
                    ys[points] = if (horizontal) center else m.toDouble()
                    points++
                }
            } else if (d - max(lastWhite, start) > gap) {
                break
            }
            m += step
        }
        if (lastWhite < 0 || points < minSamples) return null

        val cut = if (stopAt.isInfinite()) lastWhite - 1.3 * r else stopAt
        val useAll = (0 until points).count { along[it] <= cut } < minSamples
        val keep = BooleanArray(points) { useAll || along[it] <= cut }
        var mean = Vec2.ZERO
        var v = u
        // Ajusta, descarta os pontos que ficaram longe da reta (pedaços de outras coisas) e ajusta de novo.
        for (round in 0 until 3) {
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var syy = 0.0
            var sxy = 0.0
            var used = 0
            for (i in 0 until points) {
                if (!keep[i]) continue
                sx += xs[i]
                sy += ys[i]
                sxx += xs[i] * xs[i]
                syy += ys[i] * ys[i]
                sxy += xs[i] * ys[i]
                used++
            }
            if (used < minSamples) return null
            val mx = sx / used
            val my = sy / used
            val cxx = sxx / used - mx * mx
            val cyy = syy / used - my * my
            val cxy = sxy / used - mx * my
            mean = Vec2(mx, my)
            v = Vec2.ofAngle(0.5 * atan2(2 * cxy, cxx - cyy))
            if ((v dot u) < 0) v = Vec2(-v.x, -v.y)
            var dropped = false
            for (i in 0 until points) {
                if (keep[i] && kotlin.math.abs((xs[i] - mx) * v.y - (ys[i] - my) * v.x) > OUTLIER_PX) {
                    keep[i] = false
                    dropped = true
                }
            }
            if (!dropped) break
        }
        val projected = mean + v * ((origin - mean) dot v)
        return Line(projected, v, origin + u * lastWhite)
    }

    /**
     * Há um traço branco fino passando a até [window] pixels do ponto, atravessando na direção
     * (nx, ny)? Fino quer dizer faixa branca estreita: o taco, mesmo claro, é bem mais largo que as
     * linhas de mira.
     */
    private fun thinWhiteAt(frame: Frame, px: Double, py: Double, nx: Double, ny: Double, window: Int, maxCore: Int): Boolean {
        for (i in 0..2 * window) {
            val k = if (i % 2 == 0) i / 2 else -(i + 1) / 2
            if (!frame.isWhite(px + nx * k, py + ny * k)) continue
            var lo = k
            while (k - lo <= maxCore && frame.isWhite(px + nx * (lo - 1), py + ny * (lo - 1))) lo--
            var hi = k
            while (hi - lo < maxCore && frame.isWhite(px + nx * (hi + 1), py + ny * (hi + 1))) hi++
            return hi - lo + 1 <= maxCore
        }
        return false
    }

    /** O anel do ponto de choque fica perto do fim da linha; procura onde um círculo do raio da bola é mais branco. */
    private fun findGhost(frame: Frame, cue: Vec2, dir: Vec2, guideLength: Double, r: Double): Vec2? {
        val from = max(2.0 * r, guideLength - 2.0 * r)
        val to = guideLength + 1.5 * r
        val count = (to - from).toInt() + 1
        if (count <= 0) return null
        val back = Vec2(-dir.x, -dir.y)
        val scores = DoubleArray(count) { i -> ringScore(frame, cue + dir * (from + i), r, back) }
        val best = scores.max()
        if (best < MIN_RING_SCORE) return null
        var sum = 0.0
        var n = 0
        scores.forEachIndexed { i, s ->
            if (s >= best - 0.03) {
                sum += i
                n++
            }
        }
        return cue + dir * (from + sum / n)
    }

    private fun ringScore(frame: Frame, c: Vec2, r: Double, back: Vec2): Double {
        var hits = 0
        var total = 0
        for (i in 0 until RING_SAMPLES) {
            val ux = RING_COS[i]
            val uy = RING_SIN[i]
            // Por trás entra a própria linha de mira, que é branca de qualquer jeito.
            if (ux * back.x + uy * back.y > RING_SKIP_COS) continue
            total++
            if (frame.isWhite(c.x + ux * (r - 2), c.y + uy * (r - 2)) ||
                frame.isWhite(c.x + ux * r, c.y + uy * r) ||
                frame.isWhite(c.x + ux * (r + 2), c.y + uy * (r + 2))
            ) {
                hits++
            }
        }
        return hits.toDouble() / total
    }

    /**
     * A bola alvo encosta no anel, na frente da mira. Acha o lado onde há uma bola (pixels bem
     * diferentes do pano) e, se o jogo desenhou a linha que sai dela, usa essa linha como direção exata.
     */
    private fun findObjectBall(frame: Frame, table: Table, ghost: Vec2, aim: Vec2): Line? {
        val r = table.ballRadius
        val cloth = clothColor(frame, ghost, r)
        val aimAngle = atan2(aim.y, aim.x)
        val scores = DoubleArray(OBJECT_CANDIDATES) { i ->
            val angle = aimAngle + Math.toRadians(OBJECT_STEP_DEG * (i - OBJECT_CANDIDATES / 2))
            ballScore(frame, ghost + Vec2.ofAngle(angle) * (2 * r), 0.6 * r, cloth)
        }
        val iBest = scores.indices.maxBy { scores[it] }
        val best = scores[iBest]
        if (best < MIN_BALL_SCORE) return null
        // O disco de teste cabe inteiro na bola por alguns graus para cada lado: fica com o meio.
        var lo = iBest
        while (lo > 0 && scores[lo - 1] >= best * 0.95) lo--
        var hi = iBest
        while (hi < scores.size - 1 && scores[hi + 1] >= best * 0.95) hi++
        val coarse = aimAngle + Math.toRadians(OBJECT_STEP_DEG * ((lo + hi) / 2.0 - OBJECT_CANDIDATES / 2))

        // A linha da bola alvo é colinear com o anel: começa no centro da bola (2 raios do anel).
        val line = fitLine(frame, table, ghost, coarse, 3.15 * r, max(6, (0.6 * r).toInt()))
        val direction = line?.direction ?: Vec2.ofAngle(coarse)
        val center = ghost + direction * (2 * r)
        return Line(center, direction, line?.end ?: center)
    }

    /** Cor do pano em volta do anel: mediana de um anel de amostras (a bola alvo ocupa só uma parte dele). */
    private fun clothColor(frame: Frame, c: Vec2, r: Double): Int {
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        for (radius in doubleArrayOf(1.3 * r, 1.5 * r, 1.7 * r)) {
            for (i in 0 until RING_SAMPLES) {
                val x = (c.x + RING_COS[i] * radius).roundToInt()
                val y = (c.y + RING_SIN[i] * radius).roundToInt()
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                val p = frame.pixel(x, y)
                reds += Frame.red(p)
                greens += Frame.green(p)
                blues += Frame.blue(p)
            }
        }
        if (reds.isEmpty()) return Frame.rgb(0, 0, 0)
        return Frame.rgb(reds.sorted()[reds.size / 2], greens.sorted()[greens.size / 2], blues.sorted()[blues.size / 2])
    }

    private fun ballScore(frame: Frame, c: Vec2, radius: Double, cloth: Int): Double {
        var ball = 0
        var total = 0
        val r2 = radius * radius
        for (y in floor(c.y - radius).toInt()..ceil(c.y + radius).toInt()) {
            for (x in floor(c.x - radius).toInt()..ceil(c.x + radius).toInt()) {
                val dx = x - c.x
                val dy = y - c.y
                if (dx * dx + dy * dy > r2) continue
                total++
                if (x in 0 until frame.width && y in 0 until frame.height && Frame.distance(frame.pixel(x, y), cloth) > BALL_COLOR_DISTANCE) {
                    ball++
                }
            }
        }
        return if (total == 0) 0.0 else ball.toDouble() / total
    }

    private fun minGuideSamples(r: Double) = max(12.0, 1.5 * r).toInt()

    private fun insideTable(table: Table, x: Double, y: Double) =
        x >= table.left && x <= table.right && y >= table.top && y <= table.bottom

    private fun insideInner(table: Table, p: Vec2, margin: Double) =
        p.x >= table.minX + margin && p.x <= table.maxX - margin && p.y >= table.minY + margin && p.y <= table.maxY - margin

    private companion object {
        /** Fração do quadrado central que precisa ser branca para ser a bola branca. */
        const val MIN_CUE_FILL = 0.6

        /** Quanto a linha pode ter girado desde o quadro anterior. */
        const val HINT_DEGREES = 15

        /** Distância máxima (px) de um ponto até a reta ajustada para continuar no ajuste. */
        const val OUTLIER_PX = 1.5
        const val MIN_RING_SCORE = 0.55
        const val RING_SAMPLES = 48
        val RING_SKIP_COS = cos(Math.toRadians(25.0))
        val RING_COS = DoubleArray(RING_SAMPLES) { cos(2 * PI * it / RING_SAMPLES) }
        val RING_SIN = DoubleArray(RING_SAMPLES) { sin(2 * PI * it / RING_SAMPLES) }
        const val OBJECT_CANDIDATES = 89
        const val OBJECT_STEP_DEG = 2.0
        const val MIN_BALL_SCORE = 0.6
        const val BALL_COLOR_DISTANCE = 60
    }
}
