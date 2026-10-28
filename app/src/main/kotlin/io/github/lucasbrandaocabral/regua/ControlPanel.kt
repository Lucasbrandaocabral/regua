package io.github.lucasbrandaocabral.regua

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Coluna de botões flutuante que fica por cima do jogo, ao lado da régua. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ControlPanel(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun toggleAim()
        fun rotate(degrees: Float)
        fun cycleCushions()
        fun toggleTarget()
        fun toggleSpin()
        fun toggleCalibration()
        fun close()
        fun movePanel(dx: Int, dy: Int)
        fun panelMoved()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).roundToInt()

    private val moveButton = iconButton(R.drawable.ic_move, "Mover painel; toque para recolher")
    private val modeButton = textButton()
    private val ccwButton = iconButton(R.drawable.ic_rotate_ccw, "Girar a mira para a esquerda")
    private val cwButton = iconButton(R.drawable.ic_rotate_cw, "Girar a mira para a direita")
    private val cushionButton = textButton()
    private val targetButton = iconButton(R.drawable.ic_target, "Bola alvo")
    private val spinButton = SpinButton(context).apply { contentDescription = "Efeito" }
    private val tableButton = iconButton(R.drawable.ic_table, "Calibrar mesa")
    private val closeButton = iconButton(R.drawable.ic_close, "Fechar régua")

    private val collapsible = listOf(ccwButton, cwButton, cushionButton, targetButton, spinButton, tableButton, closeButton)
    private var collapsed = false

    init {
        orientation = VERTICAL
        val pad = dp(3f)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = dp(12f).toFloat()
            setColor(PANEL_BACKGROUND)
        }
        (listOf(moveButton, modeButton) + collapsible).forEachIndexed { i, button ->
            addView(button, LayoutParams(dp(40f), dp(34f)).apply { if (i > 0) topMargin = dp(3f) })
        }
        closeButton.background = buttonBackground(CLOSE)

        modeButton.setOnClickListener { actions.toggleAim() }
        ccwButton.repeatWhileHeld { actions.rotate(-FINE_STEP_DEGREES) }
        cwButton.repeatWhileHeld { actions.rotate(FINE_STEP_DEGREES) }
        cushionButton.setOnClickListener { actions.cycleCushions() }
        targetButton.setOnClickListener { actions.toggleTarget() }
        spinButton.setOnClickListener { actions.toggleSpin() }
        tableButton.setOnClickListener { actions.toggleCalibration() }
        closeButton.setOnClickListener { actions.close() }
        setUpMoveButton()
    }

    fun render(mode: RulerView.Mode, cushions: Int, targetEnabled: Boolean, spinSide: Float, spinFollow: Float) {
        val editing = mode != RulerView.Mode.PLAY
        modeButton.text = if (editing) "JOGAR" else "MIRAR"
        modeButton.contentDescription = if (editing) "Voltar ao jogo" else "Ajustar a régua"
        modeButton.background = buttonBackground(if (editing) GREEN else IDLE)
        cushionButton.text = SpannableString("$cushions\ntab.").apply {
            setSpan(RelativeSizeSpan(0.7f), length - 4, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        cushionButton.contentDescription = "Tabelas: $cushions"
        targetButton.background = buttonBackground(if (targetEnabled) ORANGE else IDLE)
        spinButton.setSpin(spinSide, spinFollow)
        spinButton.background = buttonBackground(if (mode == RulerView.Mode.SPIN) PURPLE else IDLE)
        tableButton.background = buttonBackground(if (mode == RulerView.Mode.CALIBRATE) TEAL else IDLE)
    }

    private fun iconButton(icon: Int, description: String) = ImageView(context).apply {
        setImageResource(icon)
        scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = dp(7f)
        setPadding(pad, pad, pad, pad)
        contentDescription = description
        background = buttonBackground(IDLE)
    }

    private fun textButton() = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        setLineSpacing(0f, 0.9f)
        background = buttonBackground(IDLE)
    }

    private fun buttonBackground(color: Int): Drawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(8f).toFloat()
            setColor(color)
        }
        return RippleDrawable(ColorStateList.valueOf(PRESSED), shape, null)
    }

    /** Executa [action] ao tocar e repete enquanto o dedo continuar no botão. */
    private fun View.repeatWhileHeld(action: () -> Unit) {
        val button = this
        val repeat = object : Runnable {
            override fun run() {
                action()
                button.postDelayed(this, REPEAT_INTERVAL_MS)
            }
        }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    button.isPressed = true
                    action()
                    button.postDelayed(repeat, REPEAT_DELAY_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.isPressed = false
                    button.removeCallbacks(repeat)
                }
            }
            true
        }
    }

    private fun setUpMoveButton() {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var lastX = 0f
        var lastY = 0f
        var dragging = false
        moveButton.setOnTouchListener { button, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    lastX = startX
                    lastY = startY
                    dragging = false
                    button.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && hypot(event.rawX - startX, event.rawY - startY) > slop) dragging = true
                    if (dragging) {
                        val dx = (event.rawX - lastX).roundToInt()
                        val dy = (event.rawY - lastY).roundToInt()
                        if (dx != 0 || dy != 0) {
                            actions.movePanel(dx, dy)
                            lastX += dx
                            lastY += dy
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    button.isPressed = false
                    if (dragging) {
                        actions.panelMoved()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        toggleCollapsed()
                    }
                }
            }
            true
        }
    }

    private fun toggleCollapsed() {
        collapsed = !collapsed
        for (button in collapsible) button.visibility = if (collapsed) View.GONE else View.VISIBLE
    }

    /** Bolinha branca com o ponto vermelho do efeito, como o ícone do jogo. */
    private class SpinButton(context: Context) : View(context) {
        private var side = 0f
        private var follow = 0f
        private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }

        fun setSpin(side: Float, follow: Float) {
            this.side = side
            this.follow = follow
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val r = min(width, height) * 0.32f
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, r, ballPaint)
            canvas.drawCircle(cx + side * r * 0.7f, cy - follow * r * 0.7f, r * 0.28f, dotPaint)
        }
    }

    private companion object {
        const val FINE_STEP_DEGREES = 0.1f
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 50L

        const val PANEL_BACKGROUND = 0xB3101820.toInt()
        const val IDLE = 0x33FFFFFF
        const val PRESSED = 0x66FFFFFF
        const val GREEN = 0xFF2E7D32.toInt()
        const val ORANGE = 0xFFE65100.toInt()
        const val PURPLE = 0xFF6A1B9A.toInt()
        const val TEAL = 0xFF00838F.toInt()
        const val CLOSE = 0x99B71C1C.toInt()
    }
}
