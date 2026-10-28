package io.github.lucasbrandaocabral.regua

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var permissionStatus: TextView
    private lateinit var permissionButton: Button
    private lateinit var toggleButton: Button
    private lateinit var autoCheck: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val allowed = Settings.canDrawOverlays(this)
        permissionStatus.text = if (allowed) "Permitido ✓" else "Ainda não permitido."
        permissionButton.visibility = if (allowed) View.GONE else View.VISIBLE
        toggleButton.isEnabled = allowed
        toggleButton.text = if (OverlayService.isRunning) "Parar régua" else "Iniciar régua"
    }

    private fun toggleRuler() {
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
            toggleButton.text = "Iniciar régua"
            return
        }
        if (autoCheck.isChecked) {
            // Pede a gravação de tela antes, para a régua já abrir seguindo a mira do jogo.
            startActivityForResult(ProjectionPermissionActivity.captureIntent(this), REQUEST_CAPTURE)
        } else {
            launch(resultCode = 0, captureData = null)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode == RESULT_OK && data != null) {
            launch(resultCode, data)
        } else {
            Toast.makeText(this, "Sem a gravação de tela a régua abre no modo manual.", Toast.LENGTH_LONG).show()
            launch(resultCode = 0, captureData = null)
        }
    }

    private fun launch(resultCode: Int, captureData: Intent?) {
        if (captureData != null) OverlayService.startTracking(this, resultCode, captureData) else OverlayService.start(this)
        val game = packageManager.getLaunchIntentForPackage(GAME_PACKAGE)
        if (game != null) {
            startActivity(game)
        } else {
            Toast.makeText(this, "Régua ativa. Agora abra o jogo.", Toast.LENGTH_LONG).show()
            toggleButton.text = "Parar régua"
        }
    }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20f)
            setPadding(pad, pad, pad, pad)
        }
        column.addView(text("Régua", 28f, bold = true))
        column.addView(text("Mostra a rota da bola por cima do jogo de sinuca.", 15f, color = MUTED))

        column.addView(section("1. Permissão"))
        column.addView(text("O Android precisa deixar a Régua desenhar por cima de outros apps.", 15f))
        permissionStatus = text("", 15f, bold = true)
        column.addView(permissionStatus)
        permissionButton = button("Permitir sobreposição") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        column.addView(permissionButton)

        column.addView(section("2. Usar"))
        val settings = RulerSettings(this)
        autoCheck = CheckBox(this).apply {
            text = "Seguir a linha do jogo sozinha (o Android vai pedir para gravar a tela)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            isChecked = settings.autoTrack
            setOnCheckedChangeListener { _, checked -> settings.autoTrack = checked }
        }
        column.addView(autoCheck)
        toggleButton = button("Iniciar régua") { toggleRuler() }
        column.addView(toggleButton)

        column.addView(section("Como usar"))
        column.addView(
            text(
                """
                Automático (AUTO verde no painel): é só jogar. A régua acha a bola branca e a linha branca do jogo e continua a linha dele: tabelas da branca em amarelo, a bola alvo em azul (verde quando cai) e a branca depois do choque em lilás (vermelha se ela também cai). A bola que o círculo do jogo toca é sempre a bola alvo, e as linhas param nas outras bolas da mesa (círculo tracejado); se a bola alvo bater em outra, a régua mostra para onde essa outra vai. AUTO amarelo quer dizer que a linha do jogo não está aparecendo (vez do adversário, bolas andando).

                Manual:
                1. Toque em MIRAR. Arraste o círculo branco até a bola branca e o círculo laranja até a bola que você quer encaçapar. Arraste em qualquer outro lugar para girar a linha.
                2. A linha amarela é o caminho da branca. Quando ela encosta no alvo, a linha azul mostra para onde a bola alvo vai e fica verde quando ela cai na caçapa. A linha tracejada é a branca depois do choque (vermelha quer dizer que ela também cai).
                3. Toque em JOGAR: a linha fica na tela e os toques voltam para o jogo. Gire o taco até a linha do jogo ficar em cima da linha da régua e dê a tacada.
                """.trimIndent(),
                15f,
            ),
        )
        column.addView(section("Botões do painel"))
        column.addView(
            text(
                """
                Setas de 4 pontas: arraste para mudar o painel de lugar; toque para recolher.
                AUTO: liga/desliga a mira automática (pede para gravar a tela).
                Setas redondas: giram a linha bem devagar (segure para girar sem parar).
                Número "tab.": quantas tabelas a linha mostra (0 a 5).
                Alvo: liga ou desliga a bola alvo do modo manual.
                Bolinha com ponto vermelho: efeito. Marque o mesmo ponto que você marcou no jogo. Efeito em cima/embaixo muda a branca depois do choque; efeito lateral muda o ângulo nas tabelas. É uma estimativa.
                Mesa: calibração. X: fecha a régua.
                A força da tacada não muda a direção das linhas, só até onde as bolas chegam.
                """.trimIndent(),
                15f,
            ),
        )

        column.addView(section("Calibração"))
        column.addView(
            text(
                "A régua já vem medida para telas 2408×1080. Se as linhas não baterem com as tabelas " +
                    "do jogo, toque no botão da mesa no painel e arraste os pontos até a borda das tabelas. " +
                    "A cor da mesa não importa.",
                15f,
            ),
        )
        column.addView(
            button("Restaurar calibração padrão") {
                // A régua aberta guarda a própria cópia e salvaria por cima ao fechar.
                val message = if (OverlayService.isRunning) {
                    "Pare a régua antes de restaurar a calibração."
                } else {
                    RulerSettings(this).resetCalibration()
                    "Calibração restaurada."
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            },
        )

        return ScrollView(this).apply {
            fitsSystemWindows = true
            addView(column)
        }
    }

    private fun section(title: String) = text(title, 18f, bold = true).apply {
        setPadding(0, dp(24f), 0, dp(6f))
        setTextColor(getColor(R.color.regua_accent))
    }

    private fun text(value: String, sizeSp: Float, bold: Boolean = false, color: Int = Color.WHITE) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val GAME_PACKAGE = "com.miniclip.eightballpool"
        const val REQUEST_CAPTURE = 1
        const val MUTED = 0xFFB0BEC5.toInt()
    }
}
