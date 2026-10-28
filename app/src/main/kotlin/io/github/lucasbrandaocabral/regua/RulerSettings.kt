package io.github.lucasbrandaocabral.regua

import android.content.Context

/**
 * Tudo o que a régua lembra entre um uso e outro.
 *
 * Posições são frações da largura (x) e da altura (y) da tela em paisagem, para continuarem certas
 * mesmo que a janela mude de tamanho. O raio da bola é fração da altura.
 */
class RulerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("regua", Context.MODE_PRIVATE)

    var tableLeft = prefs.getFloat(KEY_TABLE_LEFT, DEFAULT_TABLE_LEFT)
    var tableTop = prefs.getFloat(KEY_TABLE_TOP, DEFAULT_TABLE_TOP)
    var tableRight = prefs.getFloat(KEY_TABLE_RIGHT, DEFAULT_TABLE_RIGHT)
    var tableBottom = prefs.getFloat(KEY_TABLE_BOTTOM, DEFAULT_TABLE_BOTTOM)
    var ballRadius = prefs.getFloat(KEY_BALL_RADIUS, DEFAULT_BALL_RADIUS)

    var cueX = prefs.getFloat(KEY_CUE_X, 0.348f)
    var cueY = prefs.getFloat(KEY_CUE_Y, 0.575f)
    var targetX = prefs.getFloat(KEY_TARGET_X, 0.62f)
    var targetY = prefs.getFloat(KEY_TARGET_Y, 0.45f)
    var targetEnabled = prefs.getBoolean(KEY_TARGET_ENABLED, true)

    /** Direção da mira em graus: 0 aponta para a direita e cresce no sentido horário. */
    var aimDegrees = prefs.getFloat(KEY_AIM_DEGREES, 0f)
    var cushions = prefs.getInt(KEY_CUSHIONS, 2)

    var spinSide = prefs.getFloat(KEY_SPIN_SIDE, 0f)
    var spinFollow = prefs.getFloat(KEY_SPIN_FOLLOW, 0f)

    /** Posição do painel em pixels; negativo enquanto o usuário não mover o painel. */
    var panelX = prefs.getInt(KEY_PANEL_X, -1)
    var panelY = prefs.getInt(KEY_PANEL_Y, -1)

    fun save() {
        prefs.edit()
            .putFloat(KEY_TABLE_LEFT, tableLeft)
            .putFloat(KEY_TABLE_TOP, tableTop)
            .putFloat(KEY_TABLE_RIGHT, tableRight)
            .putFloat(KEY_TABLE_BOTTOM, tableBottom)
            .putFloat(KEY_BALL_RADIUS, ballRadius)
            .putFloat(KEY_CUE_X, cueX)
            .putFloat(KEY_CUE_Y, cueY)
            .putFloat(KEY_TARGET_X, targetX)
            .putFloat(KEY_TARGET_Y, targetY)
            .putBoolean(KEY_TARGET_ENABLED, targetEnabled)
            .putFloat(KEY_AIM_DEGREES, aimDegrees)
            .putInt(KEY_CUSHIONS, cushions)
            .putFloat(KEY_SPIN_SIDE, spinSide)
            .putFloat(KEY_SPIN_FOLLOW, spinFollow)
            .putInt(KEY_PANEL_X, panelX)
            .putInt(KEY_PANEL_Y, panelY)
            .apply()
    }

    fun resetCalibration() {
        tableLeft = DEFAULT_TABLE_LEFT
        tableTop = DEFAULT_TABLE_TOP
        tableRight = DEFAULT_TABLE_RIGHT
        tableBottom = DEFAULT_TABLE_BOTTOM
        ballRadius = DEFAULT_BALL_RADIUS
        save()
    }

    companion object {
        // Bordas das tabelas e raio da bola medidos num print 2408x1080 do 8 Ball Pool. Em outras
        // telas é só um ponto de partida para a calibração.
        const val DEFAULT_TABLE_LEFT = 474.5f / 2408f
        const val DEFAULT_TABLE_TOP = 255.5f / 1080f
        const val DEFAULT_TABLE_RIGHT = 1932f / 2408f
        const val DEFAULT_TABLE_BOTTOM = 984.5f / 1080f
        const val DEFAULT_BALL_RADIUS = 22f / 1080f

        private const val KEY_TABLE_LEFT = "table_left"
        private const val KEY_TABLE_TOP = "table_top"
        private const val KEY_TABLE_RIGHT = "table_right"
        private const val KEY_TABLE_BOTTOM = "table_bottom"
        private const val KEY_BALL_RADIUS = "ball_radius"
        private const val KEY_CUE_X = "cue_x"
        private const val KEY_CUE_Y = "cue_y"
        private const val KEY_TARGET_X = "target_x"
        private const val KEY_TARGET_Y = "target_y"
        private const val KEY_TARGET_ENABLED = "target_enabled"
        private const val KEY_AIM_DEGREES = "aim_degrees"
        private const val KEY_CUSHIONS = "cushions"
        private const val KEY_SPIN_SIDE = "spin_side"
        private const val KEY_SPIN_FOLLOW = "spin_follow"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
    }
}
