package com.example.sensordrive

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.SurfaceView
import java.util.*

class GameView(context: Context, attrs: AttributeSet) : SurfaceView(context, attrs), Runnable {

    private var gameThread: Thread? = null
    private var isPlaying = false
    var isCalibrating = true // No início de cada jogo, começa como true

    private val paint = Paint().apply {
        textSize = 45f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
    }

    private fun loadResource(resId: Int, reqWidth: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = 2
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val raw = BitmapFactory.decodeResource(resources, resId, options)
        return Bitmap.createScaledBitmap(raw, reqWidth, (raw.height * (reqWidth.toFloat() / raw.width)).toInt(), true)
    }

    private val carBitmap by lazy { loadResource(R.drawable.carro_traseira, 200) }
    private val coneBitmap by lazy { loadResource(R.drawable.cones, 90) }
    private val redeBitmap by lazy { loadResource(R.drawable.rede, 600) }
    private val phoneIcon by lazy { loadResource(R.drawable.telemovel, 120) }

    private var carX = 0f
    private var rotationZ = 0f
    private var coneX = 0f
    private var coneY = -200f
    private var score = 0
    private var speedMultiplier = 1.0f
    private val random = Random()

    fun updateRotation(z: Float) { rotationZ = z }
    fun setNitro(active: Boolean) { speedMultiplier = if (active) 2.2f else 1.0f }

    override fun run() {
        while (isPlaying) {
            if (!holder.surface.isValid) continue
            val canvas = holder.lockCanvas() ?: continue
            try {
                if (!isCalibrating) updateLogic()
                drawGame(canvas)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            Thread.sleep(16) // 60 FPS estáveis
        }
    }

    private fun updateLogic() {
        // MATEMÁTICA REALISTA:
        // O Rotation Vector entrega valores de -1 a 1 (radianos convertidos).
        // 4000f de sensibilidade faz com que ~15-20 graus de inclinação real
        // movam o carro completamente para o lado.
        val sensitivity = 4200f
        val targetX = (width / 2f) - (carBitmap.width / 2f) + (rotationZ * sensitivity)

        // Suavização (Interpolação)
        carX += (targetX - carX) * 0.2f

        // TRAVÃO DE ECRÃ (Clamp): O carro pára nas bordas
        val margin = 10f
        if (carX < margin) carX = margin
        if (carX > width - carBitmap.width - margin) carX = width - carBitmap.width - margin

        // Obstáculos
        coneY += 16 * speedMultiplier
        if (coneY > height) {
            coneY = -200f
            coneX = random.nextInt((width - coneBitmap.width).coerceAtLeast(1)).toFloat()
            score += 10
        }

        // Colisão simplificada
        val carRect = RectF(carX, height - 450f, carX + carBitmap.width, height - 450f + carBitmap.height)
        val coneRect = RectF(coneX, coneY, coneX + coneBitmap.width, coneY + coneBitmap.height)
        if (carRect.intersect(coneRect)) {
            score = (score - 50).coerceAtLeast(0)
            coneY = -600f
        }
    }

    private fun drawGame(canvas: Canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.drawBitmap(redeBitmap, (width / 2f - redeBitmap.width / 2f), (height / 2f), null)

        if (isCalibrating) {
            paint.color = Color.YELLOW
            canvas.drawText("SEGURE DIREITO E TOQUE", width/2f - 250f, height/2f - 150f, paint)
            canvas.drawText("PARA CALIBRAR 15º", width/2f - 200f, height/2f - 80f, paint)
            canvas.drawBitmap(phoneIcon, width/2f - phoneIcon.width/2f, height/2f, null)
        } else {
            canvas.drawBitmap(carBitmap, carX, height - 450f, null)
            canvas.drawBitmap(coneBitmap, coneX, coneY, null)
            paint.color = Color.CYAN
            canvas.drawText("PONTOS: $score", 50f, 80f, paint)
        }
    }

    fun resume() { isPlaying = true; gameThread = Thread(this); gameThread?.start() }
    fun pause() { isPlaying = false; try { gameThread?.join() } catch(e: Exception) {} }
}