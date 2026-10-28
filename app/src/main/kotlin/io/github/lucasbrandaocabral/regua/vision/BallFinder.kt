package io.github.lucasbrandaocabral.regua.vision

import io.github.lucasbrandaocabral.regua.geometry.Table
import io.github.lucasbrandaocabral.regua.geometry.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Acha as bolas paradas na mesa (inclusive a branca e a bola alvo; quem chama filtra).
 *
 * Primeiro estima a cor do pano em cada ponto ([Cloth]) e depois procura discos do tamanho da bola
 * com cor bem diferente dele. Linhas, anéis e o taco são finos demais para contar como bola.
 */
class BallFinder {

    private var mask = BooleanArray(0)
    private var integral = IntArray(0)
    private var cloth: Cloth? = null
    private var clothTable: Table? = null
    private var findsSinceCloth = 0

    fun find(frame: Frame, table: Table): List<Vec2> {
        val r = table.ballRadius
        if (r < 3.0) return emptyList()
        val cloth = cloth(frame, table) ?: return emptyList()
        val step = cloth.step
        val x0 = cloth.x0
        val y0 = cloth.y0
        val gw = cloth.columns
        val gh = cloth.rows
        if (mask.size < gw * gh) mask = BooleanArray(gw * gh)
        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                mask[gy * gw + gx] = cloth.isObjectAt(frame, gx, gy)
            }
        }
        val stride = gw + 1
        if (integral.size < stride * (gh + 1)) integral = IntArray(stride * (gh + 1))
        for (i in 0..gw) integral[i] = 0
        for (gy in 0 until gh) {
            val row = (gy + 1) * stride
            var rowSum = 0
            integral[row] = 0
            for (gx in 0 until gw) {
                if (mask[gy * gw + gx]) rowSum++
                integral[row + gx + 1] = integral[row - stride + gx + 1] + rowSum
            }
        }

        // Candidatos: centros (dentro do retângulo por onde anda o centro da bola) com o miolo cheio.
        val half = max(1, (0.6 * r / step).roundToInt())
        val area = (2 * half + 1) * (2 * half + 1)
        val candidates = ArrayList<IntArray>()
        val gx0 = max(half, ceil((table.minX - x0) / step).toInt())
        val gx1 = min(gw - 1 - half, floor((table.maxX - x0) / step).toInt())
        val gy0 = max(half, ceil((table.minY - y0) / step).toInt())
        val gy1 = min(gh - 1 - half, floor((table.maxY - y0) / step).toInt())
        for (gy in gy0..gy1) {
            val top = (gy - half) * stride
            val bottom = (gy + half + 1) * stride
            for (gx in gx0..gx1) {
                val left = gx - half
                val right = gx + half + 1
                val sum = integral[bottom + right] - integral[top + right] - integral[bottom + left] + integral[top + left]
                if (sum >= MIN_FILL * area) candidates += intArrayOf(sum, gx, gy)
            }
        }
        candidates.sortByDescending { it[0] }

        // Fica com o melhor de cada grupo (bolas encostadas ficam a 2 raios uma da outra).
        val minDistance = 1.6 * r
        val centers = ArrayList<Vec2>()
        for (c in candidates) {
            val p = Vec2(x0 + c[1].toDouble() * step, y0 + c[2].toDouble() * step)
            if (centers.none { it.distanceTo(p) < minDistance }) centers += p
            if (centers.size >= MAX_BALLS) break
        }
        // Depois do ajuste fino dois candidatos podem ter caído na mesma bola: fica um só.
        val refined = ArrayList<Vec2>()
        for (c in centers) {
            val p = refine(frame, cloth, c, r)
            if (refined.none { it.distanceTo(p) < minDistance } && isRound(frame, cloth, p, r)) refined += p
        }
        return refined
    }

    /** O pano não muda durante a partida: reaproveita a estimativa por várias buscas. */
    private fun cloth(frame: Frame, table: Table): Cloth? {
        val cached = cloth
        if (cached != null && table == clothTable && findsSinceCloth < CLOTH_REFRESH) {
            findsSinceCloth++
            return cached
        }
        return Cloth.estimate(frame, table).also {
            cloth = it
            clothTable = table
            findsSinceCloth = 0
        }
    }

    /**
     * Uma bola cobre todo o círculo de 70% do raio em volta do centro; o taco (mesmo com enfeites
     * do tamanho de uma bola) só cobre os pontos na direção dele.
     */
    private fun isRound(frame: Frame, cloth: Cloth, c: Vec2, r: Double): Boolean {
        var hits = 0
        for (i in 0 until ROUND_SAMPLES) {
            val angle = 2 * Math.PI * i / ROUND_SAMPLES
            val x = (c.x + kotlin.math.cos(angle) * 0.7 * r).roundToInt()
            val y = (c.y + kotlin.math.sin(angle) * 0.7 * r).roundToInt()
            if (x in 0 until frame.width && y in 0 until frame.height && cloth.isObject(frame, x, y)) hits++
        }
        return hits >= ROUND_MIN_HITS
    }

    /**
     * Centro de massa dos pixels "não pano" numa janela do tamanho da bola, repetido até parar: se a
     * janela está deslocada, ela pega pano de um lado e volta para o meio. Do tamanho exato da bola,
     * ela não alcança a bola vizinha mesmo quando as duas se encostam.
     */
    private fun refine(frame: Frame, cloth: Cloth, start: Vec2, r: Double): Vec2 {
        val step = max(1, cloth.step / 2)
        var c = start
        for (iteration in 0 until 6) {
            var sx = 0.0
            var sy = 0.0
            var n = 0
            for (y in floor(c.y - r).toInt()..ceil(c.y + r).toInt() step step) {
                for (x in floor(c.x - r).toInt()..ceil(c.x + r).toInt() step step) {
                    if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                    val dx = x - c.x
                    val dy = y - c.y
                    if (dx * dx + dy * dy > r * r) continue
                    if (cloth.isObject(frame, x, y)) {
                        sx += x
                        sy += y
                        n++
                    }
                }
            }
            if (n == 0) break
            val next = Vec2(sx / n, sy / n)
            val moved = next.distanceTo(c)
            c = next
            if (moved < 0.2) break
        }
        return c
    }

    /**
     * Cor esperada do pano em cada ponto, montada em três camadas:
     * 1. o degradê geral, que clareia no meio e achata nos cantos ([Radial]);
     * 2. a sombra que cada tabela faz no pano logo abaixo dela (mais larga na de cima), medida como a
     *    diferença mediana em função da distância até a tabela;
     * 3. uma correção suave por blocos da mesa, com a média do que sobrou nos pixels que parecem pano.
     * Medianas e o descarte do que não parece pano fazem as bolas não atrapalharem a estimativa.
     */
    class Cloth private constructor(
        /** Grade (em pixels da tela) onde a cor esperada do pano foi calculada. */
        val x0: Int,
        val y0: Int,
        val step: Int,
        val columns: Int,
        val rows: Int,
        private val background: FloatArray,
    ) {
        /** O pixel do nó (gx, gy) da grade é bola/linha (não pano)? */
        fun isObjectAt(frame: Frame, gx: Int, gy: Int): Boolean {
            val k = 3 * (gy * columns + gx)
            val p = frame.pixel(x0 + gx * step, y0 + gy * step)
            return difference(p, background[k].toDouble(), background[k + 1].toDouble(), background[k + 2].toDouble()) > OBJECT_DIFFERENCE
        }

        /** Para qualquer pixel: compara com a cor esperada no nó mais próximo da grade. */
        fun isObject(frame: Frame, x: Int, y: Int): Boolean {
            val gx = ((x - x0).toDouble() / step).roundToInt().coerceIn(0, columns - 1)
            val gy = ((y - y0).toDouble() / step).roundToInt().coerceIn(0, rows - 1)
            val k = 3 * (gy * columns + gx)
            return difference(frame.pixel(x, y), background[k].toDouble(), background[k + 1].toDouble(), background[k + 2].toDouble()) > OBJECT_DIFFERENCE
        }

        /** As camadas usadas para montar a grade: degradê + sombras das tabelas + correção por bloco. */
        private class Layers(
            val table: Table,
            val radial: Radial,
            val edges: Array<Array<FloatArray>>,
            val block: Double,
            val blockColumns: Int,
            val blockRows: Int,
            val correction: Array<FloatArray>,
        ) {
            /** Degradê + sombra da tabela mais próxima. */
            fun base(x: Int, y: Int, out: DoubleArray) {
                radial.color(x.toDouble(), y.toDouble(), out)
                var side = 0
                var d = x - table.left
                if (table.right - x < d) { side = 1; d = table.right - x }
                if (y - table.top < d) { side = 2; d = y - table.top }
                if (table.bottom - y < d) { side = 3; d = table.bottom - y }
                val k = d.toInt()
                if (k >= 0 && k < edges[side][0].size) for (c in 0 until 3) out[c] += edges[side][c][k]
            }

            fun expected(x: Int, y: Int, out: DoubleArray) {
                base(x, y, out)
                val fx = ((x - table.left) / block - 0.5).coerceIn(0.0, blockColumns - 1.0)
                val fy = ((y - table.top) / block - 0.5).coerceIn(0.0, blockRows - 1.0)
                val i = min(fx.toInt(), blockColumns - 2).coerceAtLeast(0)
                val j = min(fy.toInt(), blockRows - 2).coerceAtLeast(0)
                val tx = if (blockColumns > 1) fx - i else 0.0
                val ty = if (blockRows > 1) fy - j else 0.0
                for (c in 0 until 3) out[c] += lerp(correction[c], i, j, tx, ty)
            }

            private fun lerp(values: FloatArray, i: Int, j: Int, tx: Double, ty: Double): Double {
                val i1 = min(i + 1, blockColumns - 1)
                val j1 = min(j + 1, blockRows - 1)
                val top = values[j * blockColumns + i] * (1 - tx) + values[j * blockColumns + i1] * tx
                val bottom = values[j1 * blockColumns + i] * (1 - tx) + values[j1 * blockColumns + i1] * tx
                return top * (1 - ty) + bottom * ty
            }
        }

        companion object {
            fun estimate(frame: Frame, table: Table): Cloth? {
                val r = table.ballRadius
                val radial = Radial.fit(frame, table) ?: return null
                val edges = edgeShadows(frame, table, radial)
                val noCorrection = Layers(table, radial, edges, 3 * r, 1, 1, Array(3) { FloatArray(1) })

                // Correção por blocos: média do resíduo nos pixels que parecem pano.
                val block = 3 * r
                val blockColumns = max(1, ceil((table.right - table.left) / block).toInt())
                val blockRows = max(1, ceil((table.bottom - table.top) / block).toInt())
                val correction = Array(3) { FloatArray(blockColumns * blockRows) }
                val expected = DoubleArray(3)
                val sampleStep = max(1, (block / 12).toInt())
                for (j in 0 until blockRows) {
                    for (i in 0 until blockColumns) {
                        val sums = DoubleArray(3)
                        var cloth = 0
                        var total = 0
                        val bx0 = (table.left + i * block).toInt()
                        val by0 = (table.top + j * block).toInt()
                        val bx1 = min((bx0 + block).toInt(), min(frame.width, ceil(table.right).toInt()))
                        val by1 = min((by0 + block).toInt(), min(frame.height, ceil(table.bottom).toInt()))
                        for (y in max(0, by0) until by1 step sampleStep) {
                            for (x in max(0, bx0) until bx1 step sampleStep) {
                                total++
                                noCorrection.base(x, y, expected)
                                val p = frame.pixel(x, y)
                                if (difference(p, expected[0], expected[1], expected[2]) > CLOTH_DIFFERENCE) continue
                                sums[0] += Frame.red(p) - expected[0]
                                sums[1] += Frame.green(p) - expected[1]
                                sums[2] += Frame.blue(p) - expected[2]
                                cloth++
                            }
                        }
                        if (total > 0 && cloth >= 0.3 * total) {
                            for (c in 0 until 3) correction[c][j * blockColumns + i] = (sums[c] / cloth).toFloat()
                        }
                    }
                }
                val layers = Layers(table, radial, edges, block, blockColumns, blockRows, correction)

                // Grade com a cor esperada (bolas grandes: um nó a cada poucos pixels basta).
                val step = max(1, (r / 7).toInt())
                val x0 = max(0, floor(table.left).toInt())
                val y0 = max(0, floor(table.top).toInt())
                val x1 = min(frame.width - 1, ceil(table.right).toInt())
                val y1 = min(frame.height - 1, ceil(table.bottom).toInt())
                if (x1 <= x0 || y1 <= y0) return null
                val columns = (x1 - x0) / step + 1
                val rows = (y1 - y0) / step + 1
                val background = FloatArray(3 * columns * rows)
                for (gy in 0 until rows) {
                    for (gx in 0 until columns) {
                        layers.expected(x0 + gx * step, y0 + gy * step, expected)
                        val k = 3 * (gy * columns + gx)
                        background[k] = expected[0].toFloat()
                        background[k + 1] = expected[1].toFloat()
                        background[k + 2] = expected[2].toFloat()
                    }
                }
                return Cloth(x0, y0, step, columns, rows, background)
            }

            /**
             * Para cada tabela (esquerda, direita, cima, baixo), a diferença mediana entre o pano e o
             * degradê a cada pixel de distância dela, até 3 raios. Pula as bocas das caçapas.
             */
            private fun edgeShadows(frame: Frame, table: Table, radial: Radial): Array<Array<FloatArray>> {
                val r = table.ballRadius
                val depth = max(1, (3 * r).toInt())
                val step = max(2.0, r / 2)
                val margin = 3 * r
                val expected = DoubleArray(3)
                return Array(4) { side ->
                    val vertical = side < 2
                    val along = ArrayList<Double>()
                    var t = (if (vertical) table.top else table.left) + margin
                    val end = (if (vertical) table.bottom else table.right) - margin
                    while (t <= end) {
                        if (vertical || kotlin.math.abs(t - table.centerX) > 2.5 * r) along += t
                        t += step
                    }
                    Array(3) { FloatArray(depth) }.also { profile ->
                        val values = Array(3) { DoubleArray(along.size) }
                        for (d in 0 until depth) {
                            var n = 0
                            for (a in along) {
                                val x = when (side) {
                                    0 -> table.left + d
                                    1 -> table.right - d
                                    else -> a
                                }.roundToInt()
                                val y = when (side) {
                                    2 -> table.top + d
                                    3 -> table.bottom - d
                                    else -> a
                                }.roundToInt()
                                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                                radial.color(x.toDouble(), y.toDouble(), expected)
                                val p = frame.pixel(x, y)
                                values[0][n] = Frame.red(p) - expected[0]
                                values[1][n] = Frame.green(p) - expected[1]
                                values[2][n] = Frame.blue(p) - expected[2]
                                n++
                            }
                            if (n == 0) continue
                            for (c in 0 until 3) {
                                val sorted = values[c].copyOf(n).also { it.sort() }
                                profile[c][d] = sorted[n / 2].toFloat()
                            }
                        }
                    }
                }
            }

            /**
             * Diferença de cor que pesa mais o tom do que o brilho: o pano muda de brilho (degradê,
             * sombras) mas quase não muda de tom, e uma bola azul no pano azul muda pouco no brilho.
             */
            fun difference(p: Int, r: Double, g: Double, b: Double): Double {
                val dr = Frame.red(p) - r
                val dg = Frame.green(p) - g
                val db = Frame.blue(p) - b
                val dy = 0.299 * dr + 0.587 * dg + 0.114 * db
                return abs(dy) + CHROMA_WEIGHT * (abs(db - dy) + abs(dr - dy))
            }

            private const val CHROMA_WEIGHT = 1.5

            /** Até essa diferença o pixel ainda conta como pano ao corrigir os blocos. */
            private const val CLOTH_DIFFERENCE = 45.0
        }
    }

    /**
     * Degradê geral do pano em função da distância ao centro da mesa (normalizada pelo tamanho dela):
     * mediana de cada canal em anéis, longe das tabelas (cuja sombra é medida à parte). A mediana
     * ignora as bolas, que ocupam pouco de cada anel.
     */
    private class Radial(
        private val cx: Double,
        private val cy: Double,
        private val hw: Double,
        private val hh: Double,
        private val profile: Array<FloatArray>,
    ) {
        fun color(x: Double, y: Double, out: DoubleArray) {
            val f = (kotlin.math.hypot((x - cx) / hw, (y - cy) / hh) / MAX_RHO * (BINS - 1)).coerceIn(0.0, BINS - 1.0)
            val i = min(f.toInt(), BINS - 2)
            val t = f - i
            for (c in 0 until 3) out[c] = profile[c][i] * (1 - t) + profile[c][i + 1] * t
        }

        companion object {
            fun fit(frame: Frame, table: Table): Radial? {
                val cx = (table.left + table.right) / 2
                val cy = (table.top + table.bottom) / 2
                val hw = (table.right - table.left) / 2
                val hh = (table.bottom - table.top) / 2
                if (hw <= 0 || hh <= 0) return null
                val counts = Array(BINS) { Array(3) { IntArray(256) } }
                val totals = IntArray(BINS)
                val step = max(2, (table.ballRadius / 2).toInt())
                val inset = 2 * table.ballRadius
                var y = (table.top + inset).toInt()
                while (y <= table.bottom - inset) {
                    var x = (table.left + inset).toInt()
                    while (x <= table.right - inset) {
                        if (x in 0 until frame.width && y in 0 until frame.height) {
                            val rho = kotlin.math.hypot((x - cx) / hw, (y - cy) / hh)
                            val bin = (rho / MAX_RHO * (BINS - 1)).roundToInt().coerceIn(0, BINS - 1)
                            val p = frame.pixel(x, y)
                            counts[bin][0][Frame.red(p)]++
                            counts[bin][1][Frame.green(p)]++
                            counts[bin][2][Frame.blue(p)]++
                            totals[bin]++
                        }
                        x += step
                    }
                    y += step
                }
                val profile = Array(3) { FloatArray(BINS) { Float.NaN } }
                for (bin in 0 until BINS) {
                    if (totals[bin] < MIN_BIN_SAMPLES) continue
                    for (c in 0 until 3) profile[c][bin] = median(counts[bin][c], totals[bin]).toFloat()
                }
                // Anéis sem amostras suficientes copiam o vizinho mais próximo que tem.
                val filled = (0 until BINS).filter { !profile[0][it].isNaN() }
                if (filled.isEmpty()) return null
                for (bin in 0 until BINS) {
                    if (!profile[0][bin].isNaN()) continue
                    val nearest = filled.minBy { abs(it - bin) }
                    for (c in 0 until 3) profile[c][bin] = profile[c][nearest]
                }
                return Radial(cx, cy, hw, hh, profile)
            }

            private fun median(histogram: IntArray, total: Int): Int {
                var seen = 0
                for (value in 0 until 256) {
                    seen += histogram[value]
                    if (seen * 2 >= total) return value
                }
                return 255
            }

            private const val BINS = 24
            private val MAX_RHO = kotlin.math.sqrt(2.0)
            private const val MIN_BIN_SAMPLES = 10
        }
    }

    private companion object {
        /** Diferença de cor ([Cloth.difference]) a partir da qual o pixel não é pano. */
        const val OBJECT_DIFFERENCE = 70.0

        /** Fração do miolo que precisa ser "não pano" para contar como bola. */
        const val MIN_FILL = 0.75
        const val MAX_BALLS = 24

        /** De quantas em quantas buscas reestima o pano. */
        const val CLOTH_REFRESH = 30
        const val ROUND_SAMPLES = 16
        const val ROUND_MIN_HITS = 13
    }
}
