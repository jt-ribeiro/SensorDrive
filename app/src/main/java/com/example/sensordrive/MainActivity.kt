package com.example.sensordrive

import android.content.Context
import android.hardware.*
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var gameView: GameView
    private var rotationSensor: Sensor? = null
    private var proximitySensor: Sensor? = null

    // Calibração única por sessão de jogo
    private var offsetZ = 0f
    private var hasCalibratedThisGame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        // Clique para calibrar (só funciona se gameView.isCalibrating for true)
        gameView.setOnClickListener {
            if (gameView.isCalibrating) {
                // A calibração será capturada no próximo onSensorChanged
                hasCalibratedThisGame = false
                gameView.isCalibrating = false
                Toast.makeText(this, "Calibrado! Use movimentos curtos (15º)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val currentZ = event.values[2]

            // Se o utilizador já saiu do ecrã de calibração mas ainda não guardamos o offset
            if (!gameView.isCalibrating && !hasCalibratedThisGame) {
                offsetZ = currentZ
                hasCalibratedThisGame = true
            }

            // Só envia dados se já estiver calibrado
            if (hasCalibratedThisGame) {
                gameView.updateRotation(currentZ - offsetZ)
            }
        }

        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val isNear = event.values[0] < event.sensor.maximumRange
            gameView.setNitro(isNear)
        }
    }

    override fun onResume() {
        super.onResume()
        gameView.resume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        proximitySensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        super.onPause()
        gameView.pause()
        sensorManager.unregisterListener(this)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}