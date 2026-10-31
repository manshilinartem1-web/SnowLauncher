package com.example.snowlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.OverScroller
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val PILE_COLS = 9
private const val PI_F = 3.1415927f

private class AppItem(
    val key: String,
    val pkg: String,
    val cls: String,
    val label: String,
    val icon: Bitmap
) {
    val pile = FloatArray(PILE_COLS)
    var shortLabel: String? = null

    fun snow(): Float {
        var m = 0f
        for (v in pile) if (v > m) m = v
        return m
    }
}

private class Flake(
    var x: Float,
    var y: Float,
    var vy: Float,
    val r: Float,
    val phase: Float,
    val gift: Boolean
) {
    var passed = -1
}

private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var life: Float,
    val maxLife: Float,
    val r: Float,
    val color: Int
)

class SnowLauncherView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val rnd = Random(System.nanoTime())
    private val ui = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("snow_launcher", Context.MODE_PRIVATE)
    private var quickMode = prefs.getBoolean("quick", false)

    private var apps: List<AppItem> = emptyList()
    private val cache = HashMap<String, AppItem>()
    @Volatile
    private var loading = false

    private val flakes = ArrayList<Flake>()
    private val particles = ArrayList<Particle>()
    private val hit = IntArray(2)

    private var topInset = dp(24f)
    private var bottomInset = 0f
    private var cols = 4
    private var cellW = 0f
    private var cellH = 0f
    private var iconSize = 0f
    private var iconPadTop = 0f
    private var gridTop = 0f
    private var maxPile = 0f
    private var scrollYf = 0f
    private var maxScroll = 0f

    private var time = 0f
    private var lastNanos = System.nanoTime()
    private var spawnAcc = 0f
    private var gust = 0f
    private var tiltWind = 0f
    private var blizzardUntil = 0f
    private var lastShake = 0L

    private var santaOn = false
    private var santaX = 0f
    private var nextSanta = 6f
    private var lastGiftCol = -1

    private var message = "Тап по иконке = смахнуть снег ❄"
    private var messageUntil = System.nanoTime() + 6_000_000_000L

    private var newYear = 0L
    private var cdText = ""
    private var cdSec = -1L

    private val greetings = arrayOf(
        "С Новым годом! 🎉",
        "Загадывай желание! 🌟",
        "Мандарины уже купил? 🍊",
        "Оливье не забудь 🥗",
        "Дед Мороз всё видит 🎅",
        "Хо-хо-хо! 🎄",
        "Пусть всё сбудется ✨"
    )

    private val lights = intArrayOf(
        Color.rgb(255, 77, 77),
        Color.rgb(77, 255, 136),
        Color.rgb(255, 213, 77),
        Color.rgb(77, 195, 255),
        Color.rgb(255, 107, 230)
    )

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(12f)
        setShadowLayer(dp(2f), 0f, dp(1f), Color.argb(200, 0, 0, 0))
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(26f)
        isFakeBoldText = true
        setShadowLayer(dp(4f), 0f, dp(2f), Color.argb(160, 0, 0, 40))
    }

    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(191, 216, 255)
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
    }

    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(247, 251, 255)
        style = Paint.Style.FILL
    }

    private val snowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 150, 175, 215)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.9f)
    }

    private val flakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255)
    }

    private val partPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bulbPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val wirePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 20, 60, 40)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
    }

    private val emojiCenter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(24f)
    }

    private val emojiLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(30f)
    }

    private val msgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = dp(15f)
    }

    private val msgBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(170, 8, 16, 48)
    }

    private val pilePath = Path()
    private val wirePath = Path()
    private val pxs = FloatArray(PILE_COLS + 2)
    private val pys = FloatArray(PILE_COLS + 2)
    private val dst = RectF()
    private val msgRect = RectF()

    private val scroller = OverScroller(context)

    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollYf = (scrollYf + distanceY).coerceIn(0f, maxScroll)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(0, scrollYf.toInt(), 0, (-velocityY).toInt(), 0, 0, 0, maxScroll.toInt())
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            handleLongPress(e.x, e.y)
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        return true
    }

    fun onTilt(ax: Float, ay: Float, az: Float) {
        val target = (-ax * dp(9f)).coerceIn(-dp(110f), dp(110f))
        tiltWind += (target - tiltWind) * 0.08f
        val mag = sqrt(ax * ax + ay * ay + az * az)
        val now = System.currentTimeMillis()
        if (mag > 24f && now - lastShake > 1500L) {
            lastShake = now
            shakeOff()
        }
    }

    fun scrollToTop() {
        scroller.forceFinished(true)
        val y = scrollYf.toInt()
        if (y > 0) scroller.startScroll(0, y, 0, -y, 400)
        invalidate()
    }

    fun reloadApps() {
        if (loading) return
        loading = true
        Thread {
            try {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val list = pm.queryIntentActivities(intent, 0)
                val bmpSize = dp(56f).toInt()
                val result = ArrayList<AppItem>()
                for (ri in list) {
                    val ai = ri.activityInfo ?: continue
                    if (ai.packageName == context.packageName) continue
                    val key = ai.packageName + "/" + ai.name
                    var item = cache[key]
                    if (item == null) {
                        val label = ri.loadLabel(pm).toString()
                        val icon: Bitmap? = try {
                            toBitmap(ri.loadIcon(pm), bmpSize)
                        } catch (e: Throwable) {
                            null
                        }
                        if (icon == null) continue
                        item = AppItem(key, ai.packageName, ai.name, label, icon)
                        cache[key] = item
                    }
                    result.add(item)
                }
                result.sortWith(compareBy<AppItem> { it.label.lowercase(Locale.getDefault()) })
                val keys = result.map { it.key }.toSet()
                cache.keys.retainAll(keys)
                ui.post {
                    apps = result
                    computeLayout()
                    invalidate()
                }
            } finally {
                loading = false
            }
        }.start()
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeLayout()
    }

    @Suppress("DEPRECATION")
    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        topInset = insets.systemWindowInsetTop.toFloat()
        bottomInset = insets.systemWindowInsetBottom.toFloat()
        computeLayout()
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    private fun computeLayout() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return
        cols = max(3, (w / dp(82f)).toInt())
        cellW = w / cols
        iconSize = min(dp(54f), cellW * 0.66f)
        maxPile = iconSize * 0.30f
        iconPadTop = maxPile + dp(6f)
        cellH = iconPadTop + iconSize + dp(32f)
        gridTop = topInset + dp(150f)
        val rows = (apps.size + cols - 1) / cols
        val content = gridTop + rows * cellH + bottomInset + dp(16f)
        maxScroll = max(0f, content - h)
        scrollYf = scrollYf.coerceIn(0f, maxScroll)
    }

    private fun iconLeft(idx: Int): Float = (idx % cols) * cellW + (cellW - iconSize) / 2f

    private fun iconTopY(idx: Int): Float = gridTop + (idx / cols) * cellH - scrollYf + iconPadTop

    private fun indexAt(x: Float, y: Float): Int {
        val cy = y + scrollYf - gridTop
        if (cy < 0f || x < 0f || x >= width || cellW <= 0f) return -1
        val col = min(cols - 1, (x / cellW).toInt())
        val row = (cy / cellH).toInt()
        val idx = row * cols + col
        return if (idx in apps.indices) idx else -1
    }

    private fun handleTap(x: Float, y: Float) {
        if (y + scrollYf < gridTop) {
            toggleBlizzard()
            return
        }
        val idx = indexAt(x, y)
        if (idx < 0) {
            gust = (if (x < width / 2f) 1f else -1f) * dp(170f)
            return
        }
        val item = apps[idx]
        if (item.snow() > 1f) {
            sweep(idx)
            if (quickMode) launch(item)
        } else {
            launch(item)
        }
    }

    private fun handleLongPress(x: Float, y: Float) {
        if (y + scrollYf < gridTop) {
            quickMode = !quickMode
            prefs.edit().putBoolean("quick", quickMode).apply()
            showMessage(
                if (quickMode) "Тап = смахнуть и открыть" else "Тап = смахнуть, потом открыть",
                4f
            )
            return
        }
        val idx = indexAt(x, y)
        if (idx >= 0) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            launch(apps[idx])
        }
    }

    private fun launch(item: AppItem) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(item.pkg, item.cls))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            context.startActivity(i)
        } catch (e: Exception) {
            showMessage("Не получилось открыть 😕")
        }
    }

    private fun toggleBlizzard() {
        if (time < blizzardUntil) {
            blizzardUntil = 0f
            showMessage("Метель утихла 🌤")
        } else {
            blizzardUntil = time + 20f
            showMessage("МЕТЕЛЬ! 🌨 (20 сек)")
        }
    }

    private fun showMessage(t: String, sec: Float = 3f) {
        message = t
        messageUntil = System.nanoTime() + (sec * 1e9f).toLong()
    }

    private fun addParticle(x: Float, y: Float, vx: Float, vy: Float, life: Float, r: Float, color: Int) {
        if (particles.size < 700) particles.add(Particle(x, y, vx, vy, life, life, r, color))
    }

    private fun sweep(idx: Int) {
        val item = apps[idx]
        val left = iconLeft(idx)
        val top = iconTopY(idx)
        val step = iconSize / PILE_COLS
        if (top > -iconSize && top < height) {
            for (i in 0 until PILE_COLS) {
                val h = item.pile[i]
                if (h < 0.3f) continue
                val n = 1 + (h / dp(5f)).toInt()
                val bx = left + (i + 0.5f) * step
                val dir = if (bx < left + iconSize / 2f) -1f else 1f
                for (k in 0 until n) {
                    addParticle(
                        bx,
                        top - h * 0.5f,
                        dir * rnd.nextFloat() * dp(150f) + (rnd.nextFloat() - 0.5f) * dp(60f),
                        -rnd.nextFloat() * dp(170f),
                        0.6f + rnd.nextFloat() * 0.6f,
                        dp(1.3f) + rnd.nextFloat() * dp(2.2f),
                        Color.WHITE
                    )
                }
            }
        }
        item.pile.fill(0f)
    }

    private fun shakeOff() {
        var any = false
        for (i in apps.indices) {
            if (apps[i].snow() > 0.3f) {
                any = true
                sweep(i)
            }
        }
        gust = (if (rnd.nextBoolean()) 1f else -1f) * dp(200f)
        showMessage(if (any) "Стряхнул всё! ❄" else "Уже чисто 😄")
    }

    private fun giftLanded(idx: Int) {
        val x = iconLeft(idx) + iconSize / 2f
        val y = iconTopY(idx)
        apps[idx].pile.fill(0f)
        for (i in 0 until 26) {
            val a = rnd.nextFloat() * 2f * PI_F
            val sp = dp(60f) + rnd.nextFloat() * dp(200f)
            addParticle(
                x,
                y,
                cos(a) * sp,
                sin(a) * sp - dp(120f),
                0.9f + rnd.nextFloat() * 0.7f,
                dp(1.5f) + rnd.nextFloat() * dp(2f),
                lights[rnd.nextInt(lights.size)]
            )
        }
        if (rnd.nextFloat() < 0.5f) showMessage(greetings[rnd.nextInt(greetings.size)])
    }

    private fun spawnFlake(blizzard: Boolean) {
        val r = dp(1.1f) + rnd.nextFloat() * dp(2.0f)
        var vy = dp(26f) + (r / dp(3f)) * dp(48f) + rnd.nextFloat() * dp(18f)
        if (blizzard) vy *= 1.7f
        flakes.add(
            Flake(
                -dp(120f) + rnd.nextFloat() * (width + dp(240f)),
                -dp(8f),
                vy,
                r,
                rnd.nextFloat() * 2f * PI_F,
                false
            )
        )
    }

    private fun hitIconTop(x: Float, y: Float, out: IntArray): Boolean {
        val cy = y + scrollYf - gridTop
        if (cy < 0f || x < 0f || cellW <= 0f) return false
        val col = (x / cellW).toInt()
        if (col >= cols) return false
        val row = (cy / cellH).toInt()
        val idx = row * cols + col
        if (idx >= apps.size) return false
        val lx = x - (col * cellW + (cellW - iconSize) / 2f)
        if (lx < 0f || lx >= iconSize) return false
        val bucket = min(PILE_COLS - 1, (lx / iconSize * PILE_COLS).toInt())
        val iconTop = row * cellH + iconPadTop
        val pileTop = iconTop - apps[idx].pile[bucket]
        if (cy < pileTop || cy > iconTop + dp(14f)) return false
        out[0] = idx
        out[1] = bucket
        return true
    }

    private fun addSnow(idx: Int, bucket: Int, r: Float) {
        val h = apps[idx].pile
        val add = dp(0.45f) * (0.6f + r / dp(3f))
        if (h[bucket] + add <= maxPile) {
            h[bucket] += add
        } else {
            h[bucket] = maxPile
            val nb = bucket + (if (rnd.nextBoolean()) 1 else -1)
            if (nb in 0 until PILE_COLS) h[nb] = min(maxPile, h[nb] + add * 0.7f)
        }
        if (bucket > 0) h[bucket - 1] += (h[bucket] - h[bucket - 1]) * 0.10f
        if (bucket < PILE_COLS - 1) h[bucket + 1] += (h[bucket] - h[bucket + 1]) * 0.10f
    }

    private fun santaY(): Float = topInset + dp(58f)

    private fun updateCountdown() {
        val now = System.currentTimeMillis()
        val sec = now / 1000L
        if (sec == cdSec) return
        cdSec = sec
        if (newYear == 0L || now >= newYear) {
            val c = Calendar.getInstance()
            c.set(c.get(Calendar.YEAR) + 1, Calendar.JANUARY, 1, 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            newYear = c.timeInMillis
        }
        var left = (newYear - now) / 1000L
        val d = left / 86400L
        left %= 86400L
        val h = left / 3600L
        left %= 3600L
        val m = left / 60L
        val s = left % 60L
        cdText = String.format(Locale.getDefault(), "До Нового года: %dд %02d:%02d:%02d", d, h, m, s)
    }

    private fun updateSanta(dt: Float) {
        if (!santaOn) {
            if (time >= nextSanta && width > 0) {
                santaOn = true
                santaX = -dp(150f)
                lastGiftCol = -1
            }
            return
        }
        santaX += dp(115f) * dt
        if (cellW > 0f) {
            val gx = santaX + dp(28f)
            val c = (gx / cellW).toInt()
            if (gx > 0f && c < cols) {
                val cx = (c + 0.5f) * cellW
                if (abs(gx - cx) < dp(7f) && c != lastGiftCol) {
                    lastGiftCol = c
                    if (rnd.nextFloat() < 0.6f) {
                        flakes.add(Flake(cx, santaY() + dp(14f), dp(120f), dp(9f), 0f, true))
                    }
                }
            }
        }
        if (santaX > width + dp(10f)) {
            santaOn = false
            nextSanta = time + 30f + rnd.nextFloat() * 30f
        }
    }

    private fun update(dt: Float) {
        if (scroller.computeScrollOffset()) {
            scrollYf = scroller.currY.toFloat().coerceIn(0f, maxScroll)
        }
        updateCountdown()
        gust *= (1f - min(1f, dt * 1.2f))

        val w = width.toFloat()
        if (w <= 0f) return

        val blizzard = time < blizzardUntil
        var wind = sin(time / 4.5f) * dp(14f) + tiltWind + gust
        if (blizzard) wind *= 2.5f

        spawnAcc += (if (blizzard) 120f else 26f) * dt
        while (spawnAcc >= 1f) {
            spawnAcc -= 1f
            if (flakes.size < 600) spawnFlake(blizzard)
        }

        val fit = flakes.iterator()
        while (fit.hasNext()) {
            val f = fit.next()
            f.y += f.vy * dt
            if (!f.gift) {
                val k = 0.5f + f.r / dp(4f)
                f.x += (wind * k + sin(f.phase + time * 1.4f) * dp(12f)) * dt
            }
            var remove = false
            if (apps.isNotEmpty() && hitIconTop(f.x, f.y, hit)) {
                val idx = hit[0]
                if (f.gift) {
                    giftLanded(idx)
                    remove = true
                } else if (idx != f.passed) {
                    if (rnd.nextFloat() < 0.2f) {
                        addSnow(idx, hit[1], f.r)
                        remove = true
                    } else {
                        f.passed = idx
                    }
                }
            }
            if (!remove && (f.y > height + dp(20f) || f.x < -dp(200f) || f.x > w + dp(200f))) {
                remove = true
            }
            if (remove) fit.remove()
        }

        val pit = particles.iterator()
        while (pit.hasNext()) {
            val p = pit.next()
            p.life -= dt
            if (p.life <= 0f) {
                pit.remove()
                continue
            }
            p.vy += dp(520f) * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
        }

        updateSanta(dt)
    }

    override fun onDraw(canvas: Canvas) {
        val nowN = System.nanoTime()
        var dt = (nowN - lastNanos) / 1e9f
        lastNanos = nowN
        if (dt < 0f) dt = 0f
        if (dt > 0.05f) dt = 0.05f
        time += dt
        update(dt)

        canvas.drawColor(Color.argb(105, 4, 10, 36))
        drawHeader(canvas)
        drawIcons(canvas)
        drawFlakes(canvas)
        drawParticles(canvas)
        if (santaOn) canvas.drawText("🎅🛷🦌🦌", santaX, santaY() + dp(10f), emojiLeft)
        drawGarland(canvas)
        drawMessage(canvas, nowN)

        postInvalidateOnAnimation()
    }

    private fun drawHeader(canvas: Canvas) {
        val ty = topInset + dp(96f) - scrollYf
        if (ty < -dp(60f)) return
        canvas.drawText("🎄 С Новым годом! 🎄", width / 2f, ty, titlePaint)
        canvas.drawText(cdText, width / 2f, ty + dp(28f), subPaint)
    }

    private fun drawIcons(canvas: Canvas) {
        if (cellW <= 0f || apps.isEmpty()) return
        val first = max(0, floor((scrollYf - gridTop) / cellH).toInt())
        val last = floor((scrollYf + height - gridTop) / cellH).toInt()
        val startIdx = first * cols
        val endIdx = min(apps.size, (last + 1) * cols)
        for (idx in startIdx until endIdx) {
            val item = apps[idx]
            val col = idx % cols
            val left = iconLeft(idx)
            val top = iconTopY(idx)
            dst.set(left, top, left + iconSize, top + iconSize)
            canvas.drawBitmap(item.icon, null, dst, iconPaint)

            var sl = item.shortLabel
            if (sl == null) {
                sl = TextUtils.ellipsize(item.label, labelPaint, cellW - dp(8f), TextUtils.TruncateAt.END).toString()
                item.shortLabel = sl
            }
            canvas.drawText(sl, col * cellW + cellW / 2f, top + iconSize + dp(18f), labelPaint)

            drawPile(canvas, item, left, top)
        }
    }

    private fun drawPile(canvas: Canvas, item: AppItem, left: Float, top: Float) {
        if (item.snow() < 0.4f) return
        val n = PILE_COLS + 2
        val step = iconSize / PILE_COLS
        pxs[0] = left
        pys[0] = 0f
        for (i in 0 until PILE_COLS) {
            pxs[i + 1] = left + (i + 0.5f) * step
            pys[i + 1] = item.pile[i]
        }
        pxs[n - 1] = left + iconSize
        pys[n - 1] = 0f

        val base = top + dp(1f)
        pilePath.reset()
        pilePath.moveTo(pxs[0], base - pys[0])
        for (i in 1 until n - 1) {
            val mx = (pxs[i] + pxs[i + 1]) / 2f
            val my = ((base - pys[i]) + (base - pys[i + 1])) / 2f
            pilePath.quadTo(pxs[i], base - pys[i], mx, my)
        }
        pilePath.lineTo(pxs[n - 1], base - pys[n - 1])
        for (i in n - 1 downTo 0) {
            pilePath.lineTo(pxs[i], base + pys[i] * 0.4f)
        }
        pilePath.close()
        canvas.drawPath(pilePath, snowPaint)
        canvas.drawPath(pilePath, snowEdge)
    }

    private fun drawFlakes(canvas: Canvas) {
        for (f in flakes) {
            if (f.gift) {
                canvas.drawText("🎁", f.x, f.y + dp(8f), emojiCenter)
            } else {
                canvas.drawCircle(f.x, f.y, f.r, flakePaint)
            }
        }
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            partPaint.color = p.color
            partPaint.alpha = (255f * (p.life / p.maxLife)).toInt().coerceIn(0, 255)
            canvas.drawCircle(p.x, p.y, p.r, partPaint)
        }
    }

    private fun garlandY(x: Float, seg: Float, sag: Float): Float {
        return sag * sin(PI_F * (x % seg) / seg)
    }

    private fun drawGarland(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) return
        val y0 = topInset + dp(4f)
        val seg = w / 3f
        val sag = dp(14f)

        wirePath.reset()
        wirePath.moveTo(0f, y0)
        val steps = 60
        for (s in 1..steps) {
            val x = w * s / steps
            wirePath.lineTo(x, y0 + garlandY(x, seg, sag))
        }
        canvas.drawPath(wirePath, wirePaint)

        var bx = dp(10f)
        var i = 0
        val phase = (time * 3f).toInt()
        while (bx < w) {
            val by = y0 + garlandY(bx, seg, sag) + dp(6f)
            val c = lights[(i + phase / 3) % lights.size]
            val on = (i + phase) % 4 != 0
            bulbPaint.color = c
            bulbPaint.alpha = if (on) 70 else 25
            canvas.drawCircle(bx, by, dp(10f), bulbPaint)
            bulbPaint.color = c
            bulbPaint.alpha = if (on) 255 else 90
            canvas.drawCircle(bx, by, dp(4.5f), bulbPaint)
            bx += dp(28f)
            i++
        }
    }

    private fun drawMessage(canvas: Canvas, nowN: Long) {
        if (nowN >= messageUntil || message.isEmpty()) return
        val remaining = (messageUntil - nowN) / 1e9f
        val a = min(1f, remaining * 1.5f)
        val tw = msgPaint.measureText(message)
        val cx = width / 2f
        val cy = height - bottomInset - dp(46f)
        msgRect.set(cx - tw / 2f - dp(16f), cy - dp(22f), cx + tw / 2f + dp(16f), cy + dp(12f))
        msgBg.alpha = (170 * a).toInt()
        canvas.drawRoundRect(msgRect, dp(18f), dp(18f), msgBg)
        msgPaint.alpha = (255 * a).toInt()
        canvas.drawText(message, cx, cy, msgPaint)
    }
}
