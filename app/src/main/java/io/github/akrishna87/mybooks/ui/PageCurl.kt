package io.github.akrishna87.mybooks.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A page turning like paper: the page bends round a cylinder whose edge follows your finger,
 * its back showing faintly through, with shadows under the curl and along the fold.
 *
 * [front] is the page that curls; [under] (if set) is drawn beneath it, otherwise whatever is
 * behind this view (the reader, already showing the next page) shows through. [edge] is where
 * the leading edge of the curl is, in pixels from the left: at [width] + radius the page lies
 * flat, below -width it has gone.
 */
class PageCurlView(context: Context) : View(context) {
    var front: Bitmap? = null
        set(value) {
            field = value
            frontShader = value?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            backShader = value?.let { BitmapShader(washed(it), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidate()
        }
    var under: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }
    var edge = 0f
        set(value) {
            field = value
            invalidate()
        }
    /** The page colour, for the back of the page (it shows the text faintly, mirrored). */
    var paper = 0xFFFBFAF7.toInt()

    val radius get() = width / 9f
    val flat get() = width + radius
    val gone get() = -width * 1.15f

    private var frontShader: BitmapShader? = null
    private var backShader: BitmapShader? = null
    private val meshPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    private val dp = context.resources.displayMetrics.density

    init {
        // Before Android 10 the GPU renderer can't draw meshes (drawVertices).
        if (android.os.Build.VERSION.SDK_INT < 29) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun washed(src: Bitmap): Bitmap {
        val copy = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(copy).drawColor((paper and 0x00FFFFFF) or (0xD0 shl 24))
        return copy
    }

    /** Where page column [x] lands once curled round the cylinder whose axis is at [a]. */
    private fun project(x: Float, a: Float, r: Float): Float {
        val d = x - a
        val half = (PI * r).toFloat() // half way round: the page lies flat again, upside down
        return when {
            d <= 0f -> x
            d < half -> a + r * sin(d / r)
            else -> a - (d - half)
        }
    }

    private fun strip(c: Canvas, shader: BitmapShader, from: Float, to: Float, a: Float, r: Float) {
        if (to - from < 0.5f) return
        val n = 24
        val h = height.toFloat()
        val verts = FloatArray((n + 1) * 4)
        val texs = FloatArray((n + 1) * 4)
        for (i in 0..n) {
            val x = from + (to - from) * i / n
            val px = project(x, a, r)
            verts[i * 4] = px; verts[i * 4 + 1] = 0f; verts[i * 4 + 2] = px; verts[i * 4 + 3] = h
            texs[i * 4] = x; texs[i * 4 + 1] = 0f; texs[i * 4 + 2] = x; texs[i * 4 + 3] = h
        }
        meshPaint.shader = shader
        c.drawVertices(Canvas.VertexMode.TRIANGLE_STRIP, verts.size, verts, 0, texs, 0, null, 0, null, 0, 0, meshPaint)
    }

    private fun band(c: Canvas, from: Float, to: Float, darkAtFrom: Int, darkAtTo: Int) {
        if (abs(to - from) < 0.5f) return
        shade.shader = LinearGradient(from, 0f, to, 0f, darkAtFrom shl 24, darkAtTo shl 24, Shader.TileMode.CLAMP)
        c.drawRect(minOf(from, to), 0f, maxOf(from, to), height.toFloat(), shade)
    }

    override fun onDraw(c: Canvas) {
        under?.let { c.drawBitmap(it, 0f, 0f, null) }
        val page = front ?: return
        val fs = frontShader ?: return
        val bs = backShader ?: return
        val w = width.toFloat()
        val r = radius
        val a = edge - r // the cylinder's axis
        val quarter = (PI * r / 2).toFloat()

        // The part of the page still lying flat.
        if (a > 0f) {
            c.save()
            c.clipRect(0f, 0f, minOf(a, w), height.toFloat())
            c.drawBitmap(page, 0f, 0f, null)
            c.restore()
        }
        if (a >= w) return

        // Shadow the curl casts on the page underneath.
        band(c, a + r, a + r + 28 * dp, 0x55, 0x00)

        // The front of the page as it bends up and over.
        strip(c, fs, maxOf(a, 0f), minOf(w, a + quarter), a, r)
        band(c, a, a + r, 0x00, 0x30)

        // The back of the page, folded over on top.
        val backFrom = maxOf(a + quarter, 0f)
        if (backFrom < w) {
            val farEdge = project(w, a, r)
            band(c, farEdge, farEdge - 14 * dp, 0x40, 0x00) // its shadow on the page below
            strip(c, bs, backFrom, w, a, r)
            band(c, a + r, a, 0x28, 0x00) // darker where it turns over
        }
    }
}

/**
 * Holds the reader and, over it, the curling page. In the curl style a sideways drag is taken
 * from the reader and turns the page instead; anything else (taps, links, selecting text) goes to
 * the reader as usual.
 */
internal class CurlFrame(context: Context, private val controller: ReaderController) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var dragging = false
    private var velocity: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (controller.turnStyle != "curl") return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downTime = ev.eventTime; dragging = false
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(ev)
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Not after a long press (that's selecting text), and not while text is selected.
                if (!dragging && abs(dx) > slop && abs(dx) > abs(dy) * 1.3f && ev.eventTime - downTime < 400 && !controller.hasSelection) {
                    val dir = if (dx < 0) 1 else -1
                    if (controller.startCurl(dir, ev.x)) {
                        dragging = true
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(ev)
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> controller.dragCurl(ev.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                controller.endCurl(velocity?.xVelocity ?: 0f, cancelled = ev.actionMasked == MotionEvent.ACTION_CANCEL)
                velocity?.recycle()
                velocity = null
                dragging = false
            }
        }
        return true
    }
}
