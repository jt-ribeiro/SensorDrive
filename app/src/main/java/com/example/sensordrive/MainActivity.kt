package com.example.sensordrive

import android.content.Context
import android.hardware.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.SoundPool
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var gameView: GameView
    private var rotationSensor: Sensor? = null
    private var proximitySensor: Sensor? = null

    // Controladores de Áudio Contínuo
    private var bgMusic: MediaPlayer? = null
    private var carMusic: MediaPlayer? = null

    // SoundPool para Efeitos Especiais (FX) Rápidos
    private lateinit var soundPool: SoundPool
    private var somEstrelaId = 0
    private var somEmbateId = 0
    private var somPerdeuId = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        gameView = findViewById(R.id.gameView)

        // Passar a referência da MainActivity para a GameView poder chamar os sons
        gameView.setAudioController(this)

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
            bgMusic?.setVolume(0.4f, 0.4f)

            // Som do Motor (Loop) - A 75% conforme pedido
            carMusic = MediaPlayer.create(this, R.raw.car_sound)
            carMusic?.isLooping = true
            carMusic?.setVolume(0.75f, 0.75f)

            // Setup SoundPool para FX
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            soundPool = SoundPool.Builder()
                .setMaxStreams(5) // Pode tocar até 5 sons ao mesmo tempo
                .setAudioAttributes(audioAttributes)
                .build()

            // Carregar sons curtos (devem estar na pasta res/raw)
            somEstrelaId = soundPool.load(this, R.raw.estrela_coletada, 1)
            somEmbateId = soundPool.load(this, R.raw.embate, 1)
            somPerdeuId = soundPool.load(this, R.raw.perdeu, 1)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Função que será chamada pela GameView
    fun playSoundFX(type: String) {
        when (type) {
            "ESTRELA" -> soundPool.play(somEstrelaId, 1f, 1f, 1, 0, 1f)
            "EMBATE_NORMAL" -> soundPool.play(somEmbateId, 1f, 1f, 1, 0, 1f)
            "EMBATE_ROXO" -> soundPool.play(somEmbateId, 1f, 1f, 1, 0, 1.5f) // Pitch +50%
            "PERDEU" -> soundPool.play(somPerdeuId, 1f, 1f, 1, 0, 1f)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)

                val roll = orientation[2]
                gameView.updateSensor(roll)

                // ── ÁUDIO 3D (STEREO PANNING) ──
                val steer = gameView.getCurrentSteer()

                var leftVol = 0.75f - (steer * 0.5f)
                var rightVol = 0.75f + (steer * 0.5f)

                leftVol = leftVol.coerceIn(0.1f, 0.75f)
                rightVol = rightVol.coerceIn(0.1f, 0.75f)

                carMusic?.setVolume(leftVol, rightVol)
            }

            Sensor.TYPE_PROXIMITY -> {
                val isNear = event.values[0] < (event.sensor.maximumRange * 0.5f)
                gameView.setNitro(isNear)

                try {
                    val params = PlaybackParams()
                    params.speed = if (isNear) 1.5f else 1.0f
                    carMusic?.playbackParams = params
                } catch (e: Exception) {}
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
        soundPool.release()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}