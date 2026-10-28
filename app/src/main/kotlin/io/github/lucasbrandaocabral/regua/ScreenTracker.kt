package io.github.lucasbrandaocabral.regua

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

    private fun process(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val now = SystemClock.uptimeMillis()
        val table = table
        if (stopped || now - lastFrameAt < MIN_FRAME_INTERVAL_MS || table == null) {
            image.close()
            if (table == null) previous = null
            return
        }
        lastFrameAt = now

        val width = screenWidth
        val height = screenHeight
        // Quadro que ainda veio no tamanho de antes de girar a tela.
        if ((image.width > image.height) != (width > height) || width == 0) {
            image.close()
            return
        }
        val scale = image.width.toDouble() / width
        val scaledTable = Table(table.left * scale, table.top * scale, table.right * scale, table.bottom * scale, table.ballRadius * scale)
        val frame = try {
            copyTableRows(image, scaledTable)
        } finally {
            image.close()
        }

        val result = detector.detect(frame, scaledTable, previous = previous)
        previous = result
        val onScreen = result?.let { scaled(it, 1 / scale) }
        main.post { if (!stopped) onResult(onScreen) }
    }

    /** Copia só as linhas da mesa (o resto do quadro não é usado). */
    private fun copyTableRows(image: Image, table: Table): Frame {
        val w = image.width
        val h = image.height
        if (pixels.size != w * h) pixels = IntArray(w * h)
        val plane = image.planes[0]
        val rowInts = plane.rowStride / 4
        val ints = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
        val y0 = max(0, (table.top - table.ballRadius).toInt())
        val y1 = min(h - 1, (table.bottom + table.ballRadius).toInt())
        for (y in y0..y1) {
            ints.position(y * rowInts)
            ints.get(pixels, y * w, w)
        }
        return Frame(w, h, pixels)
    }

    private fun scaled(r: AimDetector.Result, k: Double) = AimDetector.Result(
        cue = r.cue * k,
        direction = r.direction,
        guideLength = r.guideLength * k,
        ghost = r.ghost?.let { it * k },
        objectBall = r.objectBall?.let { it * k },
        objectGuideLength = r.objectGuideLength * k,
        balls = r.balls.map { it * k },
    )

    private companion object {
        const val MAX_SHORT_SIDE = 1080.0

        /** No máximo ~30 quadros por segundo. */
        const val MIN_FRAME_INTERVAL_MS = 33L
    }
}
