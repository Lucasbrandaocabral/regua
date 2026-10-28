package io.github.lucasbrandaocabral.regua

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import io.github.lucasbrandaocabral.regua.geometry.BallPath
import io.github.lucasbrandaocabral.regua.geometry.PathEnd
import io.github.lucasbrandaocabral.regua.geometry.ShotSolver
import io.github.lucasbrandaocabral.regua.geometry.Spin
import io.github.lucasbrandaocabral.regua.geometry.Table
import io.github.lucasbrandaocabral.regua.geometry.Vec2
import io.github.lucasbrandaocabral.regua.vision.AimDetector
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Janela transparente em tela cheia que desenha a rota da bola por cima do jogo.
 *
 * Em [Mode.PLAY] a janela não recebe toques (eles passam direto para o jogo). Nos outros modos os
 * toques movem a branca, o alvo e a mira ([Mode.AIM]), as bordas da mesa ([Mode.CALIBRATE]) ou o
 * ponto de efeito ([Mode.SPIN]).
 */
@SuppressLint("ViewConstructor")
class RulerView(context: Context, private val settings: RulerSettings) : View(context) {

    enum class Mode { PLAY, AIM, CALIBRATE, SPIN }

    var mode = Mode.PLAY
        set(value) {
            field = value
            drag = Drag.NONE
            invalidate()
        }

    /** O usuário terminou de arrastar algo: hora de salvar. */
    var onEdited: (() -> Unit)? = null

    /** Toque fora da bola do efeito: pede para fechar o controle. */
    var onSpinPadDismissed: (() -> Unit)? = null

    /** A janela mudou de tamanho (a tela girou). */
    var onResized: (() -> Unit)? = null

    /** Ligado: em [Mode.PLAY] a régua segue a mira que o jogo desenha, achada na captura de tela. */
    var autoTracking = false
        set(value) {
            field = value
            invalidate()
        }

    /** Última mira achada na tela, em coordenadas desta janela. */
    var autoAim: AimDetector.Result? = null
        set(value) {
            field = value
            invalidate()
        }

    val isLandscape: Boolean get() = width > height

    private enum class Drag {
        NONE, CUE, TARGET, AIM, SPIN, BALL_SIZE,
        LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    }

    private class Handle(val drag: Drag, val pos: Vec2)

    private var drag = Drag.NONE
    private var dragOffset = Vec2.ZERO
    private var aimAtDown = 0.0
    private var fingerAngleAtDown = 0.0

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private fun strokePaint(color: Int, widthDp: Float, dashed: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = dp(widthDp)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }

    private val cuePathPaint = strokePaint(YELLOW, 2f)
    private val objectPathPaint = strokePaint(CYAN, 2f)
    private val pottedPaint = strokePaint(GREEN, 2.5f)
    private val foulPaint = strokePaint(RED, 2f)
    private val cueAfterPaint = strokePaint(Color.WHITE, 1.5f, dashed = true)
    private val autoCueAfterPaint = strokePaint(LILAC, 1.5f, dashed = true)
    private val scratchPaint = strokePaint(RED, 2f, dashed = true)
    private val ghostPaint = strokePaint(Color.WHITE, 1.5f, dashed = true)
    private val cueMarkerPaint = strokePaint(Color.WHITE, 1.5f)
    private val targetMarkerPaint = strokePaint(ORANGE, 2f)
    private val shadowPaint = strokePaint(SHADOW, 1f)
    private val ringPaint = strokePaint(GREEN, 3f)
    private val calibrationPaint = strokePaint(CYAN, 1.5f)
    private val calibrationInnerPaint = strokePaint(CYAN_SOFT, 1f, dashed = true)
    private val pocketGuidePaint = strokePaint(Color.WHITE, 1.5f, dashed = true)
    private val mouthPaint = strokePaint(GREEN, 4f)
    private val spinGridPaint = strokePaint(SPIN_GRID, 1f)
    private val handleStrokePaint = strokePaint(Color.BLACK, 1.5f)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        setShadowLayer(dp(2f), 0f, 0f, Color.BLACK)
    }
    private val path = Path()
    private val clip = Path()
    private val rect = RectF()

    private val w: Double get() = width.toDouble()
    private val h: Double get() = height.toDouble()

    fun currentTable() = table()

    private fun table() = Table(
        left = settings.tableLeft * w,
        top = settings.tableTop * h,
        right = settings.tableRight * w,
        bottom = settings.tableBottom * h,
        ballRadius = settings.ballRadius * h,
    )

    private var cue: Vec2
        get() = Vec2(settings.cueX * w, settings.cueY * h)
        set(p) {
            settings.cueX = (p.x / w).toFloat()
            settings.cueY = (p.y / h).toFloat()
        }

    private var target: Vec2
        get() = Vec2(settings.targetX * w, settings.targetY * h)
        set(p) {
            settings.targetX = (p.x / w).toFloat()
            settings.targetY = (p.y / h).toFloat()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        onResized?.invoke()
    }

    fun rotateAim(degrees: Float) {
        settings.aimDegrees = normalizeDegrees(settings.aimDegrees + degrees)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // A régua só faz sentido com o jogo deitado; com a tela em pé (outros apps) ela some.
        if (!isLandscape) return
        when (mode) {
            Mode.CALIBRATE -> drawCalibration(canvas)
            Mode.SPIN -> {
                drawShot(canvas)
                drawSpinPad(canvas)
            }
            Mode.PLAY -> if (autoTracking) drawAutoShot(canvas) else drawShot(canvas)
            Mode.AIM -> drawShot(canvas)
        }
    }

    private fun spin() = Spin(settings.spinSide.toDouble(), settings.spinFollow.toDouble())

    /**
     * Mira automática: parte da branca e da direção que o jogo está mostrando. O que a régua desenha
     * também aparece na captura do próximo quadro, então ela não desenha nada branco e deixa livres a
     * branca, a linha do jogo, o anel e a linha da bola alvo (senão, numa tacada que volta pela mesma
     * reta, cobriria a linha do jogo e perderia a mira). Ela só continua de onde as linhas do jogo acabam.
     */
    private fun drawAutoShot(canvas: Canvas) {
        val aim = autoAim ?: return
        val table = table()
        val r = table.ballRadius
        val targetBall = if (settings.targetEnabled) aim.objectBall else null
        val shot = ShotSolver.solve(table, aim.cue, aim.direction, targetBall, settings.cushions, spin())

        canvas.save()
        clipOutCircle(canvas, aim.cue, 1.6 * r)
        clipOutBand(canvas, aim.cue, aim.cue + aim.direction * aim.guideLength, 0.5 * r)
        aim.ghost?.let { ghost ->
            clipOutCircle(canvas, ghost, 3 * r)
            aim.objectBall?.let { o -> clipOutBand(canvas, o, o + (o - ghost).normalized() * aim.objectGuideLength, 0.5 * r) }
        }
        val cuePaint = when {
            shot.cue.end != PathEnd.POCKET -> cuePathPaint
            targetBall != null -> foulPaint
            else -> pottedPaint
        }
        drawPath(canvas, shot.cue, cuePaint, skip = aim.guideLength + 0.6 * r)
        shot.objectBall?.let {
            drawPath(canvas, it, if (it.end == PathEnd.POCKET) pottedPaint else objectPathPaint, skip = max(1.5 * r, aim.objectGuideLength + 0.6 * r))
        }
        shot.cueAfter?.let { drawPath(canvas, it, if (it.end == PathEnd.POCKET) scratchPaint else autoCueAfterPaint, skip = r) }
        canvas.restore()
    }

    private fun clipOutCircle(canvas: Canvas, c: Vec2, radius: Double) {
        clip.rewind()
        clip.addCircle(c.x.f, c.y.f, radius.f, Path.Direction.CW)
        canvas.clipOutPath(clip)
    }

    /** Tira do desenho uma faixa de meia largura [half] em volta do segmento de [a] até [b]. */
    private fun clipOutBand(canvas: Canvas, a: Vec2, b: Vec2, half: Double) {
        val u = (b - a).normalized()
        if (u == Vec2.ZERO) return
        val n = Vec2(-u.y, u.x) * half
        val start = a - u * half
        val end = b + u * half
        clip.rewind()
        clip.moveTo((start - n).x.f, (start - n).y.f)
        clip.lineTo((end - n).x.f, (end - n).y.f)
        clip.lineTo((end + n).x.f, (end + n).y.f)
        clip.lineTo((start + n).x.f, (start + n).y.f)
        clip.close()
        canvas.clipOutPath(clip)
    }

    private fun drawShot(canvas: Canvas) {
        val table = table()
        val r = table.ballRadius.toFloat()
        val targetBall = if (settings.targetEnabled) target else null
        val shot = ShotSolver.solve(
            table, cue, Vec2.ofAngle(Math.toRadians(settings.aimDegrees.toDouble())), targetBall, settings.cushions, spin(),
        )

        // Com alvo marcado, a branca caindo direto numa caçapa é falta; sem alvo a régua é só uma linha.
        val cuePaint = when {
            shot.cue.end != PathEnd.POCKET -> cuePathPaint
            targetBall != null -> foulPaint
            else -> pottedPaint
        }
        drawPath(canvas, shot.cue, cuePaint)
        shot.objectBall?.let { drawPath(canvas, it, if (it.end == PathEnd.POCKET) pottedPaint else objectPathPaint) }
        shot.cueAfter?.let { drawPath(canvas, it, if (it.end == PathEnd.POCKET) scratchPaint else cueAfterPaint) }
        shot.ghost?.let { g -> stroke(ghostPaint) { canvas.drawCircle(g.x.f, g.y.f, r, it) } }

        val editing = mode == Mode.AIM
        drawBallMarker(canvas, cue, r, cueMarkerPaint, editing)
        targetBall?.let { drawBallMarker(canvas, it, r, targetMarkerPaint, editing) }

        if (editing) {
            val ghost = shot.ghost
            val cut = shot.cutAngle
            if (ghost != null && cut != null) {
                canvas.drawText("${cut.roundToInt()}°", (ghost.x + r * 1.5).f, (ghost.y - r * 1.5).f, textPaint)
            }
            drawHint(
                canvas,
                "Arraste a branca e o alvo até as bolas do jogo. Arraste em outro lugar para girar a mira.",
                "Toque em JOGAR para voltar ao jogo (a linha continua na tela).",
            )
        }
    }

    /** Desenha o caminho a partir de [skip] pixels do começo (o trecho antes disso fica de fora). */
    private fun drawPath(canvas: Canvas, ball: BallPath, paint: Paint, skip: Double = 0.0) {
        path.rewind()
        fillPaint.color = paint.color
        var travelled = 0.0
        var started = false
        for (i in 1 until ball.points.size) {
            val a = ball.points[i - 1]
            val b = ball.points[i]
            val length = a.distanceTo(b)
            if (length == 0.0) continue
            if (travelled + length > skip) {
                if (!started) {
                    val start = a + (b - a) * ((skip - travelled) / length).coerceIn(0.0, 1.0)
                    path.moveTo(start.x.f, start.y.f)
                    started = true
                }
                path.lineTo(b.x.f, b.y.f)
            }
            travelled += length
        }
        if (started) stroke(paint) { canvas.drawPath(path, it) }
        // Pontos de batida nas tabelas, só no trecho desenhado.
        travelled = 0.0
        for (i in 1 until ball.points.size) {
            travelled += ball.points[i - 1].distanceTo(ball.points[i])
            val p = ball.points[i]
            if (travelled > skip && p in ball.bounces) canvas.drawCircle(p.x.f, p.y.f, dp(3f), fillPaint)
        }
        ball.pocket?.let { pocket ->
            ringPaint.color = paint.color
            stroke(ringPaint) { canvas.drawCircle(pocket.center.x.f, pocket.center.y.f, pocket.radius.f, it) }
        }
    }

    /** Desenha com um contorno escuro por baixo, para a linha aparecer em mesa de qualquer cor. */
    private inline fun stroke(paint: Paint, draw: (Paint) -> Unit) {
        shadowPaint.strokeWidth = paint.strokeWidth + dp(2f)
        shadowPaint.pathEffect = paint.pathEffect
        draw(shadowPaint)
        draw(paint)
    }

    private fun drawBallMarker(canvas: Canvas, c: Vec2, r: Float, paint: Paint, editing: Boolean) {
        val x = c.x.f
        val y = c.y.f
        if (editing) {
            fillPaint.color = (paint.color and 0x00FFFFFF) or 0x40000000
            canvas.drawCircle(x, y, r, fillPaint)
        }
        stroke(paint) { canvas.drawCircle(x, y, r, it) }
        // Mira no centro, para encaixar o marcador em cima da bola do jogo.
        val s = r * 0.4f
        stroke(paint) {
            canvas.drawLine(x - s, y, x + s, y, it)
            canvas.drawLine(x, y - s, x, y + s, it)
        }
    }

    private fun drawCalibration(canvas: Canvas) {
        val t = table()
        val r = t.ballRadius.toFloat()

        // Bordas das tabelas e, tracejado, o retângulo por onde anda o centro da bola.
        canvas.drawRect(t.left.f, t.top.f, t.right.f, t.bottom.f, calibrationPaint)
        canvas.drawRect(t.minX.f, t.minY.f, t.maxX.f, t.maxY.f, calibrationInnerPaint)

        // Trechos de tabela que são boca de caçapa (bola que chega ali cai).
        val corner = ((1 + Table.CORNER_MOUTH) * t.ballRadius).f
        val side = (Table.SIDE_MOUTH * t.ballRadius).f
        for (y in floatArrayOf(t.top.f, t.bottom.f)) {
            canvas.drawLine(t.left.f, y, t.left.f + corner, y, mouthPaint)
            canvas.drawLine(t.right.f - corner, y, t.right.f, y, mouthPaint)
            canvas.drawLine(t.centerX.f - side, y, t.centerX.f + side, y, mouthPaint)
        }
        for (x in floatArrayOf(t.left.f, t.right.f)) {
            canvas.drawLine(x, t.top.f, x, t.top.f + corner, mouthPaint)
            canvas.drawLine(x, t.bottom.f - corner, x, t.bottom.f, mouthPaint)
        }
        for (pocket in t.pockets) {
            canvas.drawCircle(pocket.center.x.f, pocket.center.y.f, pocket.radius.f, pocketGuidePaint)
        }

        // Medidor da bola: fica sobre a branca, com uma alça ao lado para mudar o tamanho.
        val c = cue
        stroke(cueMarkerPaint) {
            canvas.drawCircle(c.x.f, c.y.f, r, it)
            canvas.drawLine(c.x.f + r, c.y.f, c.x.f + r + dp(KNOB_GAP_DP), c.y.f, it)
        }
        for (handle in calibrationHandles(t)) {
            fillPaint.color = Color.WHITE
            canvas.drawCircle(handle.pos.x.f, handle.pos.y.f, dp(7f), fillPaint)
            canvas.drawCircle(handle.pos.x.f, handle.pos.y.f, dp(7f), handleStrokePaint)
        }

        drawHint(
            canvas,
            "Arraste os pontos até a borda das tabelas, onde a bola encosta.",
            "Os círculos tracejados devem ficar em cima das caçapas.",
            "Ponha o círculo branco sobre a bola branca e puxe a alça até ele ficar do tamanho dela.",
            "Toque em JOGAR quando terminar.",
        )
    }

    private fun calibrationHandles(t: Table): List<Handle> {
        val midY = (t.top + t.bottom) / 2
        val quarterX = t.left + t.width / 4
        val c = cue
        return listOf(
            Handle(Drag.TOP_LEFT, Vec2(t.left, t.top)),
            Handle(Drag.TOP_RIGHT, Vec2(t.right, t.top)),
            Handle(Drag.BOTTOM_LEFT, Vec2(t.left, t.bottom)),
            Handle(Drag.BOTTOM_RIGHT, Vec2(t.right, t.bottom)),
            Handle(Drag.LEFT, Vec2(t.left, midY)),
            Handle(Drag.RIGHT, Vec2(t.right, midY)),
            Handle(Drag.TOP, Vec2(quarterX, t.top)),
            Handle(Drag.BOTTOM, Vec2(quarterX, t.bottom)),
            Handle(Drag.BALL_SIZE, Vec2(c.x + t.ballRadius + dp(KNOB_GAP_DP), c.y)),
        )
    }

    private fun spinPadRadius() = min(width, height) * 0.22f

    private fun drawSpinPad(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = spinPadRadius()
        fillPaint.color = SPIN_BACKDROP
        canvas.drawCircle(cx, cy, radius * SPIN_PAD_REACH, fillPaint)
        fillPaint.color = SPIN_BALL
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawLine(cx - radius, cy, cx + radius, cy, spinGridPaint)
        canvas.drawLine(cx, cy - radius, cx, cy + radius, spinGridPaint)
        canvas.drawCircle(cx, cy, radius * SPIN_LIMIT, spinGridPaint)
        fillPaint.color = SPIN_DOT
        canvas.drawCircle(
            cx + settings.spinSide * radius * SPIN_LIMIT,
            cy - settings.spinFollow * radius * SPIN_LIMIT,
            radius * 0.11f, fillPaint,
        )
        drawHint(
            canvas,
            "Toque onde você colocou o ponto vermelho no jogo.",
            "Toque no meio para tirar o efeito, ou fora da bola para fechar.",
        )
    }

    private fun drawHint(canvas: Canvas, vararg lines: String) {
        val lineHeight = textPaint.fontSpacing
        val pad = dp(8f)
        val textWidth = lines.maxOf { textPaint.measureText(it) }
        val left = (width - textWidth) / 2 - pad
        val top = dp(6f)
        rect.set(left, top, left + textWidth + 2 * pad, top + lines.size * lineHeight + 2 * pad)
        fillPaint.color = HINT_BACKGROUND
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fillPaint)
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, rect.left + pad, rect.top + pad + i * lineHeight - textPaint.ascent(), textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode == Mode.PLAY) return false
        val p = Vec2(event.x.toDouble(), event.y.toDouble())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> startDrag(p)
            MotionEvent.ACTION_MOVE -> moveDrag(p)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) onEdited?.invoke()
                drag = Drag.NONE
            }
        }
        invalidate()
        return true
    }

    private fun startDrag(p: Vec2) {
        drag = when (mode) {
            Mode.AIM -> pickAimHandle(p)
            Mode.CALIBRATE -> pickCalibrationHandle(p)
            Mode.SPIN -> {
                if (p.distanceTo(Vec2(w / 2, h / 2)) > spinPadRadius() * SPIN_PAD_REACH) {
                    onSpinPadDismissed?.invoke()
                    Drag.NONE
                } else {
                    Drag.SPIN
                }
            }
            Mode.PLAY -> Drag.NONE
        }
        if (drag == Drag.AIM) {
            aimAtDown = settings.aimDegrees.toDouble()
            fingerAngleAtDown = angleDegrees(p - cue)
        }
        if (drag == Drag.SPIN) setSpin(p)
    }

    private fun pickAimHandle(p: Vec2): Drag {
        val reach = max(settings.ballRadius * h * 1.8, dp(26f).toDouble())
        val toTarget = if (settings.targetEnabled) p.distanceTo(target) else Double.MAX_VALUE
        val toCue = p.distanceTo(cue)
        return when {
            toTarget <= reach && toTarget <= toCue -> {
                dragOffset = p - target
                Drag.TARGET
            }
            toCue <= reach -> {
                dragOffset = p - cue
                Drag.CUE
            }
            // Longe das bolas o dedo gira a mira em volta da branca; quanto mais longe, mais fino.
            else -> Drag.AIM
        }
    }

    private fun pickCalibrationHandle(p: Vec2): Drag {
        val t = table()
        val c = cue
        if (p.distanceTo(c) <= max(t.ballRadius, dp(16f).toDouble())) {
            dragOffset = p - c
            return Drag.CUE
        }
        val nearest = calibrationHandles(t).minByOrNull { it.pos.distanceTo(p) } ?: return Drag.NONE
        if (nearest.pos.distanceTo(p) > dp(30f)) return Drag.NONE
        dragOffset = p - nearest.pos
        return nearest.drag
    }

    private fun moveDrag(p: Vec2) {
        val q = p - dragOffset
        when (drag) {
            Drag.NONE -> Unit
            Drag.CUE -> cue = clampToScreen(q)
            Drag.TARGET -> target = clampToScreen(q)
            Drag.AIM -> settings.aimDegrees =
                normalizeDegrees((aimAtDown + angleDegrees(p - cue) - fingerAngleAtDown).toFloat())
            Drag.SPIN -> setSpin(p)
            Drag.BALL_SIZE -> {
                val radius = (q.distanceTo(cue) - dp(KNOB_GAP_DP)).coerceIn(dp(3f).toDouble(), h * 0.1)
                settings.ballRadius = (radius / h).toFloat()
            }
            else -> moveEdges(q)
        }
    }

    private fun moveEdges(q: Vec2) {
        val fx = (q.x / w).toFloat()
        val fy = (q.y / h).toFloat()
        if (drag == Drag.LEFT || drag == Drag.TOP_LEFT || drag == Drag.BOTTOM_LEFT) {
            settings.tableLeft = fx.coerceIn(0f, settings.tableRight - MIN_TABLE_FRACTION)
        }
        if (drag == Drag.RIGHT || drag == Drag.TOP_RIGHT || drag == Drag.BOTTOM_RIGHT) {
            settings.tableRight = fx.coerceIn(settings.tableLeft + MIN_TABLE_FRACTION, 1f)
        }
        if (drag == Drag.TOP || drag == Drag.TOP_LEFT || drag == Drag.TOP_RIGHT) {
            settings.tableTop = fy.coerceIn(0f, settings.tableBottom - MIN_TABLE_FRACTION)
        }
        if (drag == Drag.BOTTOM || drag == Drag.BOTTOM_LEFT || drag == Drag.BOTTOM_RIGHT) {
            settings.tableBottom = fy.coerceIn(settings.tableTop + MIN_TABLE_FRACTION, 1f)
        }
    }

    private fun setSpin(p: Vec2) {
        val reach = spinPadRadius() * SPIN_LIMIT
        var side = (p.x - w / 2) / reach
        var follow = -(p.y - h / 2) / reach
        val length = hypot(side, follow)
        if (length > 1) {
            side /= length
            follow /= length
        }
        if (length < SPIN_DEAD_ZONE) {
            side = 0.0
            follow = 0.0
        }
        settings.spinSide = side.toFloat()
        settings.spinFollow = follow.toFloat()
    }

    private fun clampToScreen(v: Vec2) = Vec2(v.x.coerceIn(0.0, w), v.y.coerceIn(0.0, h))

    private fun angleDegrees(v: Vec2) = Math.toDegrees(atan2(v.y, v.x))

    private fun normalizeDegrees(degrees: Float) = ((degrees % 360f) + 360f) % 360f

    private val Double.f: Float get() = toFloat()

    private companion object {
        const val KNOB_GAP_DP = 14f
        const val MIN_TABLE_FRACTION = 0.1f

        /** O ponto de efeito vai até 75% do raio da bola, como no controle do jogo. */
        const val SPIN_LIMIT = 0.75f
        const val SPIN_DEAD_ZONE = 0.12
        const val SPIN_PAD_REACH = 1.25f

        const val YELLOW = 0xFFFFEB3B.toInt()
        const val CYAN = 0xFF18FFFF.toInt()
        const val CYAN_SOFT = 0xB318FFFF.toInt()
        const val GREEN = 0xFF00E676.toInt()
        const val RED = 0xFFFF5252.toInt()
        const val ORANGE = 0xFFFF9100.toInt()
        const val LILAC = 0xFFE040FB.toInt()
        const val SHADOW = 0x99000000.toInt()
        const val HINT_BACKGROUND = 0xCC000000.toInt()
        const val SPIN_BACKDROP = 0x99000000.toInt()
        const val SPIN_BALL = 0xFFF5F5F5.toInt()
        const val SPIN_GRID = 0xFF9E9E9E.toInt()
        const val SPIN_DOT = 0xFFE53935.toInt()
    }
}
