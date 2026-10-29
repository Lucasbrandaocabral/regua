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
 * Usa só o que o 8 Ball Pool desenha: a branca é a bola branca de onde sai uma linha; as linhas de
 * mira são traços brancos finos (com contorno escuro) e o ponto de choque é um anel branco do tamanho
 * da bola.
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
        /** Centros das outras bolas paradas na mesa (sem a branca e sem a bola alvo). */
        val balls: List<Vec2> = emptyList(),
    )

    /** Reta ajustada num traço: passa por [origin] na direção [direction] e o traço acaba em [end]. */
    private class Line(val origin: Vec2, val direction: Vec2, val end: Vec2)

    /**
     * A linha de mira achada, o centro da branca nela, o anel onde ela termina (se houver) e o centro
     * da branca achado só pelo branco dela.
     */
    private class Aim(val guide: Line, val cue: Vec2, val ring: Vec2?, val rough: Vec2)

    /** Um raio testado na busca da linha: até onde ele segue um traço fino a partir da branca. */
    private class Ray(val run: Double, val degrees: Int, val origin: Vec2)

    private var integral = IntArray(0)
    private val ballFinder = BallFinder()
    private var cachedBalls = emptyList<Vec2>()
    private var cachedBallsCue: Vec2? = null
    private var framesSinceBalls = 0

    /**
     * [previous] é o resultado do quadro anterior: a branca quase nunca muda de lugar enquanto se mira
     * e a linha gira pouco de um quadro para o outro, então procura primeiro perto deles (bem mais
     * rápido) e só varre a mesa inteira se não achar.
     */
    fun detect(frame: Frame, table: Table, findObjectBall: Boolean = true, previous: Result? = null): Result? {
        val r = table.ballRadius
        if (r < 3.0) return null
        val hint = previous?.let { Math.toDegrees(atan2(it.direction.y, it.direction.x)) }
        val aim = findAim(frame, table, previous?.cue, hint) ?: return null
        var direction = aim.guide.direction
        var cue = aim.cue
        var guideLength = (aim.guide.end - cue) dot direction
        var ring = aim.ring
        if (ring != null) {
            // Dentro do anel a linha se mistura com ele e com a linha de desvio da branca: refaz o
            // ajuste só com o trecho limpo, antes do anel.
            val ringDistance = (ring - cue) dot direction
            val clean = collectAndFit(frame, table, cue, direction, 1.5 * r, minGuideSamples(r).toDouble(), stopAt = ringDistance - 1.5 * r)
            if (clean != null) {
                direction = clean.direction
                cue = cueCenterOnLine(frame, clean.origin, direction, r)
                ring = findGhost(frame, cue, direction, ringDistance, r) ?: ring
                // A bola fantasma fica na reta da mira, que aqui é mais precisa que o anel.
                val center = ringCenter(frame, direction, ring, r)
                ring = cue + direction * ((center - cue) dot direction)
            } else {
                // Anel quase encostado na branca: o traço é curto demais para dar a direção, que passa
                // a ser a do centro da branca ao centro do anel. A linha achada pode ser um raio torto
                // que também chega no anel, então o centro da branca sai do branco dela, não da linha.
                ring = ringCenter(frame, direction, ring, r)
                cue = cueOnShortGuide(frame, table, aim.rough, ring, r)
                direction = (ring - cue).normalized()
            }
            guideLength = (ring - cue) dot direction
        }
        // Anel colado na tabela é a branca batendo na tabela, não numa bola.
        val ghost = ring?.takeIf { insideInner(table, it, 0.6 * r) }
        val objectBall = if (ghost != null && findObjectBall) findObjectBall(frame, table, ghost, direction) else null
        val balls = otherBalls(frame, table, cue).filter { b ->
            b.distanceTo(cue) > CUE_EXCLUSION * r &&
                objectBall?.let { b.distanceTo(it.origin) > 1.2 * r } != false &&
                ghost?.let { b.distanceTo(it) > 1.6 * r } != false &&
                !onCueStick(b, cue, direction, r)
        }
        return Result(
            cue = cue,
            direction = direction,
            guideLength = guideLength,
            ghost = ghost,
            objectBall = objectBall?.origin,
            objectGuideLength = objectBall?.let { (it.end - it.origin) dot it.direction } ?: 0.0,
            balls = balls,
        )
    }

    /**
     * O taco fica atrás da branca, na direção oposta à mira; tacos enfeitados têm pedaços do tamanho
     * de uma bola que não podem virar obstáculo.
     */
    private fun onCueStick(b: Vec2, cue: Vec2, direction: Vec2, r: Double): Boolean {
        val d = b - cue
        val behind = -(d dot direction)
        val across = kotlin.math.abs(d.x * direction.y - d.y * direction.x)
        return behind > 0 && across < 1.3 * r
    }

    /**
     * As bolas só mudam de lugar depois de uma tacada (e aí a branca também muda), então enquanto a
     * branca está parada reaproveita a última busca e só refaz de vez em quando.
     */
    private fun otherBalls(frame: Frame, table: Table, cue: Vec2): List<Vec2> {
        val lastCue = cachedBallsCue
        if (lastCue != null && lastCue.distanceTo(cue) < 3.0 && framesSinceBalls < BALL_REFRESH_FRAMES) {
            framesSinceBalls++
            return cachedBalls
        }
        cachedBalls = ballFinder.find(frame, table)
        cachedBallsCue = cue
        framesSinceBalls = 0
        return cachedBalls
    }

    /**
     * Lugares onde um quadrado do tamanho do miolo da bola fica cheio de branco, do mais cheio para o
     * menos (até [MAX_CUE_CANDIDATES], a pelo menos 1,5 raio um do outro). A branca é um deles, mas
     * as listradas com o lado branco para cima também entram, e às vezes com mais branco que ela.
     */
    private fun cueCandidates(frame: Frame, table: Table): List<Vec2> {
        val r = table.ballRadius
        val half = max(2, (0.6 * r).roundToInt())
        val x0 = max(0, table.minX.toInt())
        val x1 = min(frame.width - 1, ceil(table.maxX).toInt())
        val y0 = max(0, table.minY.toInt())
        val y1 = min(frame.height - 1, ceil(table.maxY).toInt())
        if (x1 <= x0 || y1 <= y0) return emptyList()

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

        // Caixas cheias o bastante, como (soma, posição) num Long para ordenar sem criar objetos;
        // no empate fica a que vem antes na varredura.
        val side = 2 * half + 1
        val minSum = MIN_CUE_FILL * side * side
        val columns = (x1 - x0) / 2 + 1
        var full = LongArray(64)
        var count = 0
        for (cy in y0..y1 step 2) {
            val top = (cy - half - iy0).coerceIn(0, h) * stride
            val bottom = (cy + half - iy0 + 1).coerceIn(0, h) * stride
            for (cx in x0..x1 step 2) {
                val left = (cx - half - ix0).coerceIn(0, w)
                val right = (cx + half - ix0 + 1).coerceIn(0, w)
                val sum = integral[bottom + right] - integral[top + right] - integral[bottom + left] + integral[top + left]
                if (sum < minSum) continue
                if (count == full.size) full = full.copyOf(2 * count)
                val index = (cy - y0) / 2 * columns + (cx - x0) / 2
                full[count++] = (sum.toLong() shl 32) or (Int.MAX_VALUE - index).toLong()
            }
        }
        full.sort(0, count)

        val peaks = ArrayList<Vec2>()
        for (i in count - 1 downTo 0) {
            val index = Int.MAX_VALUE - (full[i] and 0xFFFFFFFFL).toInt()
            val p = Vec2((x0 + index % columns * 2).toDouble(), (y0 + index / columns * 2).toDouble())
            if (peaks.any { it.distanceTo(p) < 1.5 * r }) continue
            peaks += p
            if (peaks.size == MAX_CUE_CANDIDATES) break
        }
        return peaks.map { peak ->
            var center = peak
            repeat(2) { center = whiteCentroid(frame, center, 0.85 * r) ?: center }
            center
        }
    }

    /**
     * Teste barato antes da busca da linha: uma linha que sai da bola cruza pelo menos dois dos
     * círculos de 1,5 a 2,4 raios em volta dela. Não todos: com o anel perto da branca, um deles pode
     * cair inteiro no contorno escuro do anel. Fora da mesa não conta (os pontos brancos das tabelas).
     */
    private fun lineLeaves(frame: Frame, table: Table, c: Vec2, r: Double): Boolean {
        val x0 = table.left.toInt() - 2
        val x1 = ceil(table.right).toInt() + 2
        val y0 = table.top.toInt() - 2
        val y1 = ceil(table.bottom).toInt() + 2
        var crossed = 0
        for (radius in doubleArrayOf(1.5 * r, 1.8 * r, 2.1 * r, 2.4 * r)) {
            val n = (2 * PI * radius).toInt() + 1
            for (i in 0 until n) {
                val a = 2 * PI * i / n
                val x = (c.x + cos(a) * radius).roundToInt()
                val y = (c.y + sin(a) * radius).roundToInt()
                if (x in x0..x1 && y in y0..y1 && frame.isWhite(x, y)) {
                    crossed++
                    break
                }
            }
        }
        return crossed >= 2
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
     * A branca é a bola branca de onde sai a linha de mira: das listradas com o lado branco para cima
     * não sai linha. Se mais de uma tiver linha (a da bola alvo volta pela mira numa tacada reta),
     * vale a que termina num anel oco; um traço curto só vale se terminar nele. Se nenhuma linha
     * achada acaba num anel, procura um anel quase encostado numa delas: com ele tão perto, o traço
     * até ele é curto demais para a busca pela linha. [near] é a branca do quadro anterior (testada
     * primeiro) e [hintDegrees] a direção da mira nele.
     */
    private fun findAim(frame: Frame, table: Table, near: Vec2?, hintDegrees: Double?): Aim? {
        val r = table.ballRadius
        var candidates = cueCandidates(frame, table).filter { lineLeaves(frame, table, it, r) }
        if (near != null) candidates = candidates.sortedBy { it.distanceTo(near) > 3 * r }
        var fallback: Aim? = null
        // Primeiro perto da direção do quadro anterior; se isso não der numa linha que acaba num anel
        // (a dica pode cair noutro traço branco, como o de uma régua velha), varre todas as direções.
        val searches = if (hintDegrees != null) listOf(hintDegrees, null) else listOf(null)
        for (rough in candidates) {
            for (around in searches) {
                val guide = findGuide(frame, table, rough, around) ?: continue
                val cue = cueCenterOnLine(frame, guide.origin, guide.direction, r)
                val length = (guide.end - cue) dot guide.direction
                val ring = findGhost(frame, cue, guide.direction, length, r)
                if (ring != null && whiteFraction(frame, ring, 0.5 * r) < HOLLOW_RING) return Aim(guide, cue, ring, rough)
                if (fallback == null && length - 1.5 * r >= minGuideSamples(r)) fallback = Aim(guide, cue, ring, rough)
            }
        }
        for (rough in candidates) {
            val close = findCloseRing(frame, rough, r) ?: continue
            val u = (close - rough).normalized()
            return Aim(Line(rough, u, close), cueCenterOnLine(frame, rough, u, r), close, rough)
        }
        return fallback
    }

    /**
     * Procura a linha de mira saindo da branca: os raios (só perto de [aroundDegrees], se informado)
     * que seguem um traço branco fino por mais tempo sem interrupção, do mais comprido para o menos.
     * Testa também raios paralelos deslocados para os lados, porque o centro achado da branca pode
     * estar uns pixels fora da linha (a mão do "bola na mão" cobre parte dela). Em cada raio ajusta a
     * reta no trecho achado e, numa segunda passada estreita, anda até onde a linha do jogo aparece;
     * vale o primeiro que dá uma reta passando pela branca. Com o anel quase encostado na branca o
     * traço é curto, e um raio torto que cruza o anel e a linha da tangente pode ir mais longe que ele.
     */
    private fun findGuide(frame: Frame, table: Table, cue: Vec2, aroundDegrees: Double?): Line? {
        val r = table.ballRadius
        val start = 1.5 * r
        val degrees = if (aroundDegrees == null) 0 until 360 else {
            val center = aroundDegrees.roundToInt()
            center - HINT_DEGREES..center + HINT_DEGREES
        }
        // Com a dica do quadro anterior a branca já está bem centrada na linha.
        val offsets = if (aroundDegrees == null) doubleArrayOf(0.0, -0.5 * r, 0.5 * r, -r, r) else doubleArrayOf(0.0)
        val rays = ArrayList<Ray>()
        for (deg in degrees) {
            val u = Vec2.ofAngle(Math.toRadians(deg.toDouble()))
            val n = Vec2(-u.y, u.x)
            for (offset in offsets) {
                val from = cue + n * offset
                rays += Ray(runLength(frame, table, from, u, start), deg, from)
            }
        }
        rays.sortByDescending { it.run }
        val minRun = max(6.0, SHORT_GUIDE * r)
        val tried = ArrayList<Int>()
        for (ray in rays) {
            if (ray.run < minRun || tried.size == GUIDE_TRIES) break
            if (tried.any { kotlin.math.abs(((ray.degrees - it) % 360 + 540) % 360 - 180) <= GUIDE_TRY_SPREAD }) continue
            tried += ray.degrees
            // Traço curto: o cruzamento com o anel nunca é estreito e não entra na conta.
            val minSamples = min(minGuideSamples(r).toDouble(), max(4.0, 0.3 * ray.run))
            val u = Vec2.ofAngle(Math.toRadians(ray.degrees.toDouble()))
            // O fim do trecho pode já entrar no anel: deixa a última parte de fora do primeiro ajuste.
            val stopAt = start + max(ray.run - r, 0.6 * ray.run)
            val wide = collectAndFit(frame, table, ray.origin, u, start, minSamples, stopAt, tolerance = 0.6 * r) ?: continue
            val line = collectAndFit(frame, table, wide.origin, wide.direction, start, minSamples) ?: wide
            // A linha do jogo sai do centro da branca (que a mão do "bola na mão" pode tirar até um
            // raio do lugar), e a reta ajustada tem de seguir o traço desde a borda dela (um ajuste
            // que escorregou para a linha da tangente não segue).
            val off = cue - line.origin
            if (kotlin.math.abs(off.x * line.direction.y - off.y * line.direction.x) <= 1.2 * r &&
                runLength(frame, table, line.origin, line.direction, start) >= minRun
            ) {
                return line
            }
        }
        return null
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
        // Tolera buracos maiores: uma linha da própria régua pode cruzar a do jogo.
        val gap = max(10.0, r)
        val wide = collectAndFit(frame, table, origin, Vec2.ofAngle(approxAngle), start, minSamples.toDouble(), tolerance = 1.2 * r, gap = gap)
            ?: return null
        return collectAndFit(frame, table, wide.origin, wide.direction, start, minSamples.toDouble(), gap = gap) ?: wide
    }

    /**
     * Anda pela reta, um pixel inteiro por vez no eixo em que ela mais avança, juntando o meio de cada
     * corte do traço (só cortes estreitos, para não misturar o anel ou uma bola) até o traço acabar, e
     * ajusta uma reta por mínimos quadrados. Medir em pixels inteiros evita o erro de arredondamento
     * que entortaria retas quase horizontais ou verticais. O último trecho fica de fora do ajuste:
     * é onde o traço encontra o anel e a linha que sai dele. [minLength] é o mínimo de traço, em pixels.
     */
    private fun collectAndFit(
        frame: Frame,
        table: Table,
        origin: Vec2,
        u: Vec2,
        start: Double,
        minLength: Double,
        stopAt: Double = Double.POSITIVE_INFINITY,
        tolerance: Double = max(2.0, 0.15 * table.ballRadius),
        gap: Double = max(8.0, 0.45 * table.ballRadius),
    ): Line? {
        val r = table.ballRadius
        val horizontal = kotlin.math.abs(u.x) >= kotlin.math.abs(u.y)
        val major = if (horizontal) u.x else u.y
        // Um corte por pixel do eixo principal: na diagonal são só ~0,7 cortes por pixel de traço.
        val minSamples = max(3, ceil(minLength * kotlin.math.abs(major)).toInt())
        val slope = if (horizontal) u.y / u.x else u.x / u.y
        val originMajor = if (horizontal) origin.x else origin.y
        val originMinor = if (horizontal) origin.y else origin.x
        val stretch = 1.0 / kotlin.math.abs(major)
        val tol = (tolerance * stretch).roundToInt()
        val maxCore = (max(3.0, 0.3 * r) * stretch).roundToInt()
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
     * Anel quase encostado na branca, quando o traço até ele é curto demais para a busca pela linha:
     * o círculo oco do tamanho da bola mais branco a até [CLOSE_RING] raios do centro [c], com a
     * linha da mira chegando no centro dele.
     */
    private fun findCloseRing(frame: Frame, c: Vec2, r: Double): Vec2? {
        var best = 0.0
        var bestRing = c
        var angle = 0.0
        while (angle < 360.0) {
            val u = Vec2.ofAngle(Math.toRadians(angle))
            // Sem o lado virado para a branca, por onde entra a linha.
            val back = Vec2(-u.x, -u.y)
            var rho = 2.2 * r
            while (rho < CLOSE_RING * r) {
                val g = c + u * rho
                val score = ringScore(frame, g, r, back)
                if (score > best) {
                    best = score
                    bestRing = g
                }
                rho += 1.0
            }
            angle += CLOSE_RING_STEP_DEG
        }
        if (best < MIN_RING_SCORE || whiteFraction(frame, bestRing, 0.5 * r) >= HOLLOW_RING) return null
        val ring = ringCenter(frame, (bestRing - c).normalized(), bestRing, r)
        // Dentro do anel, a linha da mira vem da branca até o centro.
        val u = (c - ring).normalized()
        val window = max(1, (0.15 * r).roundToInt())
        val maxCore = max(3, (0.3 * r).roundToInt())
        var on = 0
        var total = 0
        var s = 0.25 * r
        while (s < 0.75 * r) {
            total++
            if (thinWhiteAt(frame, ring.x + u.x * s, ring.y + u.y * s, -u.y, u.x, window, maxCore)) on++
            s += 1.0
        }
        return ring.takeIf { on >= 0.7 * total }
    }

    /**
     * Centro do anel com fração de pixel: círculo ajustado ao ponto mais branco de cada raio (sem o
     * lado de onde vem a linha, que tem a direção [dir]). Com o anel quase encostado na branca, a
     * direção da mira sai daqui. Se o ajuste não convencer, devolve [ghost].
     */
    private fun ringCenter(frame: Frame, dir: Vec2, ghost: Vec2, r: Double): Vec2 {
        val ss = DoubleArray(ceil(0.7 * r / 0.25).toInt()) { 0.7 * r + 0.25 * it }
        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        for (i in 0 until RING_SAMPLES) {
            val ux = RING_COS[i]
            val uy = RING_SIN[i]
            if (-(ux * dir.x + uy * dir.y) > RING_SKIP_COS) continue
            val w = DoubleArray(ss.size) { whiteness(frame, ghost.x + ux * ss[it], ghost.y + uy * ss[it]) }
            val peak = ss[w.indices.maxBy { w[it] }]
            // Só em volta do pico: as linhas que cruzam o anel são brancas no raio todo.
            var sum = 0.0
            var total = 0.0
            for (k in ss.indices) {
                if (kotlin.math.abs(ss[k] - peak) > 1.5) continue
                val weight = max(0.0, w[k] - WHITE_BASE)
                sum += weight * ss[k]
                total += weight
            }
            if (total <= 0.0) continue
            px += ghost.x + ux * sum / total
            py += ghost.y + uy * sum / total
        }
        val keep = BooleanArray(px.size) { true }
        var cx = 0.0
        var cy = 0.0
        var fitR = 0.0
        for (round in 0 until 3) {
            val n = keep.count { it }
            if (n < RING_SAMPLES / 3) return ghost
            // Círculo por mínimos quadrados (Kåsa), com as coordenadas centradas na média.
            var mx = 0.0
            var my = 0.0
            for (i in px.indices) if (keep[i]) {
                mx += px[i]
                my += py[i]
            }
            mx /= n
            my /= n
            var suu = 0.0
            var svv = 0.0
            var suv = 0.0
            var suuu = 0.0
            var svvv = 0.0
            var suvv = 0.0
            var svuu = 0.0
            for (i in px.indices) if (keep[i]) {
                val a = px[i] - mx
                val b = py[i] - my
                suu += a * a
                svv += b * b
                suv += a * b
                suuu += a * a * a
                svvv += b * b * b
                suvv += a * b * b
                svuu += b * a * a
            }
            val det = suu * svv - suv * suv
            if (kotlin.math.abs(det) < 1e-9) return ghost
            val e1 = 0.5 * (suuu + suvv)
            val e2 = 0.5 * (svvv + svuu)
            val uc = (e1 * svv - e2 * suv) / det
            val vc = (e2 * suu - e1 * suv) / det
            cx = mx + uc
            cy = my + vc
            fitR = kotlin.math.sqrt(uc * uc + vc * vc + (suu + svv) / n)
            var dropped = false
            for (i in px.indices) {
                if (keep[i] && kotlin.math.abs(kotlin.math.hypot(px[i] - cx, py[i] - cy) - fitR) > RING_FIT_PX) {
                    keep[i] = false
                    dropped = true
                }
            }
            if (!dropped) break
        }
        val center = Vec2(cx, cy)
        return if (kotlin.math.abs(fitR - r) > 0.2 * r || center.distanceTo(ghost) > 0.5 * r) ghost else center
    }

    /** Brancura (o menor canal de cor) num ponto com fração de pixel, por interpolação bilinear. */
    private fun whiteness(frame: Frame, x: Double, y: Double): Double {
        val x0 = floor(x).toInt().coerceIn(0, frame.width - 2)
        val y0 = floor(y).toInt().coerceIn(0, frame.height - 2)
        val fx = (x - x0).coerceIn(0.0, 1.0)
        val fy = (y - y0).coerceIn(0.0, 1.0)
        fun at(dx: Int, dy: Int): Double {
            val p = frame.pixel(x0 + dx, y0 + dy)
            return minOf(Frame.red(p), Frame.green(p), Frame.blue(p)).toDouble()
        }
        return at(0, 0) * (1 - fx) * (1 - fy) + at(1, 0) * fx * (1 - fy) + at(0, 1) * (1 - fx) * fy + at(1, 1) * fx * fy
    }

    /** Fração branca de um disco: o anel é oco, a branca e as outras bolas no fim da linha são cheias. */
    private fun whiteFraction(frame: Frame, c: Vec2, radius: Double): Double {
        var white = 0
        var total = 0
        val r2 = radius * radius
        for (y in floor(c.y - radius).toInt()..ceil(c.y + radius).toInt()) {
            for (x in floor(c.x - radius).toInt()..ceil(c.x + radius).toInt()) {
                val dx = x - c.x
                val dy = y - c.y
                if (dx * dx + dy * dy > r2 || x !in 0 until frame.width || y !in 0 until frame.height) continue
                total++
                if (frame.isWhite(x, y)) white++
            }
        }
        return if (total == 0) 0.0 else white.toDouble() / total
    }

    /**
     * Centro da branca na reta do traço curto entre ela e o anel. O centro achado pelo branco sai um
     * pouco do lugar (a linha e o contorno dela cobrem parte da bola) e, com o anel tão perto, esse
     * desvio de lado vira graus na direção da mira.
     */
    private fun cueOnShortGuide(frame: Frame, table: Table, cue: Vec2, ring: Vec2, r: Double): Vec2 {
        val u = (ring - cue).normalized()
        val line = collectAndFit(frame, table, cue, u, 0.5 * r, 4.0, stopAt = ring.distanceTo(cue) - 0.25 * r, tolerance = 0.3 * r)
            ?: return cue
        return cueCenterOnLine(frame, line.origin, line.direction, r)
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
        val minSamples = max(6, (0.6 * r).toInt())
        val line = fitLine(frame, table, ghost, coarse, 3.15 * r, minSamples)
        var direction = line?.direction ?: Vec2.ofAngle(coarse)
        if (line != null) {
            // Reta passando pelo centro do anel: o braço até o traço é bem mais comprido que o próprio
            // traço (curto nos cortes finos), então o ângulo sai bem mais firme.
            val start = ghost + direction * (2 * r)
            val refined = lineThroughPoint(ghost, strokeCenters(frame, table, start, direction, 1.2 * r, ((line.end - start) dot direction) - 2), minSamples)
            if (refined != null) direction = if ((refined dot direction) > 0) refined else Vec2(-refined.x, -refined.y)
        }
        val center = ghost + direction * (2 * r)
        return Line(center, direction, line?.end ?: center)
    }

    /**
     * Centro do traço branco com fração de pixel num corte perpendicular a cada pixel entre [from] e
     * [to] (medidos de [origin] na direção [dir]): média da brancura no corte, porque a borda do traço
     * é suavizada.
     */
    private fun strokeCenters(frame: Frame, table: Table, origin: Vec2, dir: Vec2, from: Double, to: Double): List<Vec2> {
        val reach = max(3.0, 0.35 * table.ballRadius)
        val steps = (2 * reach / 0.25).toInt()
        val points = ArrayList<Vec2>()
        var s = from
        while (s < to) {
            val cx = origin.x + dir.x * s
            val cy = origin.y + dir.y * s
            var sum = 0.0
            var total = 0.0
            for (i in 0..steps) {
                val k = -reach + 0.25 * i
                val weight = max(0.0, whiteness(frame, cx - dir.y * k, cy + dir.x * k) - WHITE_BASE)
                sum += weight * k
                total += weight
            }
            if (total > 0.0) {
                val k = sum / total
                points += Vec2(cx - dir.y * k, cy + dir.x * k)
            }
            s += 1.0
        }
        return points
    }

    /** Direção da reta por [p] mais perto de [points] (mínimos quadrados perpendiculares), ou null com poucos pontos. */
    private fun lineThroughPoint(p: Vec2, points: List<Vec2>, minPoints: Int): Vec2? {
        if (points.size < minPoints) return null
        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        for (q in points) {
            val dx = q.x - p.x
            val dy = q.y - p.y
            sxx += dx * dx
            syy += dy * dy
            sxy += dx * dy
        }
        return Vec2.ofAngle(0.5 * atan2(2 * sxy, sxx - syy))
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

        /** Bolas brancas testadas (as listradas com o lado branco para cima também são). */
        const val MAX_CUE_CANDIDATES = 6

        /** Raio (em raios de bola) em volta da branca onde não procura outras bolas (a mão do jogo fica ali). */
        const val CUE_EXCLUSION = 2.2

        /** De quantos em quantos quadros refaz a busca das outras bolas com a branca parada. */
        const val BALL_REFRESH_FRAMES = 8

        /** Quanto a linha pode ter girado desde o quadro anterior. */
        const val HINT_DEGREES = 15

        /** Traço mínimo da mira, em raios (com o anel quase encostado na branca). */
        const val SHORT_GUIDE = 0.6

        /** Raios de direções diferentes testados na busca da linha. */
        const val GUIDE_TRIES = 4

        /** Graus: raios mais perto que isso de um já testado são o mesmo traço. */
        const val GUIDE_TRY_SPREAD = 3

        /** Fração branca máxima no miolo do anel (a branca ou outra bola no fim da linha é cheia). */
        const val HOLLOW_RING = 0.4

        /** Em raios: até onde procura um anel quase encostado na branca. */
        const val CLOSE_RING = 3.6
        const val CLOSE_RING_STEP_DEG = 2.0

        /** Brancura (menor canal de cor) do pano mais claro; acima disso conta como traço. */
        const val WHITE_BASE = 150.0

        /** Pontos do anel mais longe que isso (px) do círculo ajustado saem do ajuste. */
        const val RING_FIT_PX = 0.6

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
