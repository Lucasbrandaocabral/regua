package io.github.lucasbrandaocabral.regua

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.github.lucasbrandaocabral.regua.geometry.Table
import io.github.lucasbrandaocabral.regua.vision.AimDetector
import io.github.lucasbrandaocabral.regua.vision.Frame
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Grava a tela (MediaProjection), procura a mira do jogo em cada quadro numa thread separada e
 * entrega o resultado, em pixels da tela, na thread principal.
 */
class ScreenTracker(
    private val projection: MediaProjection,
    private val onResult: (AimDetector.Result?) -> Unit,
    private val onStopped: () -> Unit,
) {
    /** Mesa em pixels da tela; null enquanto não dá para procurar (tela em pé). */
    @Volatile
    var table: Table? = null

    private val thread = HandlerThread("regua-tracker").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val detector = AimDetector()
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null

    @Volatile
    private var screenWidth = 0

    @Volatile
    private var screenHeight = 0

    @Volatile
    private var stopped = false

    // Só usados na thread de trabalho.
    private var pixels = IntArray(0)
    private var previous: AimDetector.Result? = null
    private var lastFrameAt = 0L
    private var pending = false

    /** Pedido de cópia do próximo quadro analisado (para o usuário mandar quando a mira falha). */
    @Volatile
    private var snapshot: ((Bitmap, AimDetector.Result?) -> Unit)? = null

    private val callback = object : MediaProjection.Callback() {
        // O usuário parou a gravação pelo sistema (ou outro app pegou a tela).
        override fun onStop() {
            release()
            onStopped()
        }
    }

    fun start(width: Int, height: Int, densityDpi: Int) {
        // No Android 14+ o callback tem que existir antes de criar a tela virtual.
        projection.registerCallback(callback, main)
        screenWidth = width
        screenHeight = height
        val (w, h) = captureSize(width, height)
        val newReader = newReader(w, h)
        reader = newReader
        display = projection.createVirtualDisplay(
            "regua", w, h, densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, worker,
        )
    }

    /** A tela girou: muda o tamanho da captura (no Android 14+ não dá para criar outra tela virtual). */
    fun resize(width: Int, height: Int, densityDpi: Int) {
        if (stopped || (width == screenWidth && height == screenHeight)) return
        screenWidth = width
        screenHeight = height
        val (w, h) = captureSize(width, height)
        val old = reader
        val newReader = newReader(w, h)
        reader = newReader
        display?.resize(w, h, densityDpi)
        display?.surface = newReader.surface
        worker.post { old?.close() }
    }

    fun stop() {
        if (stopped) return
        projection.unregisterCallback(callback)
        release()
        projection.stop()
    }

    private fun release() {
        if (stopped) return
        stopped = true
        display?.release()
        display = null
        val old = reader
        reader = null
        worker.post { old?.close() }
        thread.quitSafely()
    }

    /** Captura com no máximo 1080 px no lado menor: sobra precisão e o processamento fica leve. */
    private fun captureSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(1.0, MAX_SHORT_SIDE / min(width, height))
        return Pair((width * scale).roundToInt(), (height * scale).roundToInt())
    }

    private fun newReader(w: Int, h: Int) = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ process(it) }, worker)
    }

    /**
     * Na thread de trabalho, com o próximo quadro: a imagem inteira da tela e o que a régua achou nela.
     * A imagem é feita só para isso (a análise normal copia só as linhas da mesa).
     */
    fun saveNextFrame(onFrame: (Bitmap, AimDetector.Result?) -> Unit) {
        snapshot = onFrame
    }

    private fun process(source: ImageReader) {
        if (stopped) return
        val wait = lastFrameAt + MIN_FRAME_INTERVAL_MS - SystemClock.uptimeMillis()
        if (wait > 0) {
            // Cedo demais: analisa o quadro mais novo daqui a pouco, em vez de jogar este fora. Se a
            // tela parasse de mudar logo depois, nenhum quadro novo chegaria e a régua ficaria presa no
            // resultado de um quadro do meio do movimento.
            if (!pending) {
                pending = true
                worker.postDelayed({
                    pending = false
                    process(source)
                }, wait)
            }
            return
        }
        val image = try {
            source.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val table = table
        if (table == null) {
            image.close()
            previous = null
            return
        }
        lastFrameAt = SystemClock.uptimeMillis()

        val width = screenWidth
        val height = screenHeight
        // Quadro que ainda veio no tamanho de antes de girar a tela.
        if ((image.width > image.height) != (width > height) || width == 0) {
            image.close()
            return
        }
        val scale = image.width.toDouble() / width
        val scaledTable = Table(table.left * scale, table.top * scale, table.right * scale, table.bottom * scale, table.ballRadius * scale)
        val save = snapshot
        snapshot = null
        val frame = try {
            copyRows(image, if (save != null) null else scaledTable)
        } finally {
            image.close()
        }

        val result = detector.detect(frame, scaledTable, previous = previous)
        previous = result
        save?.invoke(annotated(frame, scaledTable, result), result)
        val onScreen = result?.let { scaled(it, 1 / scale) }
        main.post { if (!stopped) onResult(onScreen) }
    }

    /** Copia só as linhas da [table] (o resto do quadro não é usado), ou o quadro inteiro sem ela. */
    private fun copyRows(image: Image, table: Table?): Frame {
        val w = image.width
        val h = image.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val plane = image.planes[0]
        val rowInts = plane.rowStride / 4
        val ints = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val y0 = if (table == null) 0 else max(0, (table.top - table.ballRadius).toInt())
        val y1 = if (table == null) h - 1 else min(h - 1, (table.bottom + table.ballRadius).toInt())
        for (y in y0..y1) {
            ints.position(y * rowInts)
            ints.get(pixels, y * w, w)
        }
        return Frame(w, h, pixels)
    }

    /**
     * O quadro como Bitmap (os pixels vêm como 0xAABBGGRR; o Bitmap quer 0xAARRGGBB), com a mesa que
     * a régua usou em ciano, o que ela achou em magenta e os números no topo: dá para ver se a mesa
     * está no lugar certo do quadro.
     */
    private fun annotated(frame: Frame, table: Table, result: AimDetector.Result?): Bitmap {
        val argb = IntArray(frame.width * frame.height) { i ->
            val p = frame.pixels[i]
            (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p shr 16) and 0xFF)
        }
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(argb, 0, frame.width, 0, 0, frame.width, frame.height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.CYAN
        }
        canvas.drawRect(table.left.toFloat(), table.top.toFloat(), table.right.toFloat(), table.bottom.toFloat(), paint)
        val r = table.ballRadius.toFloat()
        result?.let {
            paint.color = Color.MAGENTA
            val end = it.cue + it.direction * it.guideLength
            canvas.drawCircle(it.cue.x.toFloat(), it.cue.y.toFloat(), r, paint)
            canvas.drawLine(it.cue.x.toFloat(), it.cue.y.toFloat(), end.x.toFloat(), end.y.toFloat(), paint)
            it.ghost?.let { g -> canvas.drawCircle(g.x.toFloat(), g.y.toFloat(), r, paint) }
        }
        val text = "quadro ${frame.width}x${frame.height}  mesa %.1f %.1f %.1f %.1f r=%.1f  %s".format(
            java.util.Locale.US, table.left, table.top, table.right, table.bottom, table.ballRadius,
            if (result == null) "sem mira" else "branca %.0f,%.0f".format(java.util.Locale.US, result.cue.x, result.cue.y),
        )
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 26f
            color = Color.WHITE
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        }
        canvas.drawText(text, 12f, 32f, textPaint)
        return bitmap
    }

    private fun scaled(r: AimDetector.Result, k: Double) = AimDetector.Result(
        cue = r.cue * k,
        direction = r.direction,
        guideLength = r.guideLength * k,
        ghost = r.ghost?.let { it * k },
        objectBall = r.objectBall?.let { it * k },
        objectGuideLength = r.objectGuideLength * k,
        balls = r.balls.map { it * k },
        cushion = r.cushion?.let { it * k },
    )

    private companion object {
        const val MAX_SHORT_SIDE = 1080.0

        /** No máximo ~30 quadros por segundo. */
        const val MIN_FRAME_INTERVAL_MS = 33L
    }
}
