package com.example.sensordrive

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.*
import kotlin.random.Random

class GameView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), Runnable {

    // ── Thread & State ──────────────────────────────────────────────
    private var gameThread: Thread? = null
    private var isPlaying = false
    var isCalibrating = true
    var isGameOver = false
    private var isPaused = false
    private var showMenu = false
    private var showScores = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = false
        isFilterBitmap = false
    }

    // ── Menu System ─────────────────────────────────────────────────
    private data class MenuButton(val text: String, val x: Float, val y: Float, val width: Float, val height: Float, val color: Int, val action: () -> Unit)
    private val menuButtons = mutableListOf<MenuButton>()
    private var menuAnimationProgress = 0f
    private var menuTargetProgress = 0f

    // ── Scoring System ──────────────────────────────────────────────
    private data class ScoreEntry(val score: Int, val distance: Float, val date: String)
    private val scores = mutableListOf<ScoreEntry>()
    private var currentDistance = 0f
    private var totalDistance = 0f
    private var bestDistance = 0f
    private var sessionBestScore = 0
    private val prefs = context.getSharedPreferences("SensorDrivePrefs", Context.MODE_PRIVATE)

    // ── Assets ──────────────────────────────────────────────────────
    private fun loadBitmap(resId: Int, targetW: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val raw = BitmapFactory.decodeResource(resources, resId, opts)
        val h = (raw.height * (targetW.toFloat() / raw.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(raw, targetW, h, false)
        raw.recycle()
        return scaled
    }

    private val carBitmap  by lazy { loadBitmap(R.drawable.carro_traseira, 120) }
    private val coneBitmap by lazy { loadBitmap(R.drawable.cones,           60) }
    private val redeBitmap by lazy { loadBitmap(R.drawable.rede,           540) }
    private val phoneIcon  by lazy { loadBitmap(R.drawable.telemovel,       90) }
    private val nitroIcon  by lazy { loadBitmap(R.drawable.nitro,           80) }
    private val starIcon   by lazy { loadBitmap(R.drawable.estrela,         44) }
    private val flagIcon   by lazy { loadBitmap(R.drawable.bandeira,        80) }

    // Carregar o GIF Animado
    private val bgGif: Movie? by lazy {
        try {
            resources.openRawResource(R.raw.fundo).use { stream ->
                Movie.decodeStream(stream)
            }
        } catch (e: Exception) { null }
    }

    // ── Neon palette ─────────────────────────────────────────────────
    private val cyan       = Color.parseColor("#00FFFF")
    private val magenta    = Color.parseColor("#FF44FF")
    private val neonYellow = Color.parseColor("#FFEE00")
    private val neonRed    = Color.parseColor("#FF3333")
    private val neonGreen  = Color.parseColor("#00FF88")
    private val darkBg     = Color.parseColor("#07001A")

    // ────────────────────────────────────────────────────────────────
    // SENSOR — Calibração e Steering Simétrico (CORRIGIDO)
    // ────────────────────────────────────────────────────────────────
    private var rawRoll = 0f
    private var rollOffset = 0f
    private var sensorFiltered = 0f
    private var steerInput = 0f

    private val FILTER_ALPHA = 0.15f
    private val DEADZONE     = 0.03f   // Aumentado ligeiramente para evitar drift no centro
    private val MAX_TILT     = 0.40f

    fun updateSensor(raw: Float) {
        rawRoll = raw

        if (isGameOver || isCalibrating || isPaused) return

        // 1. Calcular o desvio (delta)
        var delta = rawRoll - rollOffset

        // 2. Normalizar o delta para evitar saltos (wrap-around)
        while (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
        while (delta < -Math.PI) delta += (2 * Math.PI).toFloat()

        // 3. Suavização Simétrica
        sensorFiltered += FILTER_ALPHA * (delta - sensorFiltered)

        // 4. Aplicar Deadzone e Normalizar para -1 a 1
        val afterDz = when {
            sensorFiltered >  DEADZONE -> sensorFiltered - DEADZONE
            sensorFiltered < -DEADZONE -> sensorFiltered + DEADZONE
            else -> 0f
        }

        steerInput = (afterDz / (MAX_TILT - DEADZONE)).coerceIn(-1f, 1f)
    }

    fun updateRotation(z: Float) = updateSensor(z)

    // ── Game State ───────────────────────────────────────────────────
    private var carX = 0f
    private var score = 0
    private var hiScore = 0
    private var speedMultiplier = 1.0f
    private var nitroCharge = 100f
    private var isNitroActive = false
    private var gameSpeed = 7f
    private val rng = Random.Default

    private var conesDodged = 0
    private var currentStreak = 0
    private var longestStreak = 0

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        hiScore = prefs.getInt("hiScore", 0)
    }

    // ── Cones ────────────────────────────────────────────────────────
    private data class Cone(var x: Float, var y: Float, var hit: Boolean = false, var passed: Boolean = false)
    private val cones = mutableListOf<Cone>()
    private var coneSpawnTimer = 0

    // ── Road & FX ────────────────────────────────────────────────────
    private var roadScroll = 0f
    private var flashFrames = 0

    // ── Popups ───────────────────────────────────────────────────────
    private data class Popup(val text: String, val color: Int, var x: Float, var y: Float, var life: Int)
    private val popups = mutableListOf<Popup>()

    // ── Car animation ────────────────────────────────────────────────
    private var carLean = 0f
    private var carScaleX = 1f
    private var carVelX = 0f
    private var prevCarX = 0f

    // ── Wind tunnel ──────────────────────────────────────────────────
    private data class WindLine(var x: Float, var y: Float, val angle: Float, var speed: Float,
                                val length: Float, val color: Int, var alpha: Int, var life: Int, val maxLife: Int)
    private val windLines = mutableListOf<WindLine>()
    private var windSpawnTimer = 0
    private var nitroIntensity = 0f

    private var menuAlpha = 0f

    // ────────────────────────────────────────────────────────────────
    // Touch
    // ────────────────────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val x = event.x; val y = event.y
        val W = width.toFloat(); val H = height.toFloat()

        when {
            showScores -> {
                if (isInRect(x, y, W/2f-100f, H-130f, 200f, 55f)) showScores = false
                return true
            }
            showMenu -> {
                handleMenuTouch(x, y, W, H); return true
            }
            isGameOver -> {
                val bw = 260f; val bh = 52f; val bx = W/2f - bw/2f
                if (isInRect(x, y, bx, H*0.77f, bw, bh)) { resetGame(); isCalibrating = false; rollOffset = rawRoll; sensorFiltered = 0f }
                if (isInRect(x, y, bx, H*0.87f, bw, bh)) { resetGame(); openMenu() }
                return true
            }
            isCalibrating -> {
                rollOffset = rawRoll
                sensorFiltered = 0f
                steerInput = 0f
                isCalibrating = false
                return true
            }
            else -> {
                if (isInRect(x, y, W-80f, 20f, 60f, 60f)) { openMenu(); return true }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) =
        x in rx..(rx+rw) && y in ry..(ry+rh)

    private fun handleMenuTouch(x: Float, y: Float, W: Float, H: Float) {
        val bw = 280f; val bh = 65f; val bx = W/2f - bw/2f
        val sy = H/2f - 80f; val g = 80f
        when {
            isInRect(x, y, bx, sy,       bw, bh) -> closeMenu()
            isInRect(x, y, bx, sy+g,     bw, bh) -> { closeMenu(); resetGame(); rollOffset = rawRoll; sensorFiltered = 0f; isCalibrating = false }
            isInRect(x, y, bx, sy+g*2f,  bw, bh) -> showScores = true
            isInRect(x, y, bx, sy+g*3f,  bw, bh) -> (context as? android.app.Activity)?.finish()
        }
    }

    private fun openMenu()  { isPaused = true;  showMenu = true  }
    private fun closeMenu() { isPaused = false; showMenu = false }

    fun setNitro(active: Boolean) {
        if (isPaused) return
        isNitroActive = active && nitroCharge > 5f
        speedMultiplier = if (isNitroActive) 2.4f else 1f + minOf(score / 400f, 0.7f)
    }

    fun resetGame() {
        score = 0; currentDistance = 0f; conesDodged = 0; currentStreak = 0; longestStreak = 0
        cones.clear(); popups.clear(); windLines.clear()
        nitroCharge = 100f; isNitroActive = false; speedMultiplier = 1f
        gameSpeed = 7f; roadScroll = 0f; coneSpawnTimer = 0; flashFrames = 0
        sensorFiltered = 0f; steerInput = 0f
        carLean = 0f; carScaleX = 1f; carVelX = 0f; prevCarX = 0f
        nitroIntensity = 0f; windSpawnTimer = 0
        isGameOver = false; isCalibrating = true; isPaused = false; showMenu = false
        menuAnimationProgress = 0f
    }

    private fun saveHiScore() {
        if (score > hiScore) { hiScore = score; prefs.edit().putInt("hiScore", hiScore).apply() }
    }

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) { Thread.sleep(8); continue }
            val canvas = holder.lockCanvas() ?: continue
            try {
                if (!isCalibrating && !isGameOver && !isPaused) updateLogic()
                val tgt = if (showMenu || showScores) 1f else 0f
                menuAlpha += (tgt - menuAlpha) * 0.18f
                drawFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Thread.sleep(16)
        }
    }

    private fun updateLogic() {
        val W = width.toFloat(); val H = height.toFloat()

        // ── Steering ─────────────────────────────────────────────────
        val laneLeft  = W * 0.14f
        val laneRight = W * 0.86f - carBitmap.width
        val center    = (laneLeft + laneRight) / 2f
        val half      = (laneRight - laneLeft) / 2f
        val targetX   = (center + steerInput * half).coerceIn(laneLeft, laneRight)
        carX += (targetX - carX) * 0.11f

        // ── Car animation ─────────────────────────────────────────────
        carVelX = carX - prevCarX; prevCarX = carX
        val targetLean  = (steerInput * 14f + (carVelX * 2.2f).coerceIn(-6f, 6f)).coerceIn(-18f, 18f)
        val targetScale = 1f + abs(steerInput) * 0.06f + abs(carVelX) * 0.016f
        carLean   += (targetLean  - carLean)   * 0.13f
        carScaleX += (targetScale - carScaleX) * 0.18f

        // ── Road scroll ───────────────────────────────────────────────
        roadScroll += 18f * speedMultiplier
        if (roadScroll > H) roadScroll -= H

        // ── Nitro ─────────────────────────────────────────────────────
        if (isNitroActive) {
            nitroCharge = (nitroCharge - 0.65f).coerceAtLeast(0f)
            if (nitroCharge == 0f) { isNitroActive = false; speedMultiplier = 1f + minOf(score/400f, 0.7f) }
            nitroIntensity = (nitroIntensity + 0.07f).coerceAtMost(1f)
        } else {
            nitroCharge = (nitroCharge + 0.20f).coerceAtMost(100f)
            nitroIntensity = (nitroIntensity - 0.04f).coerceAtLeast(0f)
        }
        updateWindTunnel(W, H)

        // ── Score ─────────────────────────────────────────────────────
        score = (score + speedMultiplier).toInt()
        currentDistance += gameSpeed * speedMultiplier * 0.08f
        gameSpeed = 7f + minOf(score / 500f, 9f)
        if (score > hiScore) hiScore = score
        if (flashFrames > 0) flashFrames--

        // ── Cone spawn ────────────────────────────────────────────────
        if (--coneSpawnTimer <= 0) {
            val lw = W * 0.60f; val lx = (W - lw) / 2f
            cones.add(Cone(lx + rng.nextFloat() * (lw - coneBitmap.width), -coneBitmap.height.toFloat()))
            coneSpawnTimer = (85 - minOf(score / 80, 50)).coerceAtLeast(30)
        }

        // ── Cones ────────────────────────────────────────────────────
        val carCY = H - 350f
        val carRect = RectF(carX+12f, carCY+18f, carX+carBitmap.width-12f, carCY+carBitmap.height-10f)
        val iter = cones.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            c.y += (gameSpeed + 4f) * speedMultiplier

            if (!c.passed && !c.hit && c.y > carCY + carBitmap.height) {
                c.passed = true; conesDodged++; currentStreak++
                if (currentStreak > longestStreak) longestStreak = currentStreak
                val bonus = 25 + (currentStreak / 3) * 10
                score += bonus
                popups.add(Popup("+$bonus", neonGreen, c.x + coneBitmap.width/2f, c.y - 40f, 38))
            }

            if (c.y > H + coneBitmap.height) { iter.remove(); continue }

            if (!c.hit) {
                val cr = RectF(c.x+8f, c.y+8f, c.x+coneBitmap.width-8f, c.y+coneBitmap.height-8f)
                if (RectF.intersects(carRect, cr)) {
                    c.hit = true; iter.remove(); currentStreak = 0

                    score -= 50; flashFrames = 12
                    popups.add(Popup("-50", neonRed, carX+carBitmap.width/2f, carCY-20f, 40))

                    if (score <= 0) {
                        score = 0
                        isGameOver = true
                        saveHiScore()
                        return
                    }
                }
            }
        }
        popups.removeAll { it.life <= 0 }
        popups.forEach { it.y -= 1.8f; it.life-- }
    }

    private fun updateWindTunnel(W: Float, H: Float) {
        if (nitroIntensity <= 0.02f) { windLines.clear(); return }
        val ox = W/2f; val oy = H*0.40f
        val rate = (7f - nitroIntensity*5.5f).toInt().coerceAtLeast(1)
        if (--windSpawnTimer <= rate) {
            windSpawnTimer = rate
            repeat((nitroIntensity*4f).toInt().coerceAtLeast(1)) {
                val raw = rng.nextFloat()*2f*PI.toFloat()
                val ang = atan2(sin(raw)*0.45f, cos(raw))
                val ml  = rng.nextInt(20, 38)
                windLines.add(WindLine(ox, oy, ang, rng.nextFloat()*4f+2.5f,
                    rng.nextFloat()*55f+25f, if (rng.nextBoolean()) cyan else magenta, 0, ml, ml))
            }
        }
        val wi = windLines.iterator()
        while (wi.hasNext()) {
            val wl = wi.next(); if (--wl.life <= 0) { wi.remove(); continue }
            wl.speed = (wl.speed*1.055f).coerceAtMost(60f)
            wl.x += cos(wl.angle)*wl.speed; wl.y += sin(wl.angle)*wl.speed
            val lr = wl.life.toFloat()/wl.maxLife
            wl.alpha = when {
                lr > 0.8f  -> ((1f-lr)/0.2f*210f*nitroIntensity).toInt()
                lr < 0.22f -> (lr/0.22f*210f*nitroIntensity).toInt()
                else       -> (210f*nitroIntensity).toInt()
            }.coerceIn(0, 255)
        }
    }

    private fun drawFrame(canvas: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()

        // 1. Desenhar o Fundo GIF animado
        if (bgGif != null) {
            val now = android.os.SystemClock.uptimeMillis()
            val dur = if (bgGif!!.duration() == 0) 1000 else bgGif!!.duration()
            bgGif!!.setTime((now % dur).toInt())

            // Escalar GIF para cobrir o ecrã
            val scale = maxOf(W / bgGif!!.width(), H / bgGif!!.height())
            canvas.save()
            canvas.scale(scale, scale)
            val dx = (W / scale - bgGif!!.width()) / 2f
            val dy = (H / scale - bgGif!!.height()) / 2f
            bgGif!!.draw(canvas, dx, dy)
            canvas.restore()
        } else {
            canvas.drawColor(darkBg) // Fallback caso o GIF falhe
        }

        // 2. Camadas do Jogo
        drawRede(canvas, W, H)
        drawRoad(canvas, W, H)

        when {
            isGameOver    -> { drawPlayfield(canvas, W, H); drawGameOver(canvas, W, H) }
            isCalibrating -> drawCalibrate(canvas, W, H)
            else          -> { drawPlayfield(canvas, W, H); drawHUD(canvas, W, H); drawMenuBtn(canvas, W) }
        }
        if (menuAlpha > 0.01f) drawMenuOverlay(canvas, W, H)
    }

    private fun drawRede(canvas: Canvas, W: Float, H: Float) {
        paint.alpha = 50
        canvas.drawBitmap(redeBitmap, W/2f-redeBitmap.width/2f, H/2f-redeBitmap.height/2f, paint)
        paint.alpha = 255
    }

    private fun drawRoad(canvas: Canvas, W: Float, H: Float) {
        val cx = W/2f; val vy = H*0.38f; val lw = W*0.65f
        val p = Path().apply {
            moveTo(cx-lw*0.1f,vy); lineTo(cx+lw*0.1f,vy); lineTo(cx+lw/2f,H); lineTo(cx-lw/2f,H); close()
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(cx,vy,cx,H, Color.argb(60,8,0,40), Color.argb(200,12,0,50), Shader.TileMode.CLAMP)
        canvas.drawPath(p, paint); paint.shader = null
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = cyan
        paint.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.SOLID)
        canvas.drawLine(cx-lw*0.1f,vy,cx-lw/2f,H,paint); canvas.drawLine(cx+lw*0.1f,vy,cx+lw/2f,H,paint)
        paint.maskFilter = null
        for (i in 0..14) {
            val t = ((i/14f) + roadScroll/H) % 1f; if (t < 0.04f) continue
            val yy = vy + t*(H-vy); val pr = (yy-vy)/(H-vy)
            val hw = (lw*0.06f + (lw*0.42f-lw*0.06f)*pr)*0.12f
            paint.strokeWidth = 1f+pr*3.5f; paint.color = Color.argb((80+pr*140).toInt(),255,0,255)
            canvas.drawLine(cx-hw,yy,cx+hw,yy,paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawPlayfield(canvas: Canvas, W: Float, H: Float) {
        if (isGameOver || isCalibrating) return
        if (flashFrames > 0) {
            paint.color = Color.argb((flashFrames*18).coerceAtMost(160),255,40,40)
            canvas.drawRect(0f,0f,W,H,paint)
        }
        if (nitroIntensity > 0.01f) drawWindTunnelFx(canvas, W, H)
        cones.forEach { canvas.drawBitmap(coneBitmap, it.x, it.y, pixelPaint) }

        val cx = carX+carBitmap.width/2f; val cy = H-350f+carBitmap.height/2f
        drawTyreTracks(canvas, cx, cy)
        canvas.save(); canvas.translate(cx,cy); canvas.rotate(carLean); canvas.scale(carScaleX,1f)
        if (isNitroActive) {
            val fp = Paint(Paint.ANTI_ALIAS_FLAG)
            val fw = carBitmap.width*0.28f
            fp.shader = LinearGradient(0f,carBitmap.height/2f-4f,0f,carBitmap.height/2f+80f,
                intArrayOf(Color.WHITE,magenta,Color.argb(130,150,0,220),Color.TRANSPARENT),
                floatArrayOf(0f,0.2f,0.6f,1f), Shader.TileMode.CLAMP)
            canvas.drawRect(-fw/2f,carBitmap.height/2f-4f,fw/2f,carBitmap.height/2f+80f,fp)
            val fl = abs(sin(System.currentTimeMillis()/55.0)).toFloat()
            fp.shader = LinearGradient(0f,carBitmap.height/2f,0f,carBitmap.height/2f+50f+fl*22f,
                intArrayOf(Color.argb(210,255,100,255),Color.TRANSPARENT),floatArrayOf(0f,1f),Shader.TileMode.CLAMP)
            val fw2 = fw*(0.45f+fl*0.35f)
            canvas.drawRect(-fw2/2f,carBitmap.height/2f,fw2/2f,carBitmap.height/2f+50f+fl*22f,fp)
        }
        canvas.drawBitmap(carBitmap,-carBitmap.width/2f,-carBitmap.height/2f,pixelPaint)
        canvas.restore()

        pixelPaint.textAlign = Paint.Align.CENTER
        popups.forEach {
            pixelPaint.color = it.color; pixelPaint.textSize = 28f
            pixelPaint.alpha = ((it.life/40f)*255).toInt().coerceIn(0,255)
            canvas.drawText(it.text, it.x, it.y, pixelPaint)
        }
        pixelPaint.alpha = 255; pixelPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawTyreTracks(canvas: Canvas, cx: Float, cy: Float) {
        val v = abs(carVelX); if (v < 0.4f) return
        val tc = if (carVelX > 0) cyan else magenta
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2f
        paint.color = Color.argb(((v-0.4f)/2.5f*170f).toInt().coerceIn(0,170), Color.red(tc), Color.green(tc), Color.blue(tc))
        paint.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)
        val ox = carBitmap.width*0.30f; val wy = cy+carBitmap.height/2f-8f; val tl = (v*20f).coerceAtMost(45f)
        canvas.drawLine(cx-ox,wy,cx-ox,wy+tl,paint); canvas.drawLine(cx+ox,wy,cx+ox,wy+tl,paint)
        paint.maskFilter = null; paint.style = Paint.Style.FILL
    }

    private fun drawWindTunnelFx(canvas: Canvas, W: Float, H: Float) {
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        windLines.forEach { wl ->
            if (wl.alpha < 5) return@forEach
            val tx = wl.x-cos(wl.angle)*wl.length; val ty = wl.y-sin(wl.angle)*wl.length
            lp.shader = LinearGradient(tx,ty,wl.x,wl.y,Color.TRANSPARENT,
                Color.argb(wl.alpha,Color.red(wl.color),Color.green(wl.color),Color.blue(wl.color)),Shader.TileMode.CLAMP)
            lp.strokeWidth = 1.5f+nitroIntensity*2.5f; canvas.drawLine(tx,ty,wl.x,wl.y,lp)
        }
        if (nitroIntensity > 0.25f) {
            val va = ((nitroIntensity-0.25f)/0.75f*130f).toInt()
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f,H/2f,W*0.2f,H/2f,Color.argb(va,0,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,W*0.2f,H,paint)
            paint.shader = LinearGradient(W,H/2f,W*0.8f,H/2f,Color.argb(va,255,68,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            canvas.drawRect(W*0.8f,0f,W,H,paint); paint.shader = null
            if (nitroIntensity > 0.65f) {
                val aa = ((nitroIntensity-0.65f)/0.35f*40f).toInt()
                paint.color = Color.argb(aa,255,68,255); canvas.drawRect(0f,0f,W*0.06f,H,paint)
                paint.color = Color.argb(aa,0,255,255); canvas.drawRect(W*0.94f,0f,W,H,paint)
            }
        }
    }

    private fun drawHUD(canvas: Canvas, W: Float, H: Float) {
        hudBox(canvas, 20f, 20f, 175f, 86f); hudLbl(canvas,"PONTOS",38f,50f,cyan); hudVal(canvas,"$score",38f,84f)
        hudBox(canvas,W/2f-100f,20f,200f,86f); hudLbl(canvas,"RECORDE",W/2f-82f,50f,neonYellow); hudVal(canvas,"$hiScore",W/2f-82f,84f)
        val kmh=(80f*speedMultiplier+(speedMultiplier-1f)*120f).toInt()
        hudBox(canvas,W-270f,20f,175f,86f); hudLbl(canvas,"VELOC.",W-252f,50f,magenta); hudVal(canvas,"$kmh km/h",W-252f,84f)
        hudBox(canvas,20f,116f,175f,66f); hudLbl(canvas,"DISTÂNCIA",38f,143f,neonGreen); hudVal(canvas,"${currentDistance.toInt()} m",38f,172f,22f)
        if (currentStreak >= 2) { hudBox(canvas,W/2f-100f,116f,200f,66f); hudLbl(canvas,"STREAK",W/2f-82f,143f,neonYellow); hudVal(canvas,"×$currentStreak",W/2f-82f,172f,26f) }
        drawNitroBar(canvas, W, H)
    }

    private fun hudBox(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style=Paint.Style.FILL; paint.color=Color.argb(180,0,0,20); canvas.drawRect(x,y,x+w,y+h,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=Color.argb(180,0,255,255); canvas.drawRect(x,y,x+w,y+h,paint); paint.style=Paint.Style.FILL
    }
    private fun hudLbl(canvas: Canvas, t: String, x: Float, y: Float, c: Int) { pixelPaint.textSize=15f; pixelPaint.color=c; canvas.drawText(t,x,y,pixelPaint) }
    private fun hudVal(canvas: Canvas, t: String, x: Float, y: Float, s: Float=25f) { pixelPaint.textSize=s; pixelPaint.color=Color.WHITE; canvas.drawText(t,x,y,pixelPaint) }

    private fun drawNitroBar(canvas: Canvas, W: Float, H: Float) {
        val bw=260f; val bh=24f; val bx=W/2f-bw/2f; val by=H-85f
        canvas.drawBitmap(nitroIcon,bx-nitroIcon.width-8f,by-18f,pixelPaint)
        pixelPaint.textSize=16f; pixelPaint.color=magenta; canvas.drawText("NITRO",bx,by-4f,pixelPaint)
        paint.style=Paint.Style.FILL; paint.color=Color.argb(180,30,0,40); canvas.drawRect(bx,by,bx+bw,by+bh,paint)
        val fw=bw*(nitroCharge/100f)
        if (fw>0f) { paint.shader=LinearGradient(bx,by,bx+fw,by,Color.parseColor("#9900CC"),magenta,Shader.TileMode.CLAMP); canvas.drawRect(bx,by,bx+fw,by+bh,paint); paint.shader=null }
        if (isNitroActive) {
            val ga=(160f+sin(System.currentTimeMillis()/80.0).toFloat()*60f).toInt().coerceIn(0,255)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=4f; paint.color=Color.argb(ga,255,68,255)
            paint.maskFilter=BlurMaskFilter(8f,BlurMaskFilter.Blur.SOLID); canvas.drawRect(bx,by,bx+bw,by+bh,paint); paint.maskFilter=null
        }
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=magenta; canvas.drawRect(bx,by,bx+bw,by+bh,paint); paint.style=Paint.Style.FILL
    }

    private fun drawMenuBtn(canvas: Canvas, W: Float) {
        val bx=W-78f; val by=22f; val bs=56f
        paint.style=Paint.Style.FILL; paint.color=Color.argb(180,0,0,20); canvas.drawRect(bx,by,bx+bs,by+bs,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=cyan; canvas.drawRect(bx,by,bx+bs,by+bs,paint)
        paint.style=Paint.Style.FILL; paint.color=cyan
        val lw=28f; val lx=bx+(bs-lw)/2f; val sy=by+15f
        for (i in 0..2) canvas.drawRect(lx,sy+i*9f,lx+lw,sy+i*9f+4f,paint)
    }

    private fun drawMenuOverlay(canvas: Canvas, W: Float, H: Float) {
        val a = menuAlpha
        paint.color = Color.argb((a*215).toInt(),0,0,10); canvas.drawRect(0f,0f,W,H,paint)
        if (a < 0.25f) return
        val ca = ((a-0.25f)/0.75f).coerceIn(0f,1f)

        if (showScores) { drawScoresScreen(canvas,W,H,ca); return }

        pixelPaint.textAlign=Paint.Align.CENTER; pixelPaint.textSize=44f
        pixelPaint.color=Color.argb((ca*255).toInt(),0,255,255); canvas.drawText("MENU",W/2f,H/2f-155f,pixelPaint)

        val items = listOf("CONTINUAR" to cyan, "REINICIAR" to neonYellow, "PONTUAÇÕES" to magenta, "SAIR" to neonRed)
        val bw=280f; val bh=62f; val bx=W/2f-bw/2f; val sy=H/2f-80f; val g=80f
        items.forEachIndexed { i,(lbl,col) ->
            val by=sy+i*g; val ia=(ca*255).toInt()
            paint.maskFilter=BlurMaskFilter(10f,BlurMaskFilter.Blur.SOLID)
            paint.color=Color.argb((ca*50).toInt(),Color.red(col),Color.green(col),Color.blue(col))
            paint.style=Paint.Style.FILL; canvas.drawRect(bx-4f,by-4f,bx+bw+4f,by+bh+4f,paint); paint.maskFilter=null
            paint.color=Color.argb((ca*210).toInt(),20,0,40); canvas.drawRect(bx,by,bx+bw,by+bh,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f; paint.color=Color.argb(ia,Color.red(col),Color.green(col),Color.blue(col))
            canvas.drawRect(bx,by,bx+bw,by+bh,paint); paint.style=Paint.Style.FILL
            pixelPaint.textSize=22f; pixelPaint.color=Color.argb(ia,255,255,255); canvas.drawText(lbl,W/2f,by+bh/2f+9f,pixelPaint)
        }
        pixelPaint.textAlign=Paint.Align.LEFT
    }

    private fun drawScoresScreen(canvas: Canvas, W: Float, H: Float, a: Float) {
        val ia=(a*255).toInt()
        pixelPaint.textAlign=Paint.Align.CENTER; pixelPaint.textSize=34f
        pixelPaint.color=Color.argb(ia,0,255,255); canvas.drawText("PONTUAÇÕES",W/2f,H*0.12f,pixelPaint)
        pixelPaint.textSize=18f; pixelPaint.color=Color.argb((a*200).toInt(),200,200,200)
        canvas.drawText("RECORDE: $hiScore", W/2f, H*0.22f, pixelPaint)
        canvas.drawText("CONES DESVIADOS: $conesDodged", W/2f, H*0.30f, pixelPaint)
        canvas.drawText("MAIOR STREAK: $longestStreak", W/2f, H*0.37f, pixelPaint)
        canvas.drawText("DISTÂNCIA: ${currentDistance.toInt()} m", W/2f, H*0.44f, pixelPaint)
        val bx=W/2f-100f; val by=H-130f
        paint.style=Paint.Style.FILL; paint.color=Color.argb((a*200).toInt(),0,20,40); canvas.drawRect(bx,by,bx+200f,by+55f,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=Color.argb(ia,0,255,255); canvas.drawRect(bx,by,bx+200f,by+55f,paint); paint.style=Paint.Style.FILL
        pixelPaint.textSize=20f; pixelPaint.color=Color.argb(ia,255,255,255); canvas.drawText("VOLTAR",W/2f,by+36f,pixelPaint)
        pixelPaint.textAlign=Paint.Align.LEFT
    }

    private fun drawCalibrate(canvas: Canvas, W: Float, H: Float) {
        paint.color=Color.argb(220,0,0,15); canvas.drawRect(0f,0f,W,H,paint)
        pixelPaint.textAlign=Paint.Align.CENTER
        pixelPaint.textSize=54f; pixelPaint.color=cyan; canvas.drawText("SENSOR",W/2f,H*0.18f,pixelPaint)
        pixelPaint.color=magenta; canvas.drawText("DRIVE",W/2f,H*0.26f,pixelPaint)
        pixelPaint.textSize=20f; pixelPaint.color=neonYellow; canvas.drawText("SEGURA O TELEMÓVEL VERTICAL",W/2f,H*0.38f,pixelPaint)
        pixelPaint.textSize=16f; pixelPaint.color=Color.argb(210,255,255,255)
        canvas.drawText("Inclina ESQUERDA / DIREITA para conduzir",W/2f,H*0.45f,pixelPaint)
        canvas.drawBitmap(phoneIcon,W/2f-phoneIcon.width/2f,H*0.52f,pixelPaint)
        if ((System.currentTimeMillis()/500)%2==0L) {
            pixelPaint.textSize=22f; pixelPaint.color=Color.WHITE; canvas.drawText("TOQUE PARA COMEÇAR",W/2f,H*0.80f,pixelPaint)
        }
        pixelPaint.textAlign=Paint.Align.LEFT
    }

    private fun drawGameOver(canvas: Canvas, W: Float, H: Float) {
        paint.color=Color.argb(215,0,0,10); canvas.drawRect(0f,0f,W,H,paint)
        pixelPaint.textAlign=Paint.Align.CENTER
        val pulse=(sin(System.currentTimeMillis()/280.0)*0.3f+0.7f).toFloat()
        pixelPaint.textSize=60f; pixelPaint.color=Color.argb((255*pulse).toInt(),255,40,40); canvas.drawText("GAME OVER",W/2f,H*0.18f,pixelPaint)
        pixelPaint.textSize=24f; pixelPaint.color=Color.WHITE; canvas.drawText("PONTUAÇÃO FINAL",W/2f,H*0.28f,pixelPaint)
        pixelPaint.textSize=52f; pixelPaint.color=cyan; canvas.drawText("$score",W/2f,H*0.37f,pixelPaint)
        pixelPaint.textSize=18f
        pixelPaint.color=neonGreen;  canvas.drawText("${currentDistance.toInt()} m percorridos",W/2f,H*0.45f,pixelPaint)
        pixelPaint.color=neonYellow; canvas.drawText("$conesDodged cones desviados",W/2f,H*0.51f,pixelPaint)
        pixelPaint.color=magenta;    canvas.drawText("Melhor streak: $longestStreak",W/2f,H*0.56f,pixelPaint)
        canvas.drawBitmap(starIcon,W/2f-starIcon.width/2f,H*0.60f,pixelPaint)
        pixelPaint.textSize=20f; pixelPaint.color=neonYellow; canvas.drawText("RECORDE: $hiScore",W/2f,H*0.70f,pixelPaint)
        val bw=260f; val bh=52f; val bx=W/2f-bw/2f
        goBtn(canvas,bx,H*0.77f,bw,bh,neonYellow,"TENTAR NOVAMENTE",W)
        goBtn(canvas,bx,H*0.87f,bw,bh,cyan,"MENU",W)
        pixelPaint.textAlign=Paint.Align.LEFT
    }

    private fun goBtn(canvas: Canvas, bx: Float, by: Float, bw: Float, bh: Float, col: Int, lbl: String, W: Float) {
        paint.style=Paint.Style.FILL; paint.color=Color.argb(200,0,15,30); canvas.drawRect(bx,by,bx+bw,by+bh,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f; paint.color=col; canvas.drawRect(bx,by,bx+bw,by+bh,paint); paint.style=Paint.Style.FILL
        pixelPaint.textSize=20f; pixelPaint.color=Color.WHITE; pixelPaint.textAlign=Paint.Align.CENTER; canvas.drawText(lbl,W/2f,by+bh/2f+8f,pixelPaint)
    }

    fun resume() { isPlaying=true; gameThread=Thread(this); gameThread?.start() }
    fun pause()  { isPlaying=false; try { gameThread?.join(200) } catch(_:Exception){} }
}