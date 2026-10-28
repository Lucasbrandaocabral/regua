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
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import io.github.lucasbrandaocabral.regua.geometry.Table
import io.github.lucasbrandaocabral.regua.geometry.Vec2
import io.github.lucasbrandaocabral.regua.vision.AimDetector
import kotlin.math.roundToInt

/**
 * Mantém a régua e o painel por cima dos outros apps. Roda em primeiro plano (com notificação) para
 * o Android não fechar a régua enquanto o jogo está aberto. Com a mira automática ligada, também
 * grava a tela para seguir a linha que o jogo desenha.
 */
class OverlayService : Service(), ControlPanel.Actions {

    private lateinit var windowManager: WindowManager
    private lateinit var settings: RulerSettings
    private lateinit var ruler: RulerView
    private lateinit var panel: ControlPanel
    private lateinit var rulerParams: WindowManager.LayoutParams
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var notification: Notification
    private var modeBeforeSpin = RulerView.Mode.PLAY

    private var tracker: ScreenTracker? = null
    private var autoState = ControlPanel.AutoState.OFF
    private var lastAimAt = 0L

    /** Onde a janela da régua começa na tela (normalmente 0,0). */
    private var overlayOffset = Vec2.ZERO

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notification = buildNotification()
        startForegroundWithTypes(capturing = false)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        settings = RulerSettings(this)

        ruler = RulerView(this, settings).apply {
            onEdited = {
                settings.save()
                updateTrackerTable()
            }
            onSpinPadDismissed = { setMode(modeBeforeSpin) }
            onResized = { updateTrackerTable() }
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
            y = if (settings.panelY >= 0) settings.panelY else (40 * density).roundToInt()
        }

        // A régua entra primeiro para o painel ficar por cima dela.
        windowManager.addView(ruler, rulerParams)
        windowManager.addView(panel, panelParams)
        renderPanel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TRACK -> {
                // Veio por startForegroundService: tem que reafirmar o primeiro plano mesmo se não usar.
                startForegroundWithTypes(capturing = tracker != null)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (data != null && ::ruler.isInitialized) startTracking(intent.getIntExtra(EXTRA_CODE, 0), data)
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::ruler.isInitialized) return
        // Com a tela em pé a régua some; sem isso uma janela invisível seguraria os toques.
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) setMode(RulerView.Mode.PLAY)
        tracker?.let {
            val metrics = realMetrics()
            it.resize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        ruler.post {
            clampPanel()
            updateTrackerTable()
        }
    }

    override fun onDestroy() {
        isRunning = false
        tracker?.stop()
        tracker = null
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

    override fun toggleAuto() {
        if (tracker != null) {
            stopTracking()
            return
        }
        // O pedido de gravação de tela precisa de uma Activity; ela devolve a permissão para cá.
        startActivity(Intent(this, ProjectionPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        tracker?.findObjectBall = settings.targetEnabled
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
        panel.render(ruler.mode, autoState, settings.cushions, settings.targetEnabled, settings.spinSide, settings.spinFollow)
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

    // --- Mira automática ---

    private fun startTracking(resultCode: Int, data: Intent) {
        if (tracker != null) return
        // Android 10+: a gravação só pode começar com o serviço em primeiro plano do tipo "mediaProjection".
        startForegroundWithTypes(capturing = true)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: RuntimeException) {
            null
        }
        if (projection == null) {
            startForegroundWithTypes(capturing = false)
            Toast.makeText(this, "Não deu para gravar a tela; a régua segue no modo manual.", Toast.LENGTH_LONG).show()
            return
        }
        val metrics = realMetrics()
        tracker = ScreenTracker(projection, ::onAim, ::onTrackingStopped).also {
            it.findObjectBall = settings.targetEnabled
            it.start(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        }
        ruler.autoTracking = true
        updateTrackerTable()
        setAutoState(ControlPanel.AutoState.SEARCHING)
    }

    private fun stopTracking() {
        tracker?.stop()
        onTrackingStopped()
    }

    private fun onTrackingStopped() {
        tracker = null
        ruler.autoTracking = false
        ruler.autoAim = null
        startForegroundWithTypes(capturing = false)
        setAutoState(ControlPanel.AutoState.OFF)
    }

    /** Resultado de um quadro (em pixels da tela) ou null se a mira do jogo não apareceu nele. */
    private fun onAim(result: AimDetector.Result?) {
        if (tracker == null) return
        val now = SystemClock.uptimeMillis()
        if (result != null) {
            lastAimAt = now
            ruler.autoAim = toOverlay(result)
            setAutoState(ControlPanel.AutoState.TRACKING)
        } else if (now - lastAimAt > LOST_AFTER_MS) {
            // Um quadro perdido não apaga a linha (evita piscar); só some se a mira sumir de verdade.
            ruler.autoAim = null
            setAutoState(ControlPanel.AutoState.SEARCHING)
        }
    }

    private fun setAutoState(state: ControlPanel.AutoState) {
        if (state == autoState) return
        autoState = state
        renderPanel()
    }

    /** Passa a mesa calibrada (em pixels da tela) para quem procura a mira. */
    private fun updateTrackerTable() {
        val t = tracker ?: return
        if (!ruler.isLandscape) {
            t.table = null
            return
        }
        val location = IntArray(2)
        ruler.getLocationOnScreen(location)
        overlayOffset = Vec2(location[0].toDouble(), location[1].toDouble())
        val table = ruler.currentTable()
        t.table = Table(
            table.left + overlayOffset.x, table.top + overlayOffset.y,
            table.right + overlayOffset.x, table.bottom + overlayOffset.y, table.ballRadius,
        )
    }

    private fun toOverlay(r: AimDetector.Result) = AimDetector.Result(
        cue = r.cue - overlayOffset,
        direction = r.direction,
        guideLength = r.guideLength,
        ghost = r.ghost?.let { it - overlayOffset },
        objectBall = r.objectBall?.let { it - overlayOffset },
        objectGuideLength = r.objectGuideLength,
    )

    @Suppress("DEPRECATION")
    private fun realMetrics() = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }

    // --- Janelas e notificação ---

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

    private fun buildNotification(): Notification {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_regua)
            .setContentTitle("Régua ativa")
            .setContentText("Use o painel por cima do jogo. Toque em Parar para fechar.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_close), "Parar", stop).build(),
            )
            .build()
    }

    private fun startForegroundWithTypes(capturing: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                (if (capturing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_STOP = "io.github.lucasbrandaocabral.regua.STOP"
        private const val ACTION_TRACK = "io.github.lucasbrandaocabral.regua.TRACK"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "regua"
        private const val NOTIFICATION_ID = 1
        private const val MAX_CUSHIONS = 5
        private const val PLAY_ALPHA = 0.8f
        private const val LOST_AFTER_MS = 300L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }

        /** Entrega a permissão de gravar a tela (e liga a mira automática), iniciando a régua se preciso. */
        fun startTracking(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java)
                    .setAction(ACTION_TRACK)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
