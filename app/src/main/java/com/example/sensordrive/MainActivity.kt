package com.example.sensordrive

import android.content.Context
import android.hardware.*
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var gameView: GameView
    private var rotationSensor: Sensor? = null
    private var proximitySensor: Sensor? = null

    // Controladores de Áudio
    private var bgMusic: MediaPlayer? = null
    private var carMusic: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor  = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        setupAudio()
    }

    private fun setupAudio() {
        try {
            // Música de Fundo (Loop)
            bgMusic = MediaPlayer.create(this, R.raw.musica_fundo)
            bgMusic?.isLooping = true
            bgMusic?.setVolume(0.5f, 0.5f) // Volume a 50% para não abafar o motor

            // Som do Motor (Loop)
            carMusic = MediaPlayer.create(this, R.raw.car_sound)
            carMusic?.isLooping = true
            carMusic?.setVolume(0.8f, 0.8f)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // SOLUÇÃO DO BUG: Converter Quaternião em Ângulos Reais (Roll)
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                // orientation[2] é o Roll (inclinação lateral) em radianos. 100% simétrico!
                val roll = orientation[2]
                gameView.updateSensor(roll)
            }

            Sensor.TYPE_PROXIMITY -> {
                val isNear = event.values[0] < (event.sensor.maximumRange * 0.5f)
                gameView.setNitro(isNear)

                // Acelera o som do motor quando o Nitro está ativo! (Apenas para Android 6.0+)
                try {
                    val params = PlaybackParams()
                    params.speed = if (isNear) 1.5f else 1.0f // 1.5x mais rápido no Nitro
                    carMusic?.playbackParams = params
                } catch (e: Exception) {
                    // Fallback se o telemóvel for muito antigo
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
        bgMusic?.start()
        carMusic?.start()
        rotationSensor?.let  { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)  }
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
        bgMusic?.pause()
        carMusic?.pause()
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        bgMusic?.release()
        carMusic?.release()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}