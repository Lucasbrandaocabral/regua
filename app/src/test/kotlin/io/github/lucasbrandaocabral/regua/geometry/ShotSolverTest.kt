package io.github.lucasbrandaocabral.regua.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShotSolverTest {
    // Mesa 2:1 com bola de raio 10: o centro da bola anda em x 10..390 e y 10..190.
    private val table = Table(left = 0.0, top = 0.0, right = 400.0, bottom = 200.0, ballRadius = 10.0)

    private fun assertVec(expected: Vec2, actual: Vec2, tolerance: Double = 1e-6) {
        assertEquals("x", expected.x, actual.x, tolerance)
        assertEquals("y", expected.y, actual.y, tolerance)
    }

    @Test
    fun straightLineStopsAtFirstCushionWhenNoBouncesAllowed() {
        val path = ShotSolver.trace(table, Vec2(100.0, 80.0), Vec2(1.0, 0.0), maxCushions = 0)
        assertEquals(PathEnd.CUSHION, path.end)
        assertVec(Vec2(390.0, 80.0), path.points.last())
        assertTrue(path.bounces.isEmpty())
    }

    @Test
    fun bounceKeepsAngleOfIncidence() {
        // Desce a 45° até a tabela de baixo (longe da caçapa do meio) e volta subindo a 45°.
        val path = ShotSolver.trace(table, Vec2(100.0, 120.0), Vec2(1.0, 1.0), maxCushions = 1)
        assertVec(Vec2(170.0, 190.0), path.bounces.single())
        assertVec(Vec2(1.0, -1.0).normalized(), path.direction)
        // Depois da batida sobe até a tabela de cima (y = 10), andando 180 em x.
        assertEquals(PathEnd.CUSHION, path.end)
        assertVec(Vec2(350.0, 10.0), path.points.last())
    }

    @Test
    fun aimingAtCornerGoesIntoCornerPocket() {
        val path = ShotSolver.trace(table, Vec2(200.0, 100.0), Vec2(-190.0, -90.0), maxCushions = 3)
        assertEquals(PathEnd.POCKET, path.end)
        assertEquals(PocketId.TOP_LEFT, path.pocket?.id)
    }

    @Test
    fun ballRunningAlongTheRailDropsInCorner() {
        val path = ShotSolver.trace(table, Vec2(300.0, 12.0), Vec2(-1.0, -0.005), maxCushions = 3)
        assertEquals(PocketId.TOP_LEFT, path.pocket?.id)
    }

    @Test
    fun straightUpAtCenterGoesIntoSidePocket() {
        val path = ShotSolver.trace(table, Vec2(200.0, 150.0), Vec2(0.0, -1.0), maxCushions = 3)
        assertEquals(PathEnd.POCKET, path.end)
        assertEquals(PocketId.TOP_MIDDLE, path.pocket?.id)
        // A linha termina dentro do buraco, acima da tabela.
        assertTrue(path.points.last().y < table.top)
    }

    @Test
    fun shallowApproachIsRejectedBySidePocket() {
        // Chega no meio da tabela de cima quase paralela a ela: bate na quina e volta.
        val path = ShotSolver.trace(table, Vec2(100.0, 30.0), Vec2(1.0, -0.2), maxCushions = 1)
        assertVec(Vec2(200.0, 10.0), path.bounces.single())
        assertTrue(path.pocket?.id != PocketId.TOP_MIDDLE)
    }

    @Test
    fun steepApproachEntersSidePocket() {
        val path = ShotSolver.trace(table, Vec2(180.0, 30.0), Vec2(1.0, -1.0), maxCushions = 1)
        assertEquals(PocketId.TOP_MIDDLE, path.pocket?.id)
    }

    @Test
    fun cushionCountIsRespected() {
        val path = ShotSolver.trace(table, Vec2(100.0, 50.0), Vec2(3.0, 1.3), maxCushions = 4)
        assertTrue(path.bounces.size <= 4)
        if (path.end == PathEnd.CUSHION) assertEquals(4, path.bounces.size)
    }

    @Test
    fun maxLengthCutsThePath() {
        val path = ShotSolver.trace(table, Vec2(100.0, 100.0), Vec2(1.0, 0.0), maxCushions = 3, maxLength = 50.0)
        assertEquals(PathEnd.LIMIT, path.end)
        assertVec(Vec2(150.0, 100.0), path.points.last())
    }

    @Test
    fun fullHitStopsCueBallAndSendsTargetStraight() {
        val shot = ShotSolver.solve(table, cue = Vec2(100.0, 100.0), aim = Vec2(1.0, 0.0), target = Vec2(200.0, 100.0), cushions = 2)
        // A branca encosta quando os centros ficam a 2 raios.
        assertVec(Vec2(180.0, 100.0), shot.ghost!!)
        assertEquals(0.0, shot.cutAngle!!, 1e-6)
        assertNull(shot.cueAfter)
        val target = shot.objectBall!!
        assertVec(Vec2(390.0, 100.0), target.points.last())
    }

    @Test
    fun cutShotFollowsNinetyDegreeRule() {
        // Branca passa 10 px acima do centro do alvo: corte de 30°.
        val shot = ShotSolver.solve(table, cue = Vec2(50.0, 90.0), aim = Vec2(1.0, 0.0), target = Vec2(200.0, 100.0), cushions = 1)
        val ghost = shot.ghost!!
        assertEquals(20.0, ghost.distanceTo(Vec2(200.0, 100.0)), 1e-6)
        assertEquals(30.0, shot.cutAngle!!, 1e-6)
        val objectDir = shot.objectBall!!.points.let { (it[1] - it[0]).normalized() }
        val cueDir = shot.cueAfter!!.points.let { (it[1] - it[0]).normalized() }
        assertEquals(0.0, objectDir dot cueDir, 1e-6)
        // Alvo vai para baixo-direita, a branca para cima-direita.
        assertTrue(objectDir.y > 0 && cueDir.y < 0)
    }

    @Test
    fun drawBringsCueBallBackOnFullHit() {
        val shot = ShotSolver.solve(
            table, cue = Vec2(100.0, 100.0), aim = Vec2(1.0, 0.0), target = Vec2(200.0, 100.0),
            cushions = 1, spin = Spin(follow = -1.0),
        )
        val back = shot.cueAfter!!
        assertTrue(back.direction.x < 0)
        assertEquals(0.0, back.direction.y, 1e-9)
    }

    @Test
    fun followPullsCueBallTowardTargetLine() {
        val stun = ShotSolver.solve(table, Vec2(50.0, 90.0), Vec2(1.0, 0.0), Vec2(200.0, 100.0), cushions = 1)
        val follow = ShotSolver.solve(table, Vec2(50.0, 90.0), Vec2(1.0, 0.0), Vec2(200.0, 100.0), cushions = 1, spin = Spin(follow = 1.0))
        val objectDir = (Vec2(200.0, 100.0) - stun.ghost!!).normalized()
        val stunDir = stun.cueAfter!!.points.let { (it[1] - it[0]).normalized() }
        val followDir = follow.cueAfter!!.points.let { (it[1] - it[0]).normalized() }
        assertTrue((followDir dot objectDir) > (stunDir dot objectDir) + 0.1)
    }

    @Test
    fun rightSpinPushesBallToShootersRightOffTheCushion() {
        // Sobe reto até a tabela de cima: sem efeito volta reto, com efeito à direita vai para +x.
        val plain = ShotSolver.trace(table, Vec2(100.0, 150.0), Vec2(0.0, -1.0), maxCushions = 1)
        val right = ShotSolver.trace(table, Vec2(100.0, 150.0), Vec2(0.0, -1.0), maxCushions = 1, sideSpin = 1.0)
        val left = ShotSolver.trace(table, Vec2(100.0, 150.0), Vec2(0.0, -1.0), maxCushions = 1, sideSpin = -1.0)
        assertEquals(0.0, plain.direction.x, 1e-9)
        assertTrue(right.direction.x > 0.2)
        assertTrue(left.direction.x < -0.2)
        // O efeito não muda a velocidade para longe da tabela, só o ângulo.
        assertTrue(right.direction.y > 0)
    }

    @Test
    fun kickShotHitsTargetAfterCushion() {
        // A branca desce, tabela embaixo e sobe até o alvo.
        val shot = ShotSolver.solve(table, cue = Vec2(60.0, 100.0), aim = Vec2(1.0, 1.0), target = Vec2(250.0, 100.0), cushions = 2)
        assertEquals(1, shot.cue.bounces.size)
        assertEquals(PathEnd.BALL, shot.cue.end)
        assertNotNull(shot.objectBall)
    }

    @Test
    fun overlappingMarkersDoNotBlockTheLine() {
        val shot = ShotSolver.solve(table, cue = Vec2(100.0, 100.0), aim = Vec2(1.0, 0.0), target = Vec2(105.0, 100.0), cushions = 0)
        assertEquals(PathEnd.CUSHION, shot.cue.end)
        assertNull(shot.objectBall)
    }

    @Test
    fun firstBallOnTheWayBecomesTheTarget() {
        // Sem anel do jogo (sem alvo), a primeira bola no caminho é onde a branca bate.
        val shot = ShotSolver.solve(
            table, cue = Vec2(50.0, 100.0), aim = Vec2(1.0, 0.0), target = null, cushions = 2,
            obstacles = listOf(Vec2(150.0, 100.0), Vec2(300.0, 100.0)),
        )
        assertEquals(Vec2(150.0, 100.0), shot.cue.hitBall)
        assertVec(Vec2(130.0, 100.0), shot.ghost!!)
        // E a bola atingida segue reto até encostar na outra: combinação.
        assertEquals(PathEnd.BALL, shot.objectBall!!.end)
        assertVec(Vec2(280.0, 100.0), shot.objectBall!!.points.last())
        val combo = shot.combo!!
        assertVec(Vec2(300.0, 100.0), combo.points.first())
        assertVec(Vec2(390.0, 100.0), combo.points.last())
    }

    @Test
    fun targetFromTheGameWinsOverGrazingObstacle() {
        // Como no print: a linha da branca passa a 1,9 raio de uma bola antes do anel. Pelas posições
        // achadas ela encostaria, mas o anel do jogo mostra que passa e acerta o alvo.
        val obstacle = Vec2(150.0, 119.0)
        val shot = ShotSolver.solve(
            table, cue = Vec2(50.0, 100.0), aim = Vec2(1.0, 0.0), target = Vec2(300.0, 105.0), cushions = 2,
            obstacles = listOf(obstacle),
        )
        assertEquals(Vec2(300.0, 105.0), shot.cue.hitBall)
        assertTrue(shot.ghost!!.x > 280.0)
        assertNotNull(shot.objectBall)
        // Sem alvo, a mesma bola para a branca.
        val blind = ShotSolver.solve(table, Vec2(50.0, 100.0), Vec2(1.0, 0.0), null, 2, obstacles = listOf(obstacle))
        assertEquals(obstacle, blind.cue.hitBall)
    }

    @Test
    fun comboFollowsLineOfCentersAtSecondContact() {
        val shot = ShotSolver.solve(
            table, cue = Vec2(50.0, 100.0), aim = Vec2(1.0, 0.0), target = Vec2(150.0, 100.0), cushions = 1,
            obstacles = listOf(Vec2(250.0, 110.0)),
        )
        val objectPath = shot.objectBall!!
        assertEquals(PathEnd.BALL, objectPath.end)
        val contact = objectPath.points.last()
        assertEquals(20.0, contact.distanceTo(Vec2(250.0, 110.0)), 1e-6)
        val expected = (Vec2(250.0, 110.0) - contact).normalized()
        val combo = shot.combo!!
        assertVec(expected, (combo.points[1] - combo.points[0]).normalized())
    }

    @Test
    fun linesStopAtBallsInTheWay() {
        // Sem alvo: a branca anda até a bola parada no caminho e para ali.
        val shot = ShotSolver.solve(
            table, cue = Vec2(50.0, 100.0), aim = Vec2(1.0, 1.0), target = null, cushions = 3,
            obstacles = listOf(Vec2(120.0, 170.0)),
        )
        assertEquals(PathEnd.BALL, shot.cue.end)
        assertEquals(20.0, shot.cue.points.last().distanceTo(Vec2(120.0, 170.0)), 1e-6)
        // Uma bola longe do caminho não muda nada.
        val clear = ShotSolver.solve(table, Vec2(50.0, 100.0), Vec2(1.0, 0.0), null, 0, obstacles = listOf(Vec2(200.0, 170.0)))
        assertEquals(PathEnd.CUSHION, clear.cue.end)
    }

    @Test
    fun pocketsSitOutsideTheCushionLines() {
        for (pocket in table.pockets) {
            val c = pocket.center
            val outside = c.x < table.left || c.x > table.right || c.y < table.top || c.y > table.bottom
            assertTrue("${pocket.id} fora da mesa", outside)
        }
        assertEquals(6, table.pockets.size)
        val side = table.pocket(PocketId.TOP_MIDDLE).center
        assertTrue(abs(side.x - 200.0) < 1e-9 && side.y < 0)
    }
}
