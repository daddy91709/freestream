package com.freestream.tv

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

class VirtualCursorController(
    private val virtualCursor: ImageView,
    private val osdHudContainer: FrameLayout,
    private val ivOsdIcon: ImageView,
    private val webViewProvider: () -> WebView,
    private val evalJs: (String) -> Unit
) {
    var isMouseMode: Boolean = false
        private set

    private var cursorX = 0f
    private var cursorY = 0f
    private var dirLeft = false
    private var dirRight = false
    private var dirUp = false
    private var dirDown = false
    private var moveStartNanos = 0L
    private var lastFrameNanos = 0L
    private var frameLoopRunning = false
    private var pendingScrollX = 0f
    private var pendingScrollY = 0f

    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        osdHudContainer.animate()
            .alpha(0f)
            .scaleX(0.82f)
            .scaleY(0.82f)
            .setDuration(300)
            .withEndAction { osdHudContainer.visibility = View.GONE }
            .start()
    }

    private val cursorFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            onCursorFrame(frameTimeNanos)
        }
    }

    fun toggleMouseMode() {
        isMouseMode = !isMouseMode
        releaseCursorKeys()
        val webView = webViewProvider()

        if (isMouseMode) {
            val w = if (webView.width > 0) webView.width.toFloat() else 1920f
            val h = if (webView.height > 0) webView.height.toFloat() else 1080f
            cursorX = w / 2f
            cursorY = h / 2f
            updateCursorPosition()
            virtualCursor.visibility = View.VISIBLE
            evalJs("window.tvClearFocus && window.tvClearFocus()")
            dispatchHover()
        } else {
            virtualCursor.visibility = View.GONE
            evalJs("window.tvFocusNear && window.tvFocusNear($cursorX, $cursorY, ${webView.width})")
        }

        showModeOsd(isMouseMode)
    }

    fun handleDpadKey(keyCode: Int, action: Int, repeatCount: Int): Boolean {
        val pressed = action == KeyEvent.ACTION_DOWN
        if (action == KeyEvent.ACTION_DOWN && repeatCount > 0) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> dirUp = pressed
            KeyEvent.KEYCODE_DPAD_DOWN -> dirDown = pressed
            KeyEvent.KEYCODE_DPAD_LEFT -> dirLeft = pressed
            KeyEvent.KEYCODE_DPAD_RIGHT -> dirRight = pressed
        }
        if (pressed) {
            if (!frameLoopRunning) moveStartNanos = System.nanoTime()
            // Spostamento immediato minimo: un tocco singolo sposta sempre di qualche pixel
            nudgeCursor(keyCode)
            startCursorLoop()
        }
        return true
    }

    fun clickVirtualCursor() {
        virtualCursor.animate().cancel()
        virtualCursor.animate()
            .scaleX(0.72f)
            .scaleY(0.72f)
            .setDuration(60)
            .withEndAction {
                virtualCursor.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
            }
            .start()

        dispatchTap(cursorX, cursorY)
    }

    fun dispatchTap(x: Float, y: Float) {
        val webView = webViewProvider()
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        webView.dispatchTouchEvent(down)
        down.recycle()
        webView.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            webView.dispatchTouchEvent(up)
            up.recycle()
        }, 40)
    }

    fun releaseCursorKeys() {
        dirLeft = false
        dirRight = false
        dirUp = false
        dirDown = false
    }

    fun destroy() {
        Choreographer.getInstance().removeFrameCallback(cursorFrameCallback)
        osdHandler.removeCallbacksAndMessages(null)
    }

    private fun showModeOsd(isMouse: Boolean) {
        osdHandler.removeCallbacks(hideOsdRunnable)
        ivOsdIcon.setImageResource(if (isMouse) R.drawable.ic_osd_mouse else R.drawable.ic_osd_controller)

        osdHudContainer.animate().cancel()
        osdHudContainer.visibility = View.VISIBLE
        osdHudContainer.alpha = 0f
        osdHudContainer.scaleX = 0.8f
        osdHudContainer.scaleY = 0.8f

        osdHudContainer.animate()
            .alpha(1f)
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(160)
            .start()

        osdHandler.postDelayed(hideOsdRunnable, 1100)
    }

    private fun startCursorLoop() {
        if (frameLoopRunning) return
        frameLoopRunning = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
    }

    private fun nudgeCursor(keyCode: Int) {
        val step = max(webViewProvider().width, 1280) * 0.006f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> moveCursorBy(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveCursorBy(0f, step)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveCursorBy(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveCursorBy(step, 0f)
        }
    }

    // Movimento fluido a 60fps: velocità iniziale bassa per la precisione, poi accelerazione progressiva
    private fun onCursorFrame(frameTimeNanos: Long) {
        val anyHeld = dirLeft || dirRight || dirUp || dirDown
        if (!isMouseMode || !anyHeld) {
            frameLoopRunning = false
            flushEdgeScroll()
            return
        }

        val dt = if (lastFrameNanos == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos

        val ref = max(webViewProvider().width, 1280).toFloat()
        val heldSeconds = ((System.nanoTime() - moveStartNanos) / 1_000_000_000f).coerceAtLeast(0f)
        val t = min(1f, heldSeconds / CURSOR_ACCEL_SECONDS)
        val speed = ref * (CURSOR_MIN_SPEED + (CURSOR_MAX_SPEED - CURSOR_MIN_SPEED) * t * t)

        var vx = 0f
        var vy = 0f
        if (dirLeft) vx -= 1f
        if (dirRight) vx += 1f
        if (dirUp) vy -= 1f
        if (dirDown) vy += 1f
        if (vx != 0f && vy != 0f) {
            vx *= 0.7071f
            vy *= 0.7071f
        }

        moveCursorBy(vx * speed * dt, vy * speed * dt)
        Choreographer.getInstance().postFrameCallback(cursorFrameCallback)
    }

    private fun moveCursorBy(dx: Float, dy: Float) {
        val webView = webViewProvider()
        val w = if (webView.width > 0) webView.width.toFloat() else 1920f
        val h = if (webView.height > 0) webView.height.toFloat() else 1080f

        cursorX = (cursorX + dx).coerceIn(0f, w - 1f)
        cursorY = (cursorY + dy).coerceIn(0f, h - 1f)

        // Scorrimento della pagina quando il cursore spinge contro il bordo (stile Silk)
        val edge = h * 0.06f
        if (dy > 0 && cursorY >= h - edge) pendingScrollY += dy
        if (dy < 0 && cursorY <= edge) pendingScrollY += dy
        if (dx > 0 && cursorX >= w - edge) pendingScrollX += dx
        if (dx < 0 && cursorX <= edge) pendingScrollX += dx

        updateCursorPosition()
        dispatchHover()
        flushEdgeScroll()
    }

    private fun flushEdgeScroll() {
        val sx = pendingScrollX.toInt()
        val sy = pendingScrollY.toInt()
        if (sx == 0 && sy == 0) return
        pendingScrollX -= sx
        pendingScrollY -= sy
        val width = webViewProvider().width
        evalJs("window.fsScrollAt ? window.fsScrollAt($cursorX, $cursorY, $sx, $sy, $width) : window.scrollBy($sx / (window.devicePixelRatio||1), $sy / (window.devicePixelRatio||1))")
    }

    private fun updateCursorPosition() {
        // La punta del cursore coincide con il centro dell'icona circolare
        val halfW = (virtualCursor.width / 2f).takeIf { it > 0 } ?: 16f
        val halfH = (virtualCursor.height / 2f).takeIf { it > 0 } ?: 16f
        virtualCursor.translationX = cursorX - halfW
        virtualCursor.translationY = cursorY - halfH
    }

    // Eventi hover del mouse: il sito mostra anteprime/effetti come su PC
    private fun dispatchHover() {
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, cursorX, cursorY, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        webViewProvider().dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    companion object {
        // Velocità del cursore in frazioni della larghezza schermo al secondo
        private const val CURSOR_MIN_SPEED = 0.22f
        private const val CURSOR_MAX_SPEED = 1.05f
        private const val CURSOR_ACCEL_SECONDS = 1.1f
    }
}
