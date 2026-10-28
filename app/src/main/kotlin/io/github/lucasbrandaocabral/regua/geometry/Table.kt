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

    companion object {
        // Medidas em raios de bola, tiradas de um print do 8 Ball Pool (todas as mesas do jogo têm a
        // mesma geometria; só muda a cor).

        /** Abertura da caçapa de canto, contada a partir do canto do retângulo interno, em cada tabela. */
        const val CORNER_MOUTH = 1.7

        /** Meia abertura da caçapa do meio, medida onde o centro da bola cruza a linha da tabela. */
        const val SIDE_MOUTH = 1.5

        /** Bola que chega rasante demais na caçapa do meio bate na quina e não cai. */
        const val SIDE_MIN_APPROACH = 0.4

        const val CORNER_HOLE_OFFSET = 1.0
        const val CORNER_HOLE_RADIUS = 2.1
        const val SIDE_HOLE_OFFSET = 1.8
        const val SIDE_HOLE_RADIUS = 1.7
    }
}
