package io.github.lucasbrandaocabral.regua.vision

import io.github.lucasbrandaocabral.regua.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos

class AimDetectorTest {
    private val detector = AimDetector()

    private fun angleBetween(a: Vec2, b: Vec2) = Math.toDegrees(acos((a.normalized() dot b.normalized()).coerceIn(-1.0, 1.0)))

    /** Distância do ponto até a reta que passa por [origin] na direção [dir]. */
    private fun offLine(p: Vec2, origin: Vec2, dir: Vec2): Double {
        val d = p - origin
        return abs(d.x * dir.y - d.y * dir.x)
    }

    @Test
    fun followsGuideAtManyAngles() {
        for (degrees in listOf(0.0, 17.3, 63.0, 123.4, 180.0, 251.7, 333.3)) {
            val scene = SyntheticTable()
            val cue = Vec2(600.0, 280.0)
            val dir = Vec2.ofAngle(Math.toRadians(degrees))
            scene.ball(Vec2(250.0, 120.0), 220, 30, 30)
            scene.ball(Vec2(900.0, 430.0), 240, 200, 20, striped = true)
            scene.aimAt(cue, target = null, direction = dir)

            val result = detector.detect(scene.frame(), scene.table)
            assertNotNull("sem resultado em $degrees°", result)
            result!!
            assertTrue("ângulo errado em $degrees°: ${angleBetween(result.direction, dir)}", angleBetween(result.direction, dir) < 0.15)
            assertTrue("branca fora da linha em $degrees°", offLine(result.cue, cue, dir) < 1.0)
            assertTrue("branca longe em $degrees°", result.cue.distanceTo(cue) < 3.0)
            assertEquals("comprimento em $degrees°", 12 * scene.r, result.guideLength, 3.0)
            assertNull(result.ghost)
        }
    }

    @Test
    fun findsGhostAndObjectBall() {
        for ((degrees, offset) in listOf(0.0 to 0.0, 10.0 to 9.0, 200.0 to -14.0, 300.0 to 20.0)) {
            val scene = SyntheticTable()
            val cue = Vec2(600.0, 280.0)
            val dir = Vec2.ofAngle(Math.toRadians(degrees))
            val side = Vec2(-dir.y, dir.x)
            // Alvo 8 raios à frente, deslocado para o lado: corte.
            val target = cue + dir * (8 * scene.r) + side * offset
            scene.ball(target, 40, 150, 60)
            scene.aimAt(cue, target, dir)

            val result = detector.detect(scene.frame(), scene.table)!!
            assertTrue("ângulo $degrees°", angleBetween(result.direction, dir) < 0.15)
            val ghost = assertNotNullAnd(result.ghost, "anel em $degrees°")
            assertEquals("anel a 2 raios do alvo em $degrees°", 2 * scene.r, ghost.distanceTo(target), 1.5)
            val objectBall = assertNotNullAnd(result.objectBall, "alvo em $degrees°")
            // A bola alvo sai na linha que liga o ponto de choque verdadeiro ao centro dela.
            val m = cue - target
            val b = m dot dir
            val trueGhost = cue + dir * (-b - kotlin.math.sqrt(b * b - ((m dot m) - 4 * scene.r * scene.r)))
            val expected = (target - trueGhost).normalized()
            val found = (objectBall - ghost).normalized()
            assertTrue("direção do alvo em $degrees°: ${angleBetween(found, expected)}", angleBetween(found, expected) < 0.3)
            assertEquals("linha do alvo em $degrees°", 6 * scene.r, result.objectGuideLength, 3.0)
        }
    }

    @Test
    fun nearlyFullHitStillFindsRingAndTarget() {
        // A linha que sai da bola alvo quase continua a linha de mira: não pode puxar a direção.
        val scene = SyntheticTable()
        val cue = Vec2(500.0, 300.0)
        val dir = Vec2.ofAngle(Math.toRadians(250.0))
        val target = cue + dir * (9 * scene.r) + Vec2(-dir.y, dir.x) * 5.0
        scene.ball(target, 220, 40, 40)
        scene.aimAt(cue, target, dir)
        val result = detector.detect(scene.frame(), scene.table)!!
        assertTrue(angleBetween(result.direction, dir) < 0.15)
        assertNotNull(result.ghost)
        assertNotNull(result.objectBall)
    }

    @Test
    fun whiteBlobOnCueBallDoesNotBreakTheLine() {
        // Como a mão do "bola na mão": o centro achado da branca sai alguns pixels da linha.
        val scene = SyntheticTable()
        val cue = Vec2(500.0, 300.0)
        val dir = Vec2.ofAngle(Math.toRadians(-8.0))
        scene.aimAt(cue, target = null, direction = dir)
        scene.ball(cue + Vec2(0.9 * scene.r, 0.9 * scene.r), 245, 245, 245)
        val result = detector.detect(scene.frame(), scene.table)!!
        assertTrue("ângulo: ${angleBetween(result.direction, dir)}", angleBetween(result.direction, dir) < 0.15)
        assertTrue(offLine(result.cue, cue, dir) < 1.0)
    }

    @Test
    fun previousFrameHelpsFollowRotation() {
        val cue = Vec2(600.0, 280.0)
        val before = SyntheticTable().apply { aimAt(cue, target = null, direction = Vec2.ofAngle(Math.toRadians(40.0))) }
        val previous = detector.detect(before.frame(), before.table)!!
        val dir = Vec2.ofAngle(Math.toRadians(46.0))
        val after = SyntheticTable().apply { aimAt(cue, target = null, direction = dir) }
        val result = detector.detect(after.frame(), after.table, previous = previous)!!
        assertTrue(angleBetween(result.direction, dir) < 0.15)

        // Branca em outro lugar (depois de uma tacada): a dica falha e ele procura na mesa toda.
        val moved = Vec2(300.0, 400.0)
        val elsewhere = SyntheticTable().apply { aimAt(moved, target = null, direction = Vec2(-1.0, 0.0)) }
        val found = detector.detect(elsewhere.frame(), elsewhere.table, previous = previous)!!
        assertTrue(found.cue.distanceTo(moved) < 3.0)
        assertTrue(angleBetween(found.direction, Vec2(-1.0, 0.0)) < 0.15)
    }

    @Test
    fun ringOnCushionIsNotATarget() {
        val scene = SyntheticTable()
        val cue = Vec2(600.0, 280.0)
        val dir = Vec2(0.0, -1.0)
        val ghost = Vec2(600.0, scene.table.minY)
        scene.aimAt(cue, target = null, direction = dir, ghostOnCushion = ghost)
        val result = detector.detect(scene.frame(), scene.table)!!
        assertTrue(angleBetween(result.direction, dir) < 0.15)
        assertNull(result.ghost)
        assertNull(result.objectBall)
    }

    @Test
    fun whiteCueShaftIsNotMistakenForTheGuide() {
        val scene = SyntheticTable()
        val cue = Vec2(500.0, 300.0)
        val dir = Vec2.ofAngle(Math.toRadians(40.0))
        scene.cue(cue, dir, shaft = Frame.rgb(235, 232, 225))
        scene.cueBall(cue)
        scene.guide(cue, cue + dir * (10 * scene.r))
        val result = detector.detect(scene.frame(), scene.table)!!
        assertTrue(angleBetween(result.direction, dir) < 0.15)
    }

    @Test
    fun nothingWithoutGuideOrCueBall() {
        val empty = SyntheticTable()
        empty.ball(Vec2(400.0, 200.0), 220, 30, 30)
        assertNull(detector.detect(empty.frame(), empty.table))

        // Branca parada sem linha de mira (vez do adversário).
        val idle = SyntheticTable()
        idle.cueBall(Vec2(500.0, 250.0))
        assertNull(detector.detect(idle.frame(), idle.table))
    }

    private fun <T> assertNotNullAnd(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }
}
