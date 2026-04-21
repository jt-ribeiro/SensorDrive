package com.example.sensordrive

import android.content.Context
import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.SurfaceView
import kotlin.math.*
import kotlin.random.Random

class GameView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), Runnable {

    // ── CONTROLADOR DE ÁUDIO ──
    private var audioController: MainActivity? = null
    fun setAudioController(activity: MainActivity) { audioController = activity }

    private fun updateAudioState() {
        val isDriving = !isCalibrating && !isGameOver && !isPaused && !showMenu && !showScores
        audioController?.setCarEnginePlaying(isDriving)
    }

    // ── THREAD & STATE ──
    private var gameThread: Thread? = null
    private var isPlaying = false
    var isCalibrating = true
    var isGameOver = false
    private var isPaused = false
    private var showMenu = false
    private var showScores = false
    private var isInitialized = false

    // ── PAINT & OBJETOS PRÉ-ALOCADOS ──
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        isFilterBitmap = false
    }
    private val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val purpleConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = LightingColorFilter(GameConstants.MAGENTA, 0x000000)
    }

    private val flameColors1 = intArrayOf(Color.WHITE, GameConstants.MAGENTA, Color.argb(130, 150, 0, 220), Color.TRANSPARENT)
    private val flameStops1 = floatArrayOf(0f, 0.2f, 0.6f, 1f)
    private val flameColors2 = intArrayOf(Color.argb(210, 255, 100, 255), Color.TRANSPARENT)
    private val flameStops2 = floatArrayOf(0f, 1f)

    private val carRect = RectF()
    private val objRect = RectF()
    private val lightningPath = Path()

    // ── LISTAS DE ENTIDADES (Usando GameEntities.kt) ──
    private val menuButtons = mutableListOf<MenuButton>()
    private val cones = mutableListOf<Cone>()
    private val bonusStars = mutableListOf<BonusStar>()
    private val popups = mutableListOf<Popup>()
    private val windLines = mutableListOf<WindLine>()

    private var menuAnimationProgress = 0f
    private var menuAlpha = 0f

    // ── SCORING & MECÂNICAS ──
    private var score = 0
    private var hiScore = 0
    private var lives = 3
    private var currentDistance = 0f
    private var distanceSpeedMultiplier = 1.0f
    private var nextDistanceThreshold = GameConstants.DISTANCE_MULTIPLIER_THRESHOLD
    private var confusionEndTime = 0L
    private var bonusNitroEndTime = 0L

    private val prefs = context.getSharedPreferences("SensorDrivePrefs", Context.MODE_PRIVATE)

    // ── ASSETS ──
    private lateinit var carBitmap: Bitmap
    private lateinit var coneBitmap: Bitmap
    private lateinit var phoneIcon: Bitmap
    private lateinit var nitroIcon: Bitmap
    private lateinit var starIcon: Bitmap
    private var bgGifDrawable: Drawable? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isInitialized && w > 0 && h > 0) {
            initAssets(w)
            carX = w / 2f - carBitmap.width / 2f
            isInitialized = true
        }
    }

    private fun initAssets(screenWidth: Int) {
        carBitmap = loadBitmap(R.drawable.carro_traseira, (screenWidth * 0.16f).toInt())
        coneBitmap = loadBitmap(R.drawable.cones, (screenWidth * 0.09f).toInt())
        phoneIcon = loadBitmap(R.drawable.telemovel, (screenWidth * 0.12f).toInt())
        nitroIcon = loadBitmap(R.drawable.nitro, (screenWidth * 0.10f).toInt())
        starIcon = loadBitmap(R.drawable.estrela, (screenWidth * 0.10f).toInt())

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(resources, R.raw.fundo)
                bgGifDrawable = ImageDecoder.decodeDrawable(source)
                (bgGifDrawable as? AnimatedImageDrawable)?.start()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadBitmap(resId: Int, targetW: Int): Bitmap {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val raw = BitmapFactory.decodeResource(resources, resId, opts)
        val h = (raw.height * (targetW.toFloat() / raw.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(raw, targetW, h, true)
        raw.recycle()
        return scaled
    }

    private fun getLightningPath(x: Float, y: Float, w: Float, h: Float): Path {
        lightningPath.reset()
        lightningPath.moveTo(x + w * 0.6f, y)
        lightningPath.lineTo(x + w * 0.1f, y + h * 0.55f)
        lightningPath.lineTo(x + w * 0.5f, y + h * 0.55f)
        lightningPath.lineTo(x + w * 0.3f, y + h)
        lightningPath.lineTo(x + w * 0.9f, y + h * 0.45f)
        lightningPath.lineTo(x + w * 0.5f, y + h * 0.45f)
        lightningPath.close()
        return lightningPath
    }

    // ── SENSOR STEERING ──
    private var rawRoll = 0f
    private var rollOffset = 0f
    private var sensorFiltered = 0f
    private var steerInput = 0f

    fun getCurrentSteer(): Float = steerInput

    fun updateSensor(raw: Float) {
        rawRoll = raw
        if (isGameOver || isCalibrating || isPaused) return

        val delta = rawRoll - rollOffset
        sensorFiltered += GameConstants.SENSOR_FILTER_ALPHA * (delta - sensorFiltered)

        val afterDz = when {
            sensorFiltered > GameConstants.SENSOR_DEADZONE -> sensorFiltered - GameConstants.SENSOR_DEADZONE
            sensorFiltered < -GameConstants.SENSOR_DEADZONE -> sensorFiltered + GameConstants.SENSOR_DEADZONE
            else -> 0f
        }

        var finalSteer = (afterDz / GameConstants.SENSOR_MAX_TILT).coerceIn(-1f, 1f)

        if (System.currentTimeMillis() < confusionEndTime) finalSteer *= -1f
        steerInput = finalSteer
    }

    // ── VARIÁVEIS DE MOVIMENTO ──
    private var carX = 0f
    private var speedMultiplier = 1.0f
    private var nitroCharge = GameConstants.MAX_NITRO_CHARGE
    private var isManualNitro = false
    private var gameSpeed = GameConstants.INITIAL_GAME_SPEED
    private val rng = Random.Default

    private var conesDodged = 0
    private var currentStreak = 0
    private var longestStreak = 0
    private var coneSpawnTimer = 0
    private var roadScroll = 0f
    private var flashFrames = 0
    private var carLean = 0f
    private var carScaleX = 1f
    private var carVelX = 0f
    private var prevCarX = 0f
    private var windSpawnTimer = 0
    private var nitroIntensity = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        hiScore = prefs.getInt("hiScore", 0)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInitialized || event.action != MotionEvent.ACTION_DOWN) return super.onTouchEvent(event)
        val x = event.x; val y = event.y
        val W = width.toFloat(); val H = height.toFloat()

        when {
            showScores -> {
                if (isInRect(x, y, W/2f-100f, H-130f, 200f, 55f)) { showScores = false; updateAudioState() }
                return true
            }
            showMenu -> { handleMenuTouch(x, y, W, H); return true }
            isGameOver -> {
                val bw = 260f; val bh = 52f; val bx = W/2f - bw/2f
                if (isInRect(x, y, bx, H*0.77f, bw, bh)) { resetGame(); isCalibrating = false; rollOffset = rawRoll; sensorFiltered = 0f; updateAudioState() }
                if (isInRect(x, y, bx, H*0.87f, bw, bh)) { resetGame(); openMenu() }
                return true
            }
            isCalibrating -> {
                rollOffset = rawRoll; sensorFiltered = 0f; steerInput = 0f; isCalibrating = false; updateAudioState()
                return true
            }
            else -> {
                if (isInRect(x, y, W-80f, 120f, 60f, 60f)) { openMenu(); return true }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isInRect(x: Float, y: Float, rx: Float, ry: Float, rw: Float, rh: Float) = x in rx..(rx+rw) && y in ry..(ry+rh)

    private fun handleMenuTouch(x: Float, y: Float, W: Float, H: Float) {
        val bw = 280f; val bh = 65f; val bx = W/2f - bw/2f; val sy = H/2f - 80f; val g = 80f
        when {
            isInRect(x, y, bx, sy,       bw, bh) -> closeMenu()
            isInRect(x, y, bx, sy+g,     bw, bh) -> { closeMenu(); resetGame(); rollOffset = rawRoll; sensorFiltered = 0f; isCalibrating = false; updateAudioState() }
            isInRect(x, y, bx, sy+g*2f,  bw, bh) -> showScores = true
            isInRect(x, y, bx, sy+g*3f,  bw, bh) -> (context as? android.app.Activity)?.finish()
        }
    }

    private fun openMenu()  { isPaused = true;  showMenu = true; setupMenuButtons(); updateAudioState() }
    private fun closeMenu() { isPaused = false; showMenu = false; updateAudioState() }

    private fun setupMenuButtons() {
        menuButtons.clear()
        val W = width.toFloat(); val H = height.toFloat(); val btnW = 280f; val btnH = 65f; val startY = H / 2f - 80f; val sp = 80f
        menuButtons.add(MenuButton("CONTINUAR", W/2f-btnW/2f, startY, btnW, btnH, GameConstants.CYAN) {})
        menuButtons.add(MenuButton("REINICIAR", W/2f-btnW/2f, startY+sp, btnW, btnH, GameConstants.NEON_YELLOW) {})
        menuButtons.add(MenuButton("PONTUAÇÕES", W/2f-btnW/2f, startY+sp*2, btnW, btnH, GameConstants.MAGENTA) {})
        menuButtons.add(MenuButton("SAIR", W/2f-btnW/2f, startY+sp*3, btnW, btnH, GameConstants.NEON_RED) {})
    }

    fun setNitro(active: Boolean) {
        if (isPaused) return
        isManualNitro = active && nitroCharge > 5f
    }

    fun resetGame() {
        score = 0; currentDistance = 0f; conesDodged = 0; currentStreak = 0; longestStreak = 0
        lives = 3
        distanceSpeedMultiplier = 1.0f
        nextDistanceThreshold = GameConstants.DISTANCE_MULTIPLIER_THRESHOLD

        cones.clear(); popups.clear(); windLines.clear(); bonusStars.clear()
        nitroCharge = GameConstants.MAX_NITRO_CHARGE; isManualNitro = false; bonusNitroEndTime = 0L; confusionEndTime = 0L
        speedMultiplier = 1f; gameSpeed = GameConstants.INITIAL_GAME_SPEED; roadScroll = 0f; coneSpawnTimer = 0; flashFrames = 0
        sensorFiltered = 0f; steerInput = 0f; carLean = 0f; carScaleX = 1f; carVelX = 0f; prevCarX = 0f
        nitroIntensity = 0f; windSpawnTimer = 0
        isGameOver = false; isCalibrating = true; isPaused = false; showMenu = false; menuAnimationProgress = 0f
        if (isInitialized) carX = width / 2f - carBitmap.width / 2f
        updateAudioState()
    }

    private fun saveHiScore() { if (score > hiScore) { hiScore = score; prefs.edit().putInt("hiScore", hiScore).apply() } }

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid || !isInitialized) { Thread.sleep(8); continue }
            val canvas = holder.lockCanvas() ?: continue
            try {
                if (!isCalibrating && !isGameOver && !isPaused) updateLogic()
                val tgt = if (showMenu || showScores) 1f else 0f
                menuAlpha += (tgt - menuAlpha) * 0.18f
                drawFrame(canvas)
            } finally { holder.unlockCanvasAndPost(canvas) }
            Thread.sleep(16)
        }
    }

    private fun updateLogic() {
        val W = width.toFloat(); val H = height.toFloat()

        val laneLeft  = W * 0.05f
        val laneRight = W * 0.95f - carBitmap.width
        val center    = (laneLeft + laneRight) / 2f
        val half      = (laneRight - laneLeft) / 2f
        val targetX   = (center + steerInput * half).coerceIn(laneLeft, laneRight)
        carX += (targetX - carX) * 0.11f

        carVelX = carX - prevCarX; prevCarX = carX
        val targetLean  = (steerInput * 14f + (carVelX * 2.2f).coerceIn(-6f, 6f)).coerceIn(-18f, 18f)
        val targetScale = 1f + abs(steerInput) * 0.06f + abs(carVelX) * 0.016f
        carLean   += (targetLean  - carLean)   * 0.13f
        carScaleX += (targetScale - carScaleX) * 0.18f

        val isBonusNitroActive = System.currentTimeMillis() < bonusNitroEndTime
        val isAnyNitro = isManualNitro || isBonusNitroActive

        if (isAnyNitro) {
            speedMultiplier = 2.5f
            nitroIntensity = (nitroIntensity + 0.07f).coerceAtMost(1f)
            if (isBonusNitroActive) {
                nitroCharge = (nitroCharge + 0.20f).coerceAtMost(GameConstants.MAX_NITRO_CHARGE)
            } else {
                nitroCharge = (nitroCharge - 0.65f).coerceAtLeast(0f)
                if (nitroCharge <= 0f) isManualNitro = false
            }
        } else {
            speedMultiplier = 1f + minOf(score/400f, 0.7f)
            nitroIntensity = (nitroIntensity - 0.04f).coerceAtLeast(0f)
            nitroCharge = (nitroCharge + 0.20f).coerceAtMost(GameConstants.MAX_NITRO_CHARGE)
        }

        currentDistance += gameSpeed * speedMultiplier * distanceSpeedMultiplier * 0.08f
        if (currentDistance >= nextDistanceThreshold) {
            distanceSpeedMultiplier *= 1.2f
            nextDistanceThreshold += GameConstants.DISTANCE_MULTIPLIER_THRESHOLD
            popups.add(Popup("VELOCIDADE x1.2!", GameConstants.NEON_YELLOW, W/2f, H/2f, 60))
        }

        roadScroll += GameConstants.ROAD_SCROLL_SPEED * speedMultiplier * distanceSpeedMultiplier
        if (roadScroll > H) roadScroll -= H
        updateWindTunnel(W, H)

        score = (score + speedMultiplier).toInt()
        gameSpeed = GameConstants.INITIAL_GAME_SPEED + minOf(score / 500f, 9f)
        if (score > hiScore) hiScore = score
        if (flashFrames > 0) flashFrames--

        coneSpawnTimer--
        if (coneSpawnTimer <= 0) {
            val spawnLeft = W * 0.15f
            val spawnRight = W * 0.85f - coneBitmap.width
            val isPurple = rng.nextInt(100) < 15
            cones.add(Cone(spawnLeft + rng.nextFloat() * (spawnRight - spawnLeft), -100f, isPurple))

            val baseTime = 45 - minOf(score / 40, 35)
            coneSpawnTimer = (baseTime / distanceSpeedMultiplier).toInt().coerceAtLeast(7)
        }

        if (rng.nextInt(350) == 0) {
            val spawnLeft = W * 0.2f
            val spawnRight = W * 0.8f - starIcon.width
            bonusStars.add(BonusStar(spawnLeft + rng.nextFloat() * (spawnRight - spawnLeft), -100f))
        }

        val carCY = H * 0.8f
        carRect.set(carX + 12f, carCY + 12f, carX + carBitmap.width - 12f, carCY + carBitmap.height - 12f)

        val starIter = bonusStars.iterator()
        while (starIter.hasNext()) {
            val s = starIter.next()
            s.y += (gameSpeed + 4f) * speedMultiplier * distanceSpeedMultiplier
            if (s.y > H) { starIter.remove(); continue }
            if (!s.hit) {
                objRect.set(s.x, s.y, s.x+starIcon.width, s.y+starIcon.height)
                if (RectF.intersects(carRect, objRect)) {
                    s.hit = true
                    starIter.remove()
                    audioController?.playSoundFX("ESTRELA")
                    bonusNitroEndTime = System.currentTimeMillis() + GameConstants.BONUS_NITRO_DURATION_MS
                    popups.add(Popup("NITRO 5s!", GameConstants.CYAN, carX+carBitmap.width/2f, carCY-40f, 60))
                }
            }
        }

        val coneIter = cones.iterator()
        while (coneIter.hasNext()) {
            val c = coneIter.next()
            c.y += (gameSpeed + 4f) * speedMultiplier * distanceSpeedMultiplier

            if (!c.passed && !c.hit && c.y > carCY + carBitmap.height) {
                c.passed = true; conesDodged++; currentStreak++
                if (currentStreak > longestStreak) longestStreak = currentStreak
                val bonus = 25 + (currentStreak / 3) * 10
                score += bonus
                popups.add(Popup("+$bonus", GameConstants.NEON_GREEN, c.x + coneBitmap.width/2f, c.y - 40f, 38))
            }

            if (c.y > H + coneBitmap.height) { coneIter.remove(); continue }

            if (!c.hit) {
                objRect.set(c.x+8f, c.y+8f, c.x+coneBitmap.width-8f, c.y+coneBitmap.height-8f)
                if (RectF.intersects(carRect, objRect)) {
                    c.hit = true
                    coneIter.remove()
                    currentStreak = 0

                    if (c.isPurple) {
                        audioController?.playSoundFX("EMBATE_ROXO")
                        confusionEndTime = System.currentTimeMillis() + GameConstants.CONFUSION_DURATION_MS
                        popups.add(Popup("ALERTA: INVERTIDO", GameConstants.MAGENTA, carX+carBitmap.width/2f, carCY-20f, 50))
                    } else {
                        audioController?.playSoundFX("EMBATE_NORMAL")
                        lives--
                        flashFrames = 12
                        popups.add(Popup("DANO!", GameConstants.NEON_RED, carX+carBitmap.width/2f, carCY-20f, 40))

                        if (lives <= 0) {
                            audioController?.playSoundFX("PERDEU")
                            isGameOver = true
                            saveHiScore()
                            updateAudioState()
                            return
                        }
                    }
                }
            }
        }

        val popupIter = popups.iterator()
        while (popupIter.hasNext()) {
            val p = popupIter.next()
            p.y -= 1.8f
            p.life--
            if (p.life <= 0) popupIter.remove()
        }
    }

    private fun updateWindTunnel(W: Float, H: Float) {
        if (nitroIntensity <= 0.02f) { windLines.clear(); return }
        val ox = W/2f; val oy = 0f
        val rate = (7f - nitroIntensity*5.5f).toInt().coerceAtLeast(1)
        if (--windSpawnTimer <= rate) {
            windSpawnTimer = rate
            repeat((nitroIntensity*4f).toInt().coerceAtLeast(1)) {
                val raw = rng.nextFloat()*2f*PI.toFloat()
                val ang = atan2(sin(raw)*0.45f, cos(raw))
                val ml  = rng.nextInt(20, 38)
                windLines.add(WindLine(ox, oy, ang, rng.nextFloat()*4f+2.5f, rng.nextFloat()*55f+25f, if (rng.nextBoolean()) GameConstants.CYAN else GameConstants.MAGENTA, 0, ml, ml))
            }
        }

        val windIter = windLines.iterator()
        while (windIter.hasNext()) {
            val wl = windIter.next()
            wl.life--
            if (wl.life <= 0) { windIter.remove(); continue }
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

    // ── DRAW ──
    private fun drawFrame(canvas: Canvas) {
        val W = width.toFloat(); val H = height.toFloat()

        if (bgGifDrawable != null) {
            val gifW = bgGifDrawable!!.intrinsicWidth.toFloat()
            val gifH = bgGifDrawable!!.intrinsicHeight.toFloat()
            if (gifW > 0 && gifH > 0) {
                val scale = max(W / gifW, H / gifH)
                val dx = (W - gifW * scale) / 2f
                val dy = (H - gifH * scale) / 2f

                canvas.save()
                canvas.translate(dx, dy)
                canvas.scale(scale, scale)
                bgGifDrawable?.setBounds(0, 0, gifW.toInt(), gifH.toInt())
                bgGifDrawable?.draw(canvas)
                canvas.restore()
            }
        } else {
            canvas.drawColor(GameConstants.DARK_BG)
        }

        drawRoad(canvas, W, H)

        when {
            isGameOver    -> { drawPlayfield(canvas, W, H) }
            isCalibrating -> { }
            else          -> { drawPlayfield(canvas, W, H) }
        }

        if (isGameOver) drawGameOver(canvas, W, H)
        if (isCalibrating) drawCalibrate(canvas, W, H)
        if (!isGameOver && !isCalibrating) { drawHUD(canvas, W, H); drawMenuBtn(canvas, W) }
        if (menuAlpha > 0.01f) drawMenuOverlay(canvas, W, H)
    }

    private fun drawRoad(canvas: Canvas, W: Float, H: Float) {
        val cx = W/2f; val vy = 0f
        val topW = W * 0.15f
        val botW = W * 1.0f

        val p = Path().apply { moveTo(cx-topW/2f,vy); lineTo(cx+topW/2f,vy); lineTo(cx+botW/2f,H); lineTo(cx-botW/2f,H); close() }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(cx,vy,cx,H, Color.argb(60,8,0,40), Color.argb(200,12,0,50), Shader.TileMode.CLAMP)
        canvas.drawPath(p, paint)

        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = GameConstants.CYAN
        canvas.drawLine(cx-topW/2f,vy,cx-botW/2f,H,paint); canvas.drawLine(cx+topW/2f,vy,cx+botW/2f,H,paint)

        for (i in 0..14) {
            val t = ((i/14f) + roadScroll/H) % 1f; if (t < 0.04f) continue
            val yy = vy + t*(H-vy); val pr = yy / H
            val hw = (topW*0.1f + (botW*0.45f - topW*0.1f)*pr)*0.12f
            paint.strokeWidth = 1f+pr*4.5f; paint.color = Color.argb((80+pr*140).toInt(),255,0,255)
            canvas.drawLine(cx-hw,yy,cx+hw,yy,paint)
        }
        paint.shader = null
        paint.style = Paint.Style.FILL
    }

    private fun drawPlayfield(canvas: Canvas, W: Float, H: Float) {
        if (isGameOver || isCalibrating) return
        if (flashFrames > 0) {
            paint.color = Color.argb((flashFrames*18).coerceAtMost(160),255,40,40)
            canvas.drawRect(0f,0f,W,H,paint)
        }
        if (nitroIntensity > 0.01f) drawWindTunnelFx(canvas, W, H)

        for (i in bonusStars.indices) canvas.drawBitmap(starIcon, bonusStars[i].x, bonusStars[i].y, pixelPaint)

        for (i in cones.indices) {
            val c = cones[i]
            if (c.isPurple) {
                canvas.drawBitmap(coneBitmap, c.x, c.y, purpleConePaint)
            } else {
                canvas.drawBitmap(coneBitmap, c.x, c.y, pixelPaint)
            }
        }

        val carCY = H * 0.8f
        val cx = carX+carBitmap.width/2f; val cy = carCY+carBitmap.height/2f

        drawTyreTracks(canvas, cx, cy)

        canvas.save(); canvas.translate(cx,cy); canvas.rotate(carLean); canvas.scale(carScaleX,1f)
        if (nitroIntensity > 0f) {
            val fw = carBitmap.width*0.28f
            flamePaint.shader = LinearGradient(0f,carBitmap.height/2f-4f,0f,carBitmap.height/2f+80f, flameColors1, flameStops1, Shader.TileMode.CLAMP)
            canvas.drawRect(-fw/2f,carBitmap.height/2f-4f,fw/2f,carBitmap.height/2f+80f,flamePaint)
            val fl = abs(sin(System.currentTimeMillis()/55.0)).toFloat()
            flamePaint.shader = LinearGradient(0f,carBitmap.height/2f,0f,carBitmap.height/2f+50f+fl*22f, flameColors2, flameStops2, Shader.TileMode.CLAMP)
            val fw2 = fw*(0.45f+fl*0.35f)
            canvas.drawRect(-fw2/2f,carBitmap.height/2f,fw2/2f,carBitmap.height/2f+50f+fl*22f,flamePaint)
        }
        canvas.drawBitmap(carBitmap,-carBitmap.width/2f,-carBitmap.height/2f,pixelPaint)
        canvas.restore()

        pixelPaint.textAlign = Paint.Align.CENTER
        for (i in popups.indices) {
            val p = popups[i]
            pixelPaint.color = p.color; pixelPaint.textSize = 28f
            pixelPaint.alpha = ((p.life/40f)*255).toInt().coerceIn(0,255)
            canvas.drawText(p.text, p.x, p.y, pixelPaint)
        }
        pixelPaint.alpha = 255; pixelPaint.textAlign = Paint.Align.LEFT
    }

    private fun drawTyreTracks(canvas: Canvas, cx: Float, cy: Float) {
        val v = abs(carVelX)
        if (v < 0.4f) return

        val tc = if (carVelX > 0) GameConstants.CYAN else GameConstants.MAGENTA
        val trackAlpha = ((v - 0.4f) / 2.5f * 170f).toInt().coerceIn(0, 170)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(trackAlpha, Color.red(tc), Color.green(tc), Color.blue(tc))

        val ox = carBitmap.width * 0.30f
        val wy = cy + carBitmap.height / 2f - 8f
        val tl = (v * 20f).coerceAtMost(45f)

        canvas.drawLine(cx - ox, wy, cx - ox, wy + tl, paint)
        canvas.drawLine(cx + ox, wy, cx + ox, wy + tl, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawWindTunnelFx(canvas: Canvas, W: Float, H: Float) {
        for (i in windLines.indices) {
            val wl = windLines[i]; if (wl.alpha < 5) continue
            val tx = wl.x-cos(wl.angle)*wl.length; val ty = wl.y-sin(wl.angle)*wl.length
            linePaint.shader = LinearGradient(tx,ty,wl.x,wl.y,Color.TRANSPARENT, Color.argb(wl.alpha,Color.red(wl.color),Color.green(wl.color),Color.blue(wl.color)),Shader.TileMode.CLAMP)
            linePaint.strokeWidth = 1.5f+nitroIntensity*2.5f; canvas.drawLine(tx,ty,wl.x,wl.y,linePaint)
        }
        if (nitroIntensity > 0.25f) {
            val va = ((nitroIntensity-0.25f)/0.75f*130f).toInt()
            paint.style = Paint.Style.FILL
            paint.shader = LinearGradient(0f,H/2f,W*0.2f,H/2f,Color.argb(va,0,255,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,W*0.2f,H,paint)
            paint.shader = LinearGradient(W,H/2f,W*0.8f,H/2f,Color.argb(va,255,68,255),Color.TRANSPARENT,Shader.TileMode.CLAMP)
            canvas.drawRect(W*0.8f,0f,W,H,paint); paint.shader = null
        }
    }

    private fun drawHUD(canvas: Canvas, W: Float, H: Float) {
        hudBox(canvas, 20f, 20f, 180f, 66f)
        hudLbl(canvas,"DISTÂNCIA",38f,47f,GameConstants.NEON_GREEN)
        hudVal(canvas,"${currentDistance.toInt()} m",38f,76f,22f)

        hudBox(canvas,W/2f-100f,20f,200f,86f)
        hudLbl(canvas,"RECORDE",W/2f-82f,50f,GameConstants.NEON_YELLOW)
        hudVal(canvas, hiScore.toString(),W/2f-82f,84f)

        val kmh=(80f * speedMultiplier * distanceSpeedMultiplier + (speedMultiplier-1f)*120f).toInt()
        hudBox(canvas,W-200f,20f,180f,86f)
        hudLbl(canvas,"VELOC.",W-182f,50f,GameConstants.MAGENTA)
        hudVal(canvas,"$kmh km/h",W-182f,84f)

        pixelPaint.textSize = 24f
        pixelPaint.color = GameConstants.NEON_YELLOW
        canvas.drawText("ENERGIA:", 30f, 135f, pixelPaint)

        paint.style = Paint.Style.FILL
        paint.color = GameConstants.NEON_YELLOW
        for (i in 0 until lives) {
            val px = 145f + (i * 35f)
            canvas.drawPath(getLightningPath(px, 110f, 18f, 28f), paint)
        }

        drawNitroBar(canvas, W, H)

        pixelPaint.textAlign = Paint.Align.CENTER
        pixelPaint.textSize = 34f

        val nitroTimeLeft = bonusNitroEndTime - System.currentTimeMillis()
        if (nitroTimeLeft > 0) {
            pixelPaint.color = GameConstants.CYAN
            canvas.drawText("NITRO MÁX: ${nitroTimeLeft/1000}s", W/2f, H*0.25f, pixelPaint)
        }

        val confusionTimeLeft = confusionEndTime - System.currentTimeMillis()
        if (confusionTimeLeft > 0) {
            pixelPaint.color = GameConstants.MAGENTA
            canvas.drawText("ALERTA: INVERTIDO ${confusionTimeLeft/1000}s", W/2f, H*0.32f, pixelPaint)
        }
        pixelPaint.textAlign = Paint.Align.LEFT
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
        pixelPaint.textSize=16f; pixelPaint.color=GameConstants.MAGENTA; canvas.drawText("NITRO MANUAL",bx,by-4f,pixelPaint)
        paint.style=Paint.Style.FILL; paint.color=Color.argb(180,30,0,40); canvas.drawRect(bx,by,bx+bw,by+bh,paint)
        val fw=bw*(nitroCharge/100f)
        if (fw>0f) { paint.shader=LinearGradient(bx,by,bx+fw,by,Color.parseColor("#9900CC"),GameConstants.MAGENTA,Shader.TileMode.CLAMP); canvas.drawRect(bx,by,bx+fw,by+bh,paint); paint.shader=null }
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=GameConstants.MAGENTA; canvas.drawRect(bx,by,bx+bw,by+bh,paint); paint.style=Paint.Style.FILL
    }

    private fun drawMenuBtn(canvas: Canvas, W: Float) {
        val bx=W-78f; val by=120f; val bs=56f
        paint.style=Paint.Style.FILL; paint.color=Color.argb(180,0,0,20); canvas.drawRect(bx,by,bx+bs,by+bs,paint)
        paint.style=Paint.Style.STROKE; paint.strokeWidth=2f; paint.color=GameConstants.CYAN; canvas.drawRect(bx,by,bx+bs,by+bs,paint)
        paint.style=Paint.Style.FILL; paint.color=GameConstants.CYAN
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

        val bw=280f; val bh=62f; val bx=W/2f-bw/2f; val sy=H/2f-80f; val g=80f
        menuButtons.forEachIndexed { i, btn ->
            val by=sy+i*g; val ia=(ca*255).toInt()
            paint.color=Color.argb((ca*210).toInt(),20,0,40); canvas.drawRect(bx,by,bx+bw,by+bh,paint)
            paint.style=Paint.Style.STROKE; paint.strokeWidth=2.5f; paint.color=Color.argb(ia,Color.red(btn.color),Color.green(btn.color),Color.blue(btn.color))
            canvas.drawRect(bx,by,bx+bw,by+bh,paint); paint.style=Paint.Style.FILL
            pixelPaint.textSize=22f; pixelPaint.color=Color.argb(ia,255,255,255); canvas.drawText(btn.text,W/2f,by+bh/2f+9f,pixelPaint)
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
        pixelPaint.textSize=54f; pixelPaint.color=GameConstants.CYAN; canvas.drawText("SENSOR",W/2f,H*0.18f,pixelPaint)
        pixelPaint.color=GameConstants.MAGENTA; canvas.drawText("DRIVE",W/2f,H*0.26f,pixelPaint)
        pixelPaint.textSize=20f; pixelPaint.color=GameConstants.NEON_YELLOW; canvas.drawText("SEGURA O TELEMÓVEL VERTICAL",W/2f,H*0.38f,pixelPaint)
        pixelPaint.textSize=16f; pixelPaint.color=Color.argb(210,255,255,255)
        canvas.drawText("Inclina ESQUERDA / DIREITA para conduzir",W/2f,H*0.45f,pixelPaint)
        if (isInitialized) canvas.drawBitmap(phoneIcon,W/2f-phoneIcon.width/2f,H*0.52f,pixelPaint)
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
        pixelPaint.textSize=52f; pixelPaint.color=GameConstants.CYAN; canvas.drawText("$score",W/2f,H*0.37f,pixelPaint)
        pixelPaint.textSize=18f
        pixelPaint.color=GameConstants.NEON_GREEN;  canvas.drawText("${currentDistance.toInt()} m percorridos",W/2f,H*0.45f,pixelPaint)
        pixelPaint.color=GameConstants.NEON_YELLOW; canvas.drawText("$conesDodged cones desviados",W/2f,H*0.51f,pixelPaint)
        pixelPaint.color=GameConstants.MAGENTA;    canvas.drawText("Melhor streak: $longestStreak",W/2f,H*0.56f,pixelPaint)
        if (isInitialized) canvas.drawBitmap(starIcon,W/2f-starIcon.width/2f,H*0.60f,pixelPaint)
        pixelPaint.textSize=20f; pixelPaint.color=GameConstants.NEON_YELLOW; canvas.drawText("RECORDE: $hiScore",W/2f,H*0.70f,pixelPaint)
        val bw=260f; val bh=52f; val bx=W/2f-bw/2f
        goBtn(canvas,bx,H*0.77f,bw,bh,GameConstants.NEON_YELLOW,"TENTAR NOVAMENTE",W)
        goBtn(canvas,bx,H*0.87f,bw,bh,GameConstants.CYAN,"MENU",W)
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