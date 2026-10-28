package io.github.lucasbrandaocabral.regua.vision

import io.github.lucasbrandaocabral.regua.geometry.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BallFinderTest {

    private fun assertFound(expected: List<Vec2>, found: List<Vec2>, tolerance: Double = 2.0) {
        for (e in expected) {
            val nearest = found.minByOrNull { it.distanceTo(e) }
            assertTrue("bola em $e não achada (achou $found)", nearest != null && nearest.distanceTo(e) < tolerance)
        }
    }

    @Test
    fun findsBallsOnGradientClothAndIgnoresLines() {
        val scene = SyntheticTable()
        val balls = listOf(
            Vec2(250.0, 120.0), Vec2(900.0, 430.0), Vec2(700.0, 100.0),
            // Duas encostadas.
            Vec2(400.0, 420.0), Vec2(430.0, 420.0),
            // Azul no pano azul.
            Vec2(1000.0, 200.0),
        )
        scene.ball(balls[0], 220, 30, 30)
        scene.ball(balls[1], 240, 200, 20, striped = true)
        scene.ball(balls[2], 20, 20, 20)
        scene.ball(balls[3], 230, 120, 20)
        scene.ball(balls[4], 120, 30, 140)
        scene.ball(balls[5], 30, 80, 190)
        val cue = Vec2(600.0, 280.0)
        scene.aimAt(cue, target = null, direction = Vec2.ofAngle(Math.toRadians(200.0)))

        val found = BallFinder().find(scene.frame(), scene.table)
        assertFound(balls + cue, found)
        // Nada além das bolas (a linha de mira e o taco não contam).
        assertEquals(balls.size + 1, found.size)
    }

    @Test
    fun emptyTableHasNoBalls() {
        val scene = SyntheticTable()
        assertTrue(BallFinder().find(scene.frame(), scene.table).isEmpty())
    }

    @Test
    fun detectorReportsOtherBallsWithoutCueAndTarget() {
        val scene = SyntheticTable()
        val cue = Vec2(600.0, 280.0)
        val dir = Vec2(1.0, 0.0)
        val target = cue + dir * 120.0
        val other = Vec2(950.0, 150.0)
        scene.ball(target, 40, 150, 60)
        scene.ball(other, 220, 30, 30)
        scene.aimAt(cue, target, dir)
        val result = AimDetector().detect(scene.frame(), scene.table)!!
        assertEquals(1, result.balls.size)
        assertTrue(result.balls.single().distanceTo(other) < 2.0)
    }
}
