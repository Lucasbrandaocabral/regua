package io.github.lucasbrandaocabral.regua.geometry

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

enum class PathEnd {
    /** Parou na última tabela permitida. */
    CUSHION,

    /** Entrou numa caçapa. */
    POCKET,

    /** Encostou em outra bola. */
    BALL,

    /** Acabou o comprimento máximo. */
    LIMIT,
}

class BallPath(
    /** Polilinha do centro da bola: início, batidas nas tabelas e fim. */
    val points: List<Vec2>,
    /** Só os pontos onde a bola bateu numa tabela. */
    val bounces: List<Vec2>,
    val end: PathEnd,
    /** Direção em que a bola estava indo no fim do caminho. */
    val direction: Vec2,
    val pocket: Pocket? = null,
    /** Centro da bola atingida, quando [end] é [PathEnd.BALL]. */
    val hitBall: Vec2? = null,
)

/**
 * Efeito na bola branca, do jeito que se marca no jogo (valores de -1 a 1).
 * [side] > 0 é efeito à direita; [follow] > 0 é bater em cima (a branca segue) e < 0 embaixo (volta).
 */
data class Spin(val side: Double = 0.0, val follow: Double = 0.0) {
    val isNone: Boolean get() = side == 0.0 && follow == 0.0

    companion object {
        val NONE = Spin()
    }
}

class Shot(
    val cue: BallPath,
    /** Caminho da bola alvo depois do choque; null se a branca não chegou nela. */
    val objectBall: BallPath? = null,
    /** Caminho da branca depois do choque; null se ela para (bola cheia sem efeito). */
    val cueAfter: BallPath? = null,
    /** Ângulo de corte em graus: 0 é bola cheia, perto de 90 é bem fina. */
    val cutAngle: Double? = null,
    /** Caminho da bola que a bola alvo acerta em seguida (combinação), quando acerta. */
    val combo: BallPath? = null,
) {
    /** Onde está o centro da branca no instante do choque. */
    val ghost: Vec2? get() = if (cue.end == PathEnd.BALL) cue.points.last() else null
}

object ShotSolver {
    private const val EPS = 1e-9

    /** Abaixo disso (seno do corte, ou efeito muito fraco) a branca praticamente para no choque. */
    private const val MIN_CUE_TRAVEL = 0.03

    /** Quanto o efeito em cima/embaixo puxa a branca ao longo da linha da bola alvo. */
    private const val FOLLOW_FACTOR = 0.4

    /** Quanto o efeito lateral desvia a bola numa tabela, relativo à velocidade contra a tabela. */
    private const val SIDE_SPIN_KICK = 0.35

    /** Fração do efeito lateral que sobra depois de cada tabela. */
    private const val SIDE_SPIN_DECAY = 0.5

    /**
     * Calcula a tacada: a branca sai de [cue] na direção [aim] e pode tabelar até [cushions] vezes.
     * A primeira bola que ela encostar ([target] ou uma das [obstacles], as outras bolas da mesa)
     * vira a bola alvo: calcula o caminho dela, o da branca depois do choque e, se a bola alvo
     * acertar outra bola, o caminho dessa outra (combinação). Os caminhos param na bola que encostarem.
     */
    fun solve(
        table: Table,
        cue: Vec2,
        aim: Vec2,
        target: Vec2?,
        cushions: Int,
        spin: Spin = Spin.NONE,
        obstacles: List<Vec2> = emptyList(),
    ): Shot {
        val balls = listOfNotNull(target) + obstacles
        val cuePath = trace(table, cue, aim, cushions, balls, sideSpin = spin.side)
        val hit = cuePath.hitBall ?: return Shot(cuePath)
        val others = balls.filter { it != hit }
        val ghost = cuePath.points.last()
        val incoming = cuePath.direction
        // A bola alvo sai na linha que liga os dois centros no momento do choque.
        val objectDir = (hit - ghost).normalized()
        val cosCut = (incoming dot objectDir).coerceIn(-1.0, 1.0)
        val objectPath = trace(table, hit, objectDir, cushions, others)
        val combo = objectPath.hitBall?.let { second ->
            val contact = objectPath.points.last()
            trace(table, second, (second - contact).normalized(), cushions, others.filter { it != second })
        }

        // Sem efeito a branca sai a 90° da bola alvo (só sobra a parte tangente da velocidade);
        // efeito em cima/embaixo soma ou tira velocidade na direção da bola alvo.
        val tangent = incoming - objectDir * cosCut
        val cueOut = tangent + objectDir * (cosCut * FOLLOW_FACTOR * spin.follow)
        val travel = cueOut.length
        val cueAfter = if (travel > MIN_CUE_TRAVEL) {
            trace(
                table, ghost, cueOut, min(cushions, 2), others,
                sideSpin = spin.side, maxLength = table.width * min(1.0, travel),
            )
        } else {
            null
        }
        return Shot(cuePath, objectPath, cueAfter, Math.toDegrees(acos(cosCut)), combo)
    }

    /**
     * Segue o centro de uma bola a partir de [start] na direção [direction], refletindo nas tabelas
     * até [maxCushions] vezes. Para antes se entrar numa caçapa, encostar numa das [balls] ou andar
     * [maxLength] pixels. [sideSpin] muda o ângulo de saída das tabelas (ver [Spin.side]).
     */
    fun trace(
        table: Table,
        start: Vec2,
        direction: Vec2,
        maxCushions: Int,
        balls: List<Vec2> = emptyList(),
        sideSpin: Double = 0.0,
        maxLength: Double = Double.POSITIVE_INFINITY,
    ): BallPath {
        var pos = Vec2(start.x.coerceIn(table.minX, table.maxX), start.y.coerceIn(table.minY, table.maxY))
        var dir = direction.normalized()
        val points = mutableListOf(pos)
        val bounces = mutableListOf<Vec2>()
        if (dir == Vec2.ZERO) return BallPath(points, bounces, PathEnd.LIMIT, dir)

        var remaining = maxLength
        var spin = sideSpin
        while (true) {
            val tx = when {
                dir.x > EPS -> (table.maxX - pos.x) / dir.x
                dir.x < -EPS -> (table.minX - pos.x) / dir.x
                else -> Double.POSITIVE_INFINITY
            }
            val ty = when {
                dir.y > EPS -> (table.maxY - pos.y) / dir.y
                dir.y < -EPS -> (table.minY - pos.y) / dir.y
                else -> Double.POSITIVE_INFINITY
            }
            val tWall = min(tx, ty)

            val ball = firstBallHit(table, pos, dir, balls, min(tWall, remaining))
            if (ball != null) {
                points += pos + dir * ball.first
                return BallPath(points, bounces, PathEnd.BALL, dir, hitBall = ball.second)
            }
            if (remaining <= tWall) {
                points += pos + dir * remaining
                return BallPath(points, bounces, PathEnd.LIMIT, dir)
            }

            // vertical: bate na tabela da esquerda/direita; horizontal: na de cima/baixo.
            val vertical = tx <= ty + EPS
            val horizontal = ty <= tx + EPS
            val raw = pos + dir * tWall
            val wall = Vec2(
                if (vertical) (if (dir.x > 0) table.maxX else table.minX) else raw.x,
                if (horizontal) (if (dir.y > 0) table.maxY else table.minY) else raw.y,
            )
            points += wall

            val pocket = pocketAt(table, wall, dir, vertical, horizontal)
            if (pocket != null) {
                // Continua um pouco para dentro do buraco, só para a linha terminar na caçapa.
                val depth = ((pocket.center - wall) dot dir).coerceIn(0.0, 3 * table.ballRadius)
                points += wall + dir * depth
                return BallPath(points, bounces, PathEnd.POCKET, dir, pocket = pocket)
            }
            if (bounces.size >= maxCushions) return BallPath(points, bounces, PathEnd.CUSHION, dir)

            bounces += wall
            var out = Vec2(if (vertical) -dir.x else dir.x, if (horizontal) -dir.y else dir.y)
            if (spin != 0.0 && vertical != horizontal) {
                // O atrito do giro com a borracha empurra a bola ao longo da tabela; com efeito à
                // direita (giro anti-horário visto de cima) o empurrão é para a direita de quem vem.
                val towardCushion = Vec2(if (vertical) sign(dir.x) else 0.0, if (horizontal) sign(dir.y) else 0.0)
                val along = Vec2(-towardCushion.y, towardCushion.x)
                out = (out + along * (spin * SIDE_SPIN_KICK * abs(dir dot towardCushion))).normalized()
                spin *= SIDE_SPIN_DECAY
            }
            dir = out
            pos = wall
            remaining -= tWall
        }
    }

    private fun pocketAt(table: Table, p: Vec2, dir: Vec2, vertical: Boolean, horizontal: Boolean): Pocket? {
        val r = table.ballRadius
        val cornerMouth = Table.CORNER_MOUTH * r
        if (horizontal) {
            val top = dir.y < 0
            if (p.x - table.minX <= cornerMouth) return table.pocket(if (top) PocketId.TOP_LEFT else PocketId.BOTTOM_LEFT)
            if (table.maxX - p.x <= cornerMouth) return table.pocket(if (top) PocketId.TOP_RIGHT else PocketId.BOTTOM_RIGHT)
            // Onde o centro da bola cruza a linha da tabela, um raio além do retângulo interno.
            val crossX = p.x + dir.x / abs(dir.y) * r
            if (abs(dir.y) >= Table.SIDE_MIN_APPROACH && abs(crossX - table.centerX) <= Table.SIDE_MOUTH * r) {
                return table.pocket(if (top) PocketId.TOP_MIDDLE else PocketId.BOTTOM_MIDDLE)
            }
        }
        if (vertical) {
            val left = dir.x < 0
            if (p.y - table.minY <= cornerMouth) return table.pocket(if (left) PocketId.TOP_LEFT else PocketId.TOP_RIGHT)
            if (table.maxY - p.y <= cornerMouth) return table.pocket(if (left) PocketId.BOTTOM_LEFT else PocketId.BOTTOM_RIGHT)
        }
        return null
    }

    /** Primeira bola de [balls] em que a bola em [pos] encosta andando em [dir], até a distância [maxT]. */
    private fun firstBallHit(table: Table, pos: Vec2, dir: Vec2, balls: List<Vec2>, maxT: Double): Pair<Double, Vec2>? {
        val reach = 2 * table.ballRadius
        var best: Pair<Double, Vec2>? = null
        for (center in balls) {
            val m = pos - center
            val c = (m dot m) - reach * reach
            // Bolas já encostadas (marcadores sobrepostos): ignora em vez de travar a linha.
            if (c <= 0) continue
            val b = m dot dir
            val disc = b * b - c
            if (b >= 0 || disc < 0) continue
            val t = -b - sqrt(disc)
            if (t <= maxT && (best == null || t < best.first)) best = t to center
        }
        return best
    }
}
