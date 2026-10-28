package io.github.lucasbrandaocabral.regua

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.input.InputManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.roundToInt

/**
 * Mantém a régua e o painel por cima dos outros apps. Roda em primeiro plano (com notificação) para
 * o Android não fechar a régua enquanto o jogo está aberto.
 */
class OverlayService : Service(), ControlPanel.Actions {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: RulerSettings
    private lateinit var ruler: RulerView
    private lateinit var panel: ControlPanel
    private lateinit var rulerParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private var modeBeforeSpin = RulerView.Mode.PLAY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = RulerSettings(this)

        ruler = RulerView(this, settings).apply {
            onEdited = { settings.save() }
            onSpinPadDismissed = { setMode(modeBeforeSpin) }
        }
        rulerParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            alpha = passThroughAlpha()
        }

        panel = ControlPanel(this, this)
        val density = resources.displayMetrics.density
        panelParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            // Por padrão fica colado na borda esquerda, onde o 8 Ball Pool não tem botões.
            x = if (settings.panelX >= 0) settings.panelX else 0
            y = if (settings.panelY >= 0) settings.panelY else (44 * density).roundToInt()
        }

        // A régua entra primeiro para o painel ficar por cima dela.
        windowManager.addView(ruler, rulerParams)
        windowManager.addView(panel, panelParams)
        renderPanel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::ruler.isInitialized) return
        // Com a tela em pé a régua some; sem isso uma janela invisível seguraria os toques.
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) setMode(RulerView.Mode.PLAY)
        ruler.post { clampPanel() }
    }

    override fun onDestroy() {
        isRunning = false
        if (::ruler.isInitialized) {
            settings.save()
            windowManager.removeView(panel)
            windowManager.removeView(ruler)
        }
        super.onDestroy()
    }

    // --- Painel ---

    override fun toggleAim() {
        setMode(if (ruler.mode == RulerView.Mode.PLAY) RulerView.Mode.AIM else RulerView.Mode.PLAY)
    }

    override fun rotate(degrees: Float) {
        ruler.rotateAim(degrees)
        settings.save()
    }

    override fun cycleCushions() {
        settings.cushions = (settings.cushions + 1) % (MAX_CUSHIONS + 1)
        changed()
    }

    override fun toggleTarget() {
        settings.targetEnabled = !settings.targetEnabled
        changed()
    }

    override fun toggleSpin() {
        setMode(if (ruler.mode == RulerView.Mode.SPIN) modeBeforeSpin else RulerView.Mode.SPIN)
    }

    override fun toggleCalibration() {
        setMode(if (ruler.mode == RulerView.Mode.CALIBRATE) RulerView.Mode.AIM else RulerView.Mode.CALIBRATE)
    }

    override fun close() {
        stopSelf()
    }

    override fun movePanel(dx: Int, dy: Int) {
        panelParams.x += dx
        panelParams.y += dy
        clampPanel()
    }

    override fun panelMoved() {
        settings.panelX = panelParams.x
        settings.panelY = panelParams.y
        settings.save()
    }

    private fun changed() {
        settings.save()
        ruler.invalidate()
        renderPanel()
    }

    private fun renderPanel() {
        panel.render(ruler.mode, settings.cushions, settings.targetEnabled, settings.spinSide, settings.spinFollow)
    }

    private fun setMode(mode: RulerView.Mode) {
        if (mode != RulerView.Mode.PLAY && !ruler.isLandscape) {
            Toast.makeText(this, "Abra o jogo (tela deitada) para ajustar a régua.", Toast.LENGTH_SHORT).show()
            return
        }
        if (mode == RulerView.Mode.SPIN && ruler.mode != RulerView.Mode.SPIN) modeBeforeSpin = ruler.mode
        ruler.mode = mode

        // Em PLAY os toques atravessam a régua até o jogo. O Android 12+ só deixa um toque atravessar
        // uma janela de outro app se ela for translúcida o bastante, daí o alpha reduzido.
        val touchable = mode != RulerView.Mode.PLAY
        rulerParams.flags = if (touchable) {
            rulerParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            rulerParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        rulerParams.alpha = if (touchable) 1f else passThroughAlpha()
        windowManager.updateViewLayout(ruler, rulerParams)
        settings.save()
        renderPanel()
    }

    private fun clampPanel() {
        if (!panel.isAttachedToWindow) return
        val maxX = (ruler.width - panel.width).coerceAtLeast(0)
        val maxY = (ruler.height - panel.height).coerceAtLeast(0)
        panelParams.x = panelParams.x.coerceIn(0, maxX)
        panelParams.y = panelParams.y.coerceIn(0, maxY)
        windowManager.updateViewLayout(panel, panelParams)
    }

    private fun passThroughAlpha(): Float {
        val max = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(INPUT_SERVICE) as InputManager).maximumObscuringOpacityForTouch
        } else {
            1f
        }
        return minOf(PLAY_ALPHA, max)
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(
        width,
        height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Sem foco: o jogo continua recebendo teclado/voltar e segue em tela cheia.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Cobre a tela inteira (inclusive a área da câmera), igual ao jogo, para a calibração bater
        // com as coordenadas de um print.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Régua ativa", NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_regua)
            .setContentTitle("Régua ativa")
            .setContentText("Use o painel por cima do jogo. Toque em Parar para fechar.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "Parar", stop).build(),
            )
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_STOP = "io.github.lucasbrandaocabral.regua.STOP"
        private const val CHANNEL_ID = "regua"
        private const val NOTIFICATION_ID = 1
        private const val MAX_CUSHIONS = 5
        private const val PLAY_ALPHA = 0.8f

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
