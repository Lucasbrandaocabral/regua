package io.github.lucasbrandaocabral.regua.geometry

enum class PocketId { TOP_LEFT, TOP_MIDDLE, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_MIDDLE, BOTTOM_RIGHT }

/** Buraco da caçapa: só serve para desenhar e destacar; quem decide se a bola entra é o [ShotSolver]. */
data class Pocket(val id: PocketId, val center: Vec2, val radius: Double)

/**
 * Mesa calibrada, em pixels da tela.
 *
 * [left], [top], [right] e [bottom] são as linhas onde a bola encosta nas tabelas (a borda da
 * borracha). O centro da bola nunca chega mais perto delas do que [ballRadius], então as batidas são
 * calculadas no retângulo interno [minX]..[maxX] x [minY]..[maxY].
 */
data class Table(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val ballRadius: Double,
) {
    val width: Double get() = right - left
    val centerX: Double get() = (left + right) / 2

    val minX: Double get() = left + ballRadius
    val maxX: Double get() = right - ballRadius
    val minY: Double get() = top + ballRadius
    val maxY: Double get() = bottom - ballRadius

    val pockets: List<Pocket>
        get() = PocketId.entries.map(::pocket)

    fun pocket(id: PocketId): Pocket {
        val r = ballRadius
        return when (id) {
            PocketId.TOP_LEFT -> Pocket(id, Vec2(left - CORNER_HOLE_OFFSET * r, top - CORNER_HOLE_OFFSET * r), CORNER_HOLE_RADIUS * r)
            PocketId.TOP_RIGHT -> Pocket(id, Vec2(right + CORNER_HOLE_OFFSET * r, top - CORNER_HOLE_OFFSET * r), CORNER_HOLE_RADIUS * r)
            PocketId.BOTTOM_LEFT -> Pocket(id, Vec2(left - CORNER_HOLE_OFFSET * r, bottom + CORNER_HOLE_OFFSET * r), CORNER_HOLE_RADIUS * r)
            PocketId.BOTTOM_RIGHT -> Pocket(id, Vec2(right + CORNER_HOLE_OFFSET * r, bottom + CORNER_HOLE_OFFSET * r), CORNER_HOLE_RADIUS * r)
            PocketId.TOP_MIDDLE -> Pocket(id, Vec2(centerX, top - SIDE_HOLE_OFFSET * r), SIDE_HOLE_RADIUS * r)
            PocketId.BOTTOM_MIDDLE -> Pocket(id, Vec2(centerX, bottom + SIDE_HOLE_OFFSET * r), SIDE_HOLE_RADIUS * r)
        }
    }

    /** As duas pontas da boca da caçapa, na linha das tabelas. */
    fun mouth(id: PocketId): Pair<Vec2, Vec2> {
        val j = CORNER_JAW * ballRadius
        val s = SIDE_JAW * ballRadius
        return when (id) {
            PocketId.TOP_LEFT -> Vec2(left + j, top) to Vec2(left, top + j)
            PocketId.TOP_RIGHT -> Vec2(right - j, top) to Vec2(right, top + j)
            PocketId.BOTTOM_LEFT -> Vec2(left + j, bottom) to Vec2(left, bottom - j)
            PocketId.BOTTOM_RIGHT -> Vec2(right - j, bottom) to Vec2(right, bottom - j)
            PocketId.TOP_MIDDLE -> Vec2(centerX - s, top) to Vec2(centerX + s, top)
            PocketId.BOTTOM_MIDDLE -> Vec2(centerX - s, bottom) to Vec2(centerX + s, bottom)
        }
    }

    companion object {
        // Medidas em raios de bola, tiradas de prints do 8 Ball Pool (todas as mesas do jogo têm a
        // mesma geometria; só muda a cor). As pontas da boca são onde a borracha reta acaba.

        /** Ponta da caçapa de canto, contando da tabela do lado. */
        const val CORNER_JAW = 2.2

        /** Ponta da caçapa do meio, contando do meio da tabela. */
        const val SIDE_JAW = 1.9

        /** A bola só cai se o centro passa pelo menos isso longe das duas pontas. */
        const val JAW_CLEARANCE = 0.95

        const val CORNER_HOLE_OFFSET = 1.0
        const val CORNER_HOLE_RADIUS = 2.1
        const val SIDE_HOLE_OFFSET = 1.8
        const val SIDE_HOLE_RADIUS = 1.7
    }
}
