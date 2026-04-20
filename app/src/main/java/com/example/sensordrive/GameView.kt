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
        isAntiAlias = false
        isFilterBitmap = false
    }

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

    // ── Game State ──────────────────────────────────────────────────
    private var carX = 0f
    private var rotationZ = 0f
    private var score = 0
    private var hiScore = 0
    private var speedMultiplier = 1.0f
    private var nitroCharge = 100f
    private var isNitroActive = false
    private var gameSpeed = 7f
    private val rng = Random.Default

    // ── Cones ───────────────────────────────────────────────────────
    private data class Cone(var x: Float, var y: Float, var hit: Boolean = false)
    private val cones = mutableListOf<Cone>()
    private var coneSpawnTimer = 0

    // ── Road scroll ─────────────────────────────────────────────────
    private var roadScroll = 0f

    // ── Collision flash ─────────────────────────────────────────────
    private var flashFrames = 0

    // ── Stars ────────────────────────────────────────────────────────
    private data class Star(val x: Float, val y: Float, val r: Float, val alpha: Float)
    private val stars = mutableListOf<Star>()

    // ── Score popups ─────────────────────────────────────────────────
    private data class Popup(val text: String, val color: Int, var x: Float, var y: Float, var life: Int)
    private val popups = mutableListOf<Popup>()

    // ── Car turn animation ───────────────────────────────────────────
    // carLean: ângulo visual suavizado (graus). Positivo = inclinação à direita
    private var carLean = 0f
    // carScaleX: esticamento lateral do sprite ao virar (perspetiva fake)
    private var carScaleX = 1f
    // velocidade instantânea X do carro em pixels/frame
    private var carVelX = 0f
    private var prevCarX = 0f

    // ── Wind tunnel (nitro) ──────────────────────────────────────────
    private data class WindLine(
        var x: Float,
        var y: Float,
        val angle: Float,       // ângulo em radianos
        var speed: Float,
        val length: Float,
        val color: Int,
        var alpha: Int,
        var life: Int,
        val maxLife: Int
    )
    private val windLines = mutableListOf<WindLine>()
    private var windSpawnTimer = 0
    // Intensidade do efeito: sobe ao ativar nitro, desce ao parar
    private var nitroIntensity = 0f

    // ── Neon palette ─────────────────────────────────────────────────
    private val cyan       = Color.parseColor("#00FFFF")
    private val magenta    = Color.parseColor("#FF44FF")
    private val neonYellow = Color.parseColor("#FFEE00")
    private val neonRed    = Color.parseColor("#FF3333")
    private val darkBg     = Color.parseColor("#07001A")

    // ────────────────────────────────────────────────────────────────
    // Sensor (low-pass filtered)
    // ────────────────────────────────────────────────────────────────
    fun updateRotation(z: Float) {
        if (isGameOver || isCalibrating) return
        val alpha = 0.08f
        rotationZ = rotationZ + alpha * (z - rotationZ)
    }

    fun setNitro(active: Boolean) {
        isNitroActive = active && nitroCharge > 5f
        speedMultiplier = if (isNitroActive) 2.4f else 1.0f + minOf(score / 300f, 0.8f)
    }

    fun resetGame() {
        score = 0; cones.clear(); popups.clear(); windLines.clear()
        nitroCharge = 100f; isNitroActive = false; speedMultiplier = 1.0f
        gameSpeed = 7f; roadScroll = 0f; coneSpawnTimer = 0; flashFrames = 0
        rotationZ = 0f; carLean = 0f; carScaleX = 1f; carVelX = 0f; prevCarX = 0f
        nitroIntensity = 0f; windSpawnTimer = 0
        isGameOver = false; isCalibrating = true
    }

    // ────────────────────────────────────────────────────────────────
    // Thread loop
    // ────────────────────────────────────────────────────────────────
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

    // ────────────────────────────────────────────────────────────────
    // Logic
    // ────────────────────────────────────────────────────────────────
    private fun updateLogic() {
        val W = width.toFloat()
        val H = height.toFloat()

        // ── Steering ─────────────────────────────────────────────────
        val deadzone = 0.015f
        val input = when {
            rotationZ >  deadzone ->  rotationZ - deadzone
            rotationZ < -deadzone ->  rotationZ + deadzone
            else -> 0f
        }
        val sensitivity = 1800f
        val laneLeft  = W * 0.18f
        val laneRight = W * 0.82f - carBitmap.width
        val center    = (laneLeft + laneRight) / 2f
        val target    = (center + input * sensitivity).coerceIn(laneLeft, laneRight)
        carX += (target - carX) * 0.10f

        // ── Car turn animation ────────────────────────────────────────
        carVelX = carX - prevCarX
        prevCarX = carX

        // Lean: velocidade X → ângulo visual (máx ±14°), suavizado
        val targetLean = (carVelX * 2.8f).coerceIn(-14f, 14f)
        carLean += (targetLean - carLean) * 0.12f

        // ScaleX: esticamento lateral — lado exterior parece maior
        val targetScaleX = 1f + abs(carVelX) * 0.015f
        carScaleX += (targetScaleX - carScaleX) * 0.16f

        // ── Road scroll ───────────────────────────────────────────────
        roadScroll += 18f * speedMultiplier
        if (roadScroll > H) roadScroll -= H

        // ── Nitro ─────────────────────────────────────────────────────
        if (isNitroActive) {
            nitroCharge = (nitroCharge - 0.7f).coerceAtLeast(0f)
            if (nitroCharge == 0f) { isNitroActive = false; speedMultiplier = 1.0f }
            nitroIntensity = (nitroIntensity + 0.07f).coerceAtMost(1f)
        } else {
            nitroCharge = (nitroCharge + 0.22f).coerceAtMost(100f)
            nitroIntensity = (nitroIntensity - 0.04f).coerceAtLeast(0f)
        }

        // ── Wind tunnel update ────────────────────────────────────────
        updateWindTunnel(W, H)

        // ── Score & speed ramp ────────────────────────────────────────
        score++
        gameSpeed = 7f + minOf(score / 400f, 8f)
        if (score > hiScore) hiScore = score
        if (flashFrames > 0) flashFrames--

        // ── Cone spawn ────────────────────────────────────────────────
        coneSpawnTimer--
        if (coneSpawnTimer <= 0) {
            val laneW = W * 0.62f
            val lx = (W - laneW) / 2f
            cones.add(Cone(lx + rng.nextFloat() * (laneW - coneBitmap.width), -coneBitmap.height.toFloat()))
            coneSpawnTimer = (80 - minOf(score / 60, 50)).coerceAtLeast(28)
        }

        // ── Cones move & collision ────────────────────────────────────
        val carRect = RectF(carX + 10f, H - 350f + 20f,
            carX + carBitmap.width - 10f, H - 350f + carBitmap.height - 10f)
        val iter = cones.iterator()
        while (iter.hasNext()) {
            val c = iter.next()
            c.y += (gameSpeed + 4f) * speedMultiplier
            if (c.y > H + coneBitmap.height) { iter.remove(); continue }
            if (!c.hit) {
                val cRect = RectF(c.x + 8f, c.y + 8f, c.x + coneBitmap.width - 8f, c.y + coneBitmap.height - 8f)
                if (RectF.intersects(carRect, cRect)) {
                    c.hit = true; iter.remove()
                    if (score < 60) { isGameOver = true; return }
                    score -= 60; flashFrames = 10
                    popups.add(Popup("-60", neonRed, carX + carBitmap.width / 2f, H - 380f, 40))
                }
            }
        }

        popups.removeAll { it.life <= 0 }
        popups.forEach { it.y -= 2f; it.life-- }
    }

    // ────────────────────────────────────────────────────────────────
    // Wind tunnel logic
    // ────────────────────────────────────────────────────────────────
    private fun updateWindTunnel(W: Float, H: Float) {
        if (nitroIntensity <= 0.02f) { windLines.clear(); return }

        // Ponto de origem: ponto de fuga da estrada (perspetiva)
        val originX = W / 2f
        val originY = H * 0.40f

        // Taxa de spawn baseada na intensidade
        windSpawnTimer--
        val rate = (7f - nitroIntensity * 5.5f).toInt().coerceAtLeast(1)
        if (windSpawnTimer <= rate) {
            windSpawnTimer = rate
            val burst = (nitroIntensity * 4f).toInt().coerceAtLeast(1)
            repeat(burst) { spawnWindLine(originX, originY) }
        }

        // Move e envelhece linhas
        val wIter = windLines.iterator()
        while (wIter.hasNext()) {
            val wl = wIter.next()
            wl.life--
            if (wl.life <= 0) { wIter.remove(); continue }

            // Acelera conforme afasta do centro (perspetiva)
            wl.speed = (wl.speed * 1.055f).coerceAtMost(60f)
            wl.x += cos(wl.angle) * wl.speed
            wl.y += sin(wl.angle) * wl.speed

            // Alpha: fade-in rápido, fade-out no fim
            val lifeRatio = wl.life.toFloat() / wl.maxLife
            wl.alpha = when {
                lifeRatio > 0.8f  -> ((1f - lifeRatio) / 0.2f * 210f * nitroIntensity).toInt()
                lifeRatio < 0.22f -> (lifeRatio / 0.22f * 210f * nitroIntensity).toInt()
                else              -> (210f * nitroIntensity).toInt()
            }.coerceIn(0, 255)
        }
    }

    private fun spawnWindLine(originX: Float, originY: Float) {
        // Ângulos biased para o plano horizontal: as linhas abrem para os lados
        // como raios de uma explosão radial vista em perspetiva
        val raw = rng.nextFloat() * 2f * PI.toFloat()

        // Comprime verticalmente os ângulos: transforma um círculo numa elipse horizontal
        // para simular a perspetiva do túnel
        val bx = cos(raw)
        val by = sin(raw) * 0.45f          // eixo Y comprimido a 45%
        val biased = atan2(by, bx)

        val isCyan = rng.nextBoolean()
        val maxLife = rng.nextInt(20, 38)

        windLines.add(WindLine(
            x = originX, y = originY,
            angle = biased,
            speed = rng.nextFloat() * 4f + 2.5f,
            length = rng.nextFloat() * 55f + 25f,
            color = if (isCyan) cyan else magenta,
            alpha = 0,
            life = maxLife,
            maxLife = maxLife
        ))
    }

    // ────────────────────────────────────────────────────────────────
    // Draw
    // ────────────────────────────────────────────────────────────────
    private fun drawFrame(canvas: Canvas) {
        val W = width.toFloat()
        val H = height.toFloat()
        canvas.drawColor(darkBg)
        drawStarfield(canvas, W, H)
        drawRedeBackground(canvas, W, H)
        drawRoad(canvas, W, H)

        when {
            isGameOver    -> { drawPlayfield(canvas, W, H); drawGameOver(canvas, W, H) }
            isCalibrating -> drawCalibrate(canvas, W, H)
            else          -> { drawPlayfield(canvas, W, H); drawHUD(canvas, W, H) }
        }
    }

    // ── Starfield ────────────────────────────────────────────────────
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
        paint.alpha = 55
        canvas.drawBitmap(redeBitmap, W / 2f - redeBitmap.width / 2f, H / 2f - redeBitmap.height / 2f, paint)
        paint.alpha = 255
    }

    // ── Road ─────────────────────────────────────────────────────────
    private fun drawRoad(canvas: Canvas, W: Float, H: Float) {
        val cx = W / 2f
        val vanishY = H * 0.38f
        val laneW = W * 0.65f

        val roadPath = Path().apply {
            moveTo(cx - laneW * 0.1f, vanishY); lineTo(cx + laneW * 0.1f, vanishY)
            lineTo(cx + laneW / 2f, H); lineTo(cx - laneW / 2f, H); close()
        }
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(cx, vanishY, cx, H,
            Color.argb(60, 8, 0, 40), Color.argb(200, 12, 0, 50), Shader.TileMode.CLAMP)
        canvas.drawPath(roadPath, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = cyan
        paint.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.SOLID)
        canvas.drawLine(cx - laneW * 0.1f, vanishY, cx - laneW / 2f, H, paint)
        canvas.drawLine(cx + laneW * 0.1f, vanishY, cx + laneW / 2f, H, paint)
        paint.maskFilter = null

        for (i in 0..14) {
            val t = ((i.toFloat() / 14f) + roadScroll / H) % 1f
            if (t < 0.04f) continue
            val yy = vanishY + t * (H - vanishY)
            val prog = (yy - vanishY) / (H - vanishY)
            val hw = (laneW * 0.06f + (laneW * 0.42f - laneW * 0.06f) * prog) * 0.12f
            paint.strokeWidth = 1f + prog * 3.5f
            paint.color = Color.argb((80 + prog * 140).toInt(), 255, 0, 255)
            canvas.drawLine(cx - hw, yy, cx + hw, yy, paint)
        }
        paint.style = Paint.Style.FILL
    }

    // ── Playfield ────────────────────────────────────────────────────
    private fun drawPlayfield(canvas: Canvas, W: Float, H: Float) {
        if (isGameOver || isCalibrating) return

        // Collision flash
        if (flashFrames > 0) {
            paint.color = Color.argb((flashFrames * 18).coerceAtMost(160), 255, 40, 40)
            canvas.drawRect(0f, 0f, W, H, paint)
        }

        // Wind tunnel (desenhado antes do carro para ficar por baixo)
        if (nitroIntensity > 0.01f) drawWindTunnel(canvas, W, H)

        // Cones
        for (c in cones) canvas.drawBitmap(coneBitmap, c.x, c.y, pixelPaint)

        // ── Car ───────────────────────────────────────────────────────
        val carCX = carX + carBitmap.width / 2f
        val carCY = H - 350f + carBitmap.height / 2f

        // Tyre tracks atrás do carro (desenhados antes do save do canvas do carro)
        drawTyreTracks(canvas, carCX, carCY)

        canvas.save()
        canvas.translate(carCX, carCY)

        // 1. Lean: rotação do corpo proporcional à velocidade lateral
        canvas.rotate(carLean)

        // 2. Squash & stretch: o lado exterior parece mais largo ao virar
        canvas.scale(carScaleX, 1f)

        // Nitro exhaust flame
        if (isNitroActive) {
            val flamePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            // Chama principal estática
            flamePaint.shader = LinearGradient(
                0f, carBitmap.height / 2f - 4f,
                0f, carBitmap.height / 2f + 80f,
                intArrayOf(Color.WHITE, magenta, Color.argb(130, 150, 0, 220), Color.TRANSPARENT),
                floatArrayOf(0f, 0.2f, 0.6f, 1f),
                Shader.TileMode.CLAMP
            )
            val flameW = carBitmap.width * 0.28f
            canvas.drawRect(-flameW / 2f, carBitmap.height / 2f - 4f,
                flameW / 2f, carBitmap.height / 2f + 80f, flamePaint)

            // Chama secundária com flickering baseado no tempo
            val flicker = abs(sin(System.currentTimeMillis() / 55.0)).toFloat()
            flamePaint.shader = LinearGradient(
                0f, carBitmap.height / 2f,
                0f, carBitmap.height / 2f + 50f + flicker * 22f,
                intArrayOf(Color.argb(210, 255, 100, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            val fw2 = flameW * (0.45f + flicker * 0.35f)
            canvas.drawRect(-fw2 / 2f, carBitmap.height / 2f,
                fw2 / 2f, carBitmap.height / 2f + 50f + flicker * 22f, flamePaint)
        }

        canvas.drawBitmap(carBitmap, -carBitmap.width / 2f, -carBitmap.height / 2f, pixelPaint)
        canvas.restore()

        // Popups
        for (p in popups) {
            pixelPaint.color = p.color; pixelPaint.textSize = 30f
            pixelPaint.alpha = ((p.life / 40f) * 255).toInt().coerceIn(0, 255)
            canvas.drawText(p.text, p.x - 30f, p.y, pixelPaint)
        }
        pixelPaint.alpha = 255
    }

    // ── Tyre tracks ───────────────────────────────────────────────────
    private fun drawTyreTracks(canvas: Canvas, carCX: Float, carCY: Float) {
        val velMag = abs(carVelX)
        if (velMag < 0.4f) return

        val alpha = ((velMag - 0.4f) / 2.5f * 170f).toInt().coerceIn(0, 170)
        val trackColor = if (carVelX > 0) cyan else magenta

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Color.argb(alpha, Color.red(trackColor), Color.green(trackColor), Color.blue(trackColor))
        paint.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL)

        val wheelOffX = carBitmap.width * 0.30f
        val wheelY = carCY + carBitmap.height / 2f - 8f
        val trackLen = (velMag * 20f).coerceAtMost(45f)

        canvas.drawLine(carCX - wheelOffX, wheelY, carCX - wheelOffX, wheelY + trackLen, paint)
        canvas.drawLine(carCX + wheelOffX, wheelY, carCX + wheelOffX, wheelY + trackLen, paint)

        paint.maskFilter = null
        paint.style = Paint.Style.FILL
    }

    // ── Wind tunnel draw ──────────────────────────────────────────────
    private fun drawWindTunnel(canvas: Canvas, W: Float, H: Float) {
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
        }

        for (wl in windLines) {
            if (wl.alpha < 5) continue

            // Cauda da linha: ponto de onde veio
            val tailX = wl.x - cos(wl.angle) * wl.length
            val tailY = wl.y - sin(wl.angle) * wl.length

            // Gradiente: transparente na cauda, cor sólida na ponta
            linePaint.shader = LinearGradient(
                tailX, tailY, wl.x, wl.y,
                Color.TRANSPARENT,
                Color.argb(wl.alpha, Color.red(wl.color), Color.green(wl.color), Color.blue(wl.color)),
                Shader.TileMode.CLAMP
            )
            linePaint.strokeWidth = 1.5f + nitroIntensity * 2.5f
            canvas.drawLine(tailX, tailY, wl.x, wl.y, linePaint)
        }

        // Vignette neon nas bordas: intensifica a sensação de velocidade
        if (nitroIntensity > 0.25f) {
            val vigAlpha = ((nitroIntensity - 0.25f) / 0.75f * 130f).toInt()

            paint.style = Paint.Style.FILL
            // Borda esquerda: cyan
            paint.shader = LinearGradient(0f, H / 2f, W * 0.2f, H / 2f,
                Color.argb(vigAlpha, 0, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, W * 0.2f, H, paint)
            // Borda direita: magenta
            paint.shader = LinearGradient(W, H / 2f, W * 0.8f, H / 2f,
                Color.argb(vigAlpha, 255, 68, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(W * 0.8f, 0f, W, H, paint)
            paint.shader = null

            // Aberração cromática nas bordas quando intensidade máxima
            if (nitroIntensity > 0.65f) {
                val aberAlpha = ((nitroIntensity - 0.65f) / 0.35f * 40f).toInt()
                paint.color = Color.argb(aberAlpha, 255, 68, 255)
                canvas.drawRect(0f, 0f, W * 0.06f, H, paint)
                paint.color = Color.argb(aberAlpha, 0, 255, 255)
                canvas.drawRect(W * 0.94f, 0f, W, H, paint)
            }
        }
    }

    // ── HUD ──────────────────────────────────────────────────────────
    private fun drawHUD(canvas: Canvas, W: Float, H: Float) {
        drawHudBox(canvas, 20f, 20f, 200f, 90f)
        pixelPaint.textSize = 18f; pixelPaint.color = cyan
        canvas.drawText("PONTOS", 40f, 52f, pixelPaint)
        pixelPaint.textSize = 28f; pixelPaint.color = Color.WHITE
        canvas.drawText("$score", 40f, 88f, pixelPaint)

        drawHudBox(canvas, W / 2f - 110f, 20f, 220f, 90f)
        pixelPaint.textSize = 18f; pixelPaint.color = neonYellow
        canvas.drawText("RECORDE", W / 2f - 90f, 52f, pixelPaint)
        pixelPaint.textSize = 28f; pixelPaint.color = Color.WHITE
        canvas.drawText("$hiScore", W / 2f - 90f, 88f, pixelPaint)

        val kmh = (80f * speedMultiplier + (speedMultiplier - 1f) * 120f).toInt()
        drawHudBox(canvas, W - 220f, 20f, 200f, 90f)
        pixelPaint.textSize = 18f; pixelPaint.color = magenta
        canvas.drawText("VELOC.", W - 200f, 52f, pixelPaint)
        pixelPaint.textSize = 28f; pixelPaint.color = Color.WHITE
        canvas.drawText("$kmh km/h", W - 200f, 88f, pixelPaint)

        drawNitroBar(canvas, W, H)
    }

    private fun drawHudBox(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 0, 0, 20)
        canvas.drawRect(x, y, x + w, y + h, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = cyan
        canvas.drawRect(x, y, x + w, y + h, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawNitroBar(canvas: Canvas, W: Float, H: Float) {
        val barW = 280f; val barH = 26f
        val bx = W / 2f - barW / 2f; val by = H - 90f

        canvas.drawBitmap(nitroIcon, bx - nitroIcon.width - 10f, by - 20f, pixelPaint)
        pixelPaint.textSize = 18f; pixelPaint.color = magenta
        canvas.drawText("NITRO", bx, by - 4f, pixelPaint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(180, 30, 0, 40)
        canvas.drawRect(bx, by, bx + barW, by + barH, paint)

        val fillW = barW * (nitroCharge / 100f)
        if (fillW > 0f) {
            paint.shader = LinearGradient(bx, by, bx + fillW, by,
                Color.parseColor("#9900CC"), magenta, Shader.TileMode.CLAMP)
            canvas.drawRect(bx, by, bx + fillW, by + barH, paint)
            paint.shader = null
        }

        // Glow pulsante quando nitro ativo
        if (isNitroActive) {
            val glowAlpha = (160f + sin(System.currentTimeMillis() / 80.0).toFloat() * 60f).toInt()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
            paint.color = Color.argb(glowAlpha.coerceIn(0, 255), 255, 68, 255)
            paint.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.SOLID)
            canvas.drawRect(bx, by, bx + barW, by + barH, paint)
            paint.maskFilter = null
        }

        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = magenta
        canvas.drawRect(bx, by, bx + barW, by + barH, paint)
        paint.style = Paint.Style.FILL
    }

    // ── Calibration ───────────────────────────────────────────────────
    private fun drawCalibrate(canvas: Canvas, W: Float, H: Float) {
        paint.color = Color.argb(200, 0, 0, 15)
        canvas.drawRect(0f, 0f, W, H, paint)

        pixelPaint.textAlign = Paint.Align.CENTER
        pixelPaint.textSize = 52f; pixelPaint.color = cyan
        canvas.drawText("SENSOR", W / 2f, H / 2f - 260f, pixelPaint)
        pixelPaint.color = magenta
        canvas.drawText("DRIVE", W / 2f, H / 2f - 200f, pixelPaint)

        pixelPaint.textSize = 20f; pixelPaint.color = neonYellow
        canvas.drawText("SEGURE O TELEFONE", W / 2f, H / 2f - 130f, pixelPaint)
        canvas.drawText("NA POSIÇÃO DE CONDUZIR", W / 2f, H / 2f - 100f, pixelPaint)

        canvas.drawBitmap(phoneIcon, W / 2f - phoneIcon.width / 2f, H / 2f - 70f, pixelPaint)

        if ((System.currentTimeMillis() / 600) % 2 == 0L) {
            pixelPaint.textSize = 22f; pixelPaint.color = Color.WHITE
            canvas.drawText("TOQUE PARA INICIAR", W / 2f, H / 2f + 60f, pixelPaint)
        }
        pixelPaint.textAlign = Paint.Align.LEFT
    }

    // ── Game Over ─────────────────────────────────────────────────────
    private fun drawGameOver(canvas: Canvas, W: Float, H: Float) {
        paint.color = Color.argb(210, 0, 0, 10)
        canvas.drawRect(0f, 0f, W, H, paint)

        pixelPaint.textAlign = Paint.Align.CENTER
        val pulse = (sin(System.currentTimeMillis() / 300.0) * 0.3f + 0.7f).toFloat()
        pixelPaint.textSize = 64f
        pixelPaint.color = Color.argb((255 * pulse).toInt(), 255, 40, 40)
        canvas.drawText("GAME OVER", W / 2f, H / 2f - 120f, pixelPaint)

        pixelPaint.textSize = 32f; pixelPaint.color = Color.WHITE
        canvas.drawText("PONTUAÇÃO FINAL", W / 2f, H / 2f - 40f, pixelPaint)
        pixelPaint.textSize = 52f; pixelPaint.color = cyan
        canvas.drawText("$score", W / 2f, H / 2f + 30f, pixelPaint)

        canvas.drawBitmap(starIcon, W / 2f - starIcon.width / 2f, H / 2f + 60f, pixelPaint)
        pixelPaint.textSize = 22f; pixelPaint.color = neonYellow
        canvas.drawText("RECORDE: $hiScore", W / 2f, H / 2f + 130f, pixelPaint)

        if ((System.currentTimeMillis() / 700) % 2 == 0L) {
            pixelPaint.textSize = 24f; pixelPaint.color = Color.WHITE
            canvas.drawText("TOQUE PARA TENTAR DE NOVO", W / 2f, H / 2f + 200f, pixelPaint)
        }
        canvas.drawBitmap(flagIcon, W / 2f - flagIcon.width / 2f, H / 2f + 230f, pixelPaint)
        pixelPaint.textAlign = Paint.Align.LEFT
    }

    // ── Thread control ────────────────────────────────────────────────
    fun resume() { isPlaying = true; gameThread = Thread(this); gameThread?.start() }
    fun pause() { isPlaying = false; try { gameThread?.join(200) } catch (_: Exception) {} }
}