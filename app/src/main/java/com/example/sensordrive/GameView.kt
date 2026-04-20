package com.example.sensordrive

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.*
import kotlin.random.Random

class GameView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), Runnable {

    // ── Thread & State ──────────────────────────────────────────────
    private var gameThread: Thread? = null
    private var isPlaying = false
    var isCalibrating = true
    var isGameOver = false

    // ── Paint ───────────────────────────────────────────────────────
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pixelPaint = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = false          // keep pixel-art crisp
        isFilterBitmap = false
    }

    // ── Assets ──────────────────────────────────────────────────────
    private fun loadBitmap(resId: Int, targetW: Int): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = BitmapFactory.decodeResource(resources, resId, opts)
        val h = (raw.height * (targetW.toFloat() / raw.width)).toInt()
        val scaled = Bitmap.createScaledBitmap(raw, targetW, h, false) // false = pixelated
        raw.recycle()
        return scaled
    }

    private val carBitmap    by lazy { loadBitmap(R.drawable.carro_traseira,   120) }
    private val coneBitmap   by lazy { loadBitmap(R.drawable.cones,             60) }
    private val redeBitmap   by lazy { loadBitmap(R.drawable.rede,             540) }
    private val phoneIcon    by lazy { loadBitmap(R.drawable.telemovel,         90) }
    private val nitroIcon    by lazy { loadBitmap(R.drawable.nitro,             80) }
    private val starIcon     by lazy { loadBitmap(R.drawable.estrela,           44) }
    private val flagIcon     by lazy { loadBitmap(R.drawable.bandeira,          80) }

    // ── Game State ──────────────────────────────────────────────────
    private var carX = 0f
    private var rotationZ = 0f
    private var score = 0
    private var hiScore = 0
    private var speedMultiplier = 1.0f
    private var nitroCharge = 100f      // 0‥100
    private var isNitroActive = false
    private var gameSpeed = 7f
    private val rng = Random.Default

    // Cones
    private data class Cone(var x: Float, var y: Float, var hit: Boolean = false)
    private val cones = mutableListOf<Cone>()
    private var coneSpawnTimer = 0

    // Road scroll
    private var roadScroll = 0f

    // Flash effect on hit
    private var flashFrames = 0

    // Stars background
    private data class Star(val x: Float, val y: Float, val r: Float, val alpha: Float)
    private val stars = mutableListOf<Star>()

    // Floating score pop-ups
    private data class Popup(val text: String, val color: Int, var x: Float, var y: Float, var life: Int)
    private val popups = mutableListOf<Popup>()

    // Neon colors
    private val cyan      = Color.parseColor("#00FFFF")
    private val magenta   = Color.parseColor("#FF44FF")
    private val neonYellow= Color.parseColor("#FFEE00")
    private val neonRed   = Color.parseColor("#FF3333")
    private val darkBg    = Color.parseColor("#07001A")
    private val roadColor = Color.parseColor("#0D0030")

    // ── Sensor input (low-pass filtered) ────────────────────────────
    // rotationZ é o valor cru calibrado vindo do MainActivity
    // smoothedZ é o valor filtrado usado pelo jogo
    private var smoothedZ = 0f

    fun updateRotation(z: Float) {
        if (isGameOver || isCalibrating) return
        // Low-pass filter: só deixa passar mudanças lentas, elimina tremido
        // Alpha baixo = muito suave; 0.08 é ideal para inclinações lentas
        val alpha = 0.08f
        rotationZ = rotationZ + alpha * (z - rotationZ)
    }

    fun setNitro(active: Boolean) {
        isNitroActive = active && nitroCharge > 5f
        speedMultiplier = if (isNitroActive) 2.4f else 1.0f + minOf(score / 300f, 0.8f)
    }

    fun resetGame() {
        score = 0
        cones.clear()
        popups.clear()
        nitroCharge = 100f
        isNitroActive = false
        speedMultiplier = 1.0f
        gameSpeed = 7f
        roadScroll = 0f
        coneSpawnTimer = 0
        flashFrames = 0
        rotationZ = 0f
        isGameOver = false
        isCalibrating = true
    }

    // ── Thread Loop ─────────────────────────────────────────────────
    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) { Thread.sleep(8); continue }
            val canvas = holder.lockCanvas() ?: continue
            try {
                if (!isCalibrating && !isGameOver) updateLogic()
                drawFrame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Thread.sleep(16)
        }
    }

    // ── Logic ────────────────────────────────────────────────────────
    private fun updateLogic() {
        val W = width.toFloat()
        val H = height.toFloat()

        // Car steering — landscape mode, pequena inclinação = toda a largura da estrada
        // Deadzone: ignora tremido abaixo de 0.015 rad
        val deadzone = 0.015f
        val input = when {
            rotationZ > deadzone  ->  rotationZ - deadzone
            rotationZ < -deadzone -> rotationZ + deadzone
            else -> 0f
        }
        // sensitivity: ~1800 cobre a pista com ~20° de inclinação lateral
        val sensitivity = 1800f
        val laneLeft  = W * 0.18f
        val laneRight = W * 0.82f - carBitmap.width
        val center    = (laneLeft + laneRight) / 2f
        val target    = (center + input * sensitivity).coerceIn(laneLeft, laneRight)
        // Smoothing 0.10 = suave mas responsivo
        carX += (target - carX) * 0.10f

        // Road scroll
        roadScroll += 18f * speedMultiplier
        if (roadScroll > H) roadScroll -= H

        // Nitro charge/drain
        if (isNitroActive) {
            nitroCharge = (nitroCharge - 0.7f).coerceAtLeast(0f)
            if (nitroCharge == 0f) { isNitroActive = false; speedMultiplier = 1.0f }
        } else {
            nitroCharge = (nitroCharge + 0.22f).coerceAtMost(100f)
        }

        // Score & speed ramp
        score++
        gameSpeed = 7f + minOf(score / 400f, 8f)
        if (score > hiScore) hiScore = score

        // Flash timer
        if (flashFrames > 0) flashFrames--

        // Cone spawn
        coneSpawnTimer--
        if (coneSpawnTimer <= 0) {
            val laneW = W * 0.62f
            val laneLeft = (W - laneW) / 2f
            val x = laneLeft + rng.nextFloat() * (laneW - coneBitmap.width)
            cones.add(Cone(x, -coneBitmap.height.toFloat()))
            coneSpawnTimer = (80 - minOf(score / 60, 50)).coerceAtLeast(28)
        }

        // Move cones & collision
        val carRect = RectF(carX + 10f, H - 350f + 20f,
            carX + carBitmap.width - 10f, H - 350f + carBitmap.height - 10f)
        val iter = cones.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            c.y += (gameSpeed + 4f) * speedMultiplier
            if (c.y > H + coneBitmap.height) { iter.remove(); continue }

            if (!c.hit) {
                val cRect = RectF(c.x + 8f, c.y + 8f,
                    c.x + coneBitmap.width - 8f, c.y + coneBitmap.height - 8f)
                if (RectF.intersects(carRect, cRect)) {
                    c.hit = true
                    iter.remove()
                    if (score < 60) {
                        isGameOver = true
                        return
                    }
                    val penalty = 60
                    score -= penalty
                    flashFrames = 10
                    popups.add(Popup("-$penalty", neonRed, carX + carBitmap.width / 2f, H - 380f, 40))
                }
            }
        }

        // Popups
        popups.removeAll { it.life <= 0 }
        popups.forEach { it.y -= 2f; it.life-- }
    }

    // ── Drawing ──────────────────────────────────────────────────────
    private fun drawFrame(canvas: Canvas) {
        val W = width.toFloat()
        val H = height.toFloat()

        // Background
        canvas.drawColor(darkBg)
        drawStarfield(canvas, W, H)
        drawRedeBackground(canvas, W, H)
        drawRoad(canvas, W, H)

        when {
            isGameOver      -> { drawPlayfield(canvas, W, H); drawGameOver(canvas, W, H) }
            isCalibrating   -> drawCalibrate(canvas, W, H)
            else            -> { drawPlayfield(canvas, W, H); drawHUD(canvas, W, H) }
        }
    }

    // ── Background layers ────────────────────────────────────────────
    private fun drawStarfield(canvas: Canvas, W: Float, H: Float) {
        if (stars.size < 100) {
            stars.clear()
            repeat(110) {
                stars.add(Star(rng.nextFloat() * W, rng.nextFloat() * H,
                    rng.nextFloat() * 2f + 0.5f, rng.nextFloat() * 0.7f + 0.3f))
            }
        }
        paint.style = Paint.Style.FILL
        for (s in stars) {
            paint.color = Color.argb((s.alpha * 200).toInt(), 255, 255, 255)
            canvas.drawCircle(s.x, s.y, s.r, paint)
        }
    }

    private fun drawRedeBackground(canvas: Canvas, W: Float, H: Float) {
        // Draw rede (net/grid) centered, semi-transparent
        val rx = W / 2f - redeBitmap.width / 2f
        val ry = H / 2f - redeBitmap.height / 2f
        paint.alpha = 55
        canvas.drawBitmap(redeBitmap, rx, ry, paint)
        paint.alpha = 255
    }

    private fun drawRoad(canvas: Canvas, W: Float, H: Float) {
        val cx = W / 2f
        val vanishY = H * 0.38f
        val laneW = W * 0.65f
        val roadLeft = cx - laneW / 2f
        val roadRight = cx + laneW / 2f

        // Road trapezoid fill
        val roadPath = Path().apply {
            moveTo(cx - laneW * 0.1f, vanishY)
            lineTo(cx + laneW * 0.1f, vanishY)
            lineTo(roadRight, H)
            lineTo(roadLeft, H)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(cx, vanishY, cx, H,
            Color.argb(60, 8, 0, 40), Color.argb(200, 12, 0, 50), Shader.TileMode.CLAMP)
        canvas.drawPath(roadPath, paint)
        paint.shader = null

        // Edge lines — neon cyan glow
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = cyan
        paint.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.SOLID)
        canvas.drawLine(cx - laneW * 0.1f, vanishY, roadLeft, H, paint)
        canvas.drawLine(cx + laneW * 0.1f, vanishY, roadRight, H, paint)
        paint.maskFilter = null

        // Dashed center lines scrolling
        val numLines = 14
        paint.strokeWidth = 4f
        paint.color = Color.argb(160, 255, 0, 255)
        for (i in 0..numLines) {
            val t = ((i.toFloat() / numLines) + roadScroll / H) % 1f
            if (t < 0.05f) continue
            val yy = vanishY + t * (H - vanishY)
            val prog = (yy - vanishY) / (H - vanishY)
            val hw = (laneW * 0.06f + (laneW * 0.42f - laneW * 0.06f) * prog) * 0.12f
            paint.strokeWidth = (1f + prog * 3.5f)
            paint.alpha = (80 + prog * 140).toInt()
            canvas.drawLine(cx - hw, yy, cx + hw, yy, paint)
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    // ── Playfield ────────────────────────────────────────────────────
    private fun drawPlayfield(canvas: Canvas, W: Float, H: Float) {
        if (isGameOver || isCalibrating) return

        // Flash overlay on collision
        if (flashFrames > 0) {
            paint.color = Color.argb((flashFrames * 18).coerceAtMost(160), 255, 40, 40)
            canvas.drawRect(0f, 0f, W, H, paint)
        }

        // Cones
        for (c in cones) {
            canvas.drawBitmap(coneBitmap, c.x, c.y, pixelPaint)
        }

        // Car with tilt
        canvas.save()
        val carCX = carX + carBitmap.width / 2f
        val carCY = H - 350f + carBitmap.height / 2f
        canvas.translate(carCX, carCY)
        canvas.rotate(rotationZ * 8f)

        // Nitro flame
        if (isNitroActive) {
            paint.style = Paint.Style.FILL
            val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            flamePaint.shader = LinearGradient(
                0f, 0f, 0f, 60f,
                intArrayOf(magenta, Color.argb(180, 255, 80, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            val flameW = carBitmap.width * 0.35f
            canvas.drawRect(-flameW / 2f, carBitmap.height / 2f - 4f,
                flameW / 2f, carBitmap.height / 2f + 65f, flamePaint)
            flamePaint.shader = null
        }

        canvas.drawBitmap(carBitmap, -carBitmap.width / 2f, -carBitmap.height / 2f, pixelPaint)
        canvas.restore()

        // Floating popups
        for (p in popups) {
            pixelPaint.color = p.color
            pixelPaint.textSize = 30f
            pixelPaint.alpha = ((p.life / 40f) * 255).toInt().coerceIn(0, 255)
            canvas.drawText(p.text, p.x - 30f, p.y, pixelPaint)
        }
        pixelPaint.alpha = 255
    }

    // ── HUD ──────────────────────────────────────────────────────────
    private fun drawHUD(canvas: Canvas, W: Float, H: Float) {
        // Score box — top left
        drawHudBox(canvas, 20f, 20f, 200f, 90f)
        pixelPaint.textSize = 18f
        pixelPaint.color = cyan
        canvas.drawText("PONTOS", 40f, 52f, pixelPaint)
        pixelPaint.textSize = 28f
        pixelPaint.color = Color.WHITE
        canvas.drawText("$score", 40f, 88f, pixelPaint)

        // Hi-score box — top center
        drawHudBox(canvas, W / 2f - 110f, 20f, 220f, 90f)
        pixelPaint.textSize = 18f
        pixelPaint.color = neonYellow
        canvas.drawText("RECORDE", W / 2f - 90f, 52f, pixelPaint)
        pixelPaint.textSize = 28f
        pixelPaint.color = Color.WHITE
        canvas.drawText("$hiScore", W / 2f - 90f, 88f, pixelPaint)

        // Speed box — top right
        val kmh = (80f * speedMultiplier + (speedMultiplier - 1f) * 120f).toInt()
        drawHudBox(canvas, W - 220f, 20f, 200f, 90f)
        pixelPaint.textSize = 18f
        pixelPaint.color = magenta
        canvas.drawText("VELOC.", W - 200f, 52f, pixelPaint)
        pixelPaint.textSize = 28f
        pixelPaint.color = Color.WHITE
        canvas.drawText("$kmh km/h", W - 200f, 88f, pixelPaint)

        // Nitro bar — bottom center
        drawNitroBar(canvas, W, H)
    }

    private fun drawHudBox(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 0, 0, 20)
        canvas.drawRect(x, y, x + w, y + h, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = cyan
        canvas.drawRect(x, y, x + w, y + h, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawNitroBar(canvas: Canvas, W: Float, H: Float) {
        val barW = 280f
        val barH = 26f
        val bx = W / 2f - barW / 2f
        val by = H - 90f

        // Icon
        canvas.drawBitmap(nitroIcon, bx - nitroIcon.width - 10f, by - 20f, pixelPaint)

        // Label
        pixelPaint.textSize = 18f
        pixelPaint.color = magenta
        canvas.drawText("NITRO", bx, by - 4f, pixelPaint)

        // Track
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 30, 0, 40)
        canvas.drawRect(bx, by, bx + barW, by + barH, paint)

        // Fill
        val fillW = barW * (nitroCharge / 100f)
        if (fillW > 0f) {
            paint.shader = LinearGradient(bx, by, bx + fillW, by,
                Color.parseColor("#9900CC"), magenta, Shader.TileMode.CLAMP)
            canvas.drawRect(bx, by, bx + fillW, by + barH, paint)
            paint.shader = null
        }

        // Border
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = magenta
        canvas.drawRect(bx, by, bx + barW, by + barH, paint)
        paint.style = Paint.Style.FILL
    }

    // ── Calibration Screen ──────────────────────────────────────────
    private fun drawCalibrate(canvas: Canvas, W: Float, H: Float) {
        // Semi overlay
        paint.color = Color.argb(200, 0, 0, 15)
        canvas.drawRect(0f, 0f, W, H, paint)

        // Title
        pixelPaint.textSize = 52f
        pixelPaint.color = cyan
        pixelPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("SENSOR", W / 2f, H / 2f - 260f, pixelPaint)
        pixelPaint.color = magenta
        canvas.drawText("DRIVE", W / 2f, H / 2f - 200f, pixelPaint)

        // Instruction
        pixelPaint.textSize = 20f
        pixelPaint.color = neonYellow
        canvas.drawText("SEGURE O TELEFONE", W / 2f, H / 2f - 130f, pixelPaint)
        canvas.drawText("NA POSIÇÃO DE CONDUZIR", W / 2f, H / 2f - 100f, pixelPaint)

        // Phone icon
        canvas.drawBitmap(phoneIcon, W / 2f - phoneIcon.width / 2f, H / 2f - 70f, pixelPaint)

        // Tap prompt (blinking)
        val blink = (System.currentTimeMillis() / 600) % 2 == 0L
        if (blink) {
            pixelPaint.textSize = 22f
            pixelPaint.color = Color.WHITE
            canvas.drawText("TOQUE PARA INICIAR", W / 2f, H / 2f + 60f, pixelPaint)
        }

        pixelPaint.textAlign = Paint.Align.LEFT
    }

    // ── Game Over Screen ─────────────────────────────────────────────
    private fun drawGameOver(canvas: Canvas, W: Float, H: Float) {
        // Dark overlay
        paint.color = Color.argb(210, 0, 0, 10)
        canvas.drawRect(0f, 0f, W, H, paint)

        pixelPaint.textAlign = Paint.Align.CENTER

        // GAME OVER — pulsing red
        val pulse = (sin(System.currentTimeMillis() / 300.0) * 0.3f + 0.7f).toFloat()
        pixelPaint.textSize = 64f
        pixelPaint.color = Color.argb((255 * pulse).toInt(), 255, 40, 40)
        canvas.drawText("GAME OVER", W / 2f, H / 2f - 120f, pixelPaint)

        // Final score
        pixelPaint.textSize = 32f
        pixelPaint.color = Color.WHITE
        canvas.drawText("PONTUAÇÃO FINAL", W / 2f, H / 2f - 40f, pixelPaint)
        pixelPaint.textSize = 52f
        pixelPaint.color = cyan
        canvas.drawText("$score", W / 2f, H / 2f + 30f, pixelPaint)

        // Hi-score row
        canvas.drawBitmap(starIcon, W / 2f - starIcon.width / 2f, H / 2f + 60f, pixelPaint)
        pixelPaint.textSize = 22f
        pixelPaint.color = neonYellow
        canvas.drawText("RECORDE: $hiScore", W / 2f, H / 2f + 130f, pixelPaint)

        // Retry prompt — blinking
        val blink = (System.currentTimeMillis() / 700) % 2 == 0L
        if (blink) {
            pixelPaint.textSize = 24f
            pixelPaint.color = Color.WHITE
            canvas.drawText("TOQUE PARA TENTAR DE NOVO", W / 2f, H / 2f + 200f, pixelPaint)
        }

        // Flag icon
        canvas.drawBitmap(flagIcon, W / 2f - flagIcon.width / 2f, H / 2f + 230f, pixelPaint)

        pixelPaint.textAlign = Paint.Align.LEFT
    }

    // ── Surface Thread ───────────────────────────────────────────────
    fun resume() {
        isPlaying = true
        gameThread = Thread(this)
        gameThread?.start()
    }

    fun pause() {
        isPlaying = false
        try { gameThread?.join(200) } catch (_: Exception) {}
    }
}