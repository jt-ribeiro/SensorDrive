package com.example.sensordrive

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    // Controlador de Música do Menu
    private var menuMusic: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // Referências às Camadas
        val layoutMenu = findViewById<LinearLayout>(R.id.layoutMenu)
        val layoutInstrucoes = findViewById<LinearLayout>(R.id.layoutInstrucoes)

        // Referências aos Botões
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnInstrucoes = findViewById<Button>(R.id.btnInstrucoes)
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)
        val txtHighScore = findViewById<TextView>(R.id.txtHighScore)

        // Carregar o Recorde guardado no telemóvel
        val prefs = getSharedPreferences("SensorDrivePrefs", Context.MODE_PRIVATE)
        val hiScore = prefs.getInt("hiScore", 0)
        txtHighScore.text = "RECORDE: $hiScore"

        // ── Lógica de Navegação Interna ──

        // Abrir Instruções (Esconde Menu, Mostra Instruções)
        btnInstrucoes.setOnClickListener {
            layoutMenu.visibility = View.GONE
            layoutInstrucoes.visibility = View.VISIBLE
        }

        // Fechar Instruções (Esconde Instruções, Mostra Menu)
        btnVoltar.setOnClickListener {
            layoutInstrucoes.visibility = View.GONE
            layoutMenu.visibility = View.VISIBLE
        }

        // ── Iniciar o Jogo Real ──
        btnStart.setOnClickListener {
            // Parar a música do menu para não tocar por cima da música do jogo
            menuMusic?.stop()
            menuMusic?.release()
            menuMusic = null

            // Passar para a MainActivity (onde está a GameView)
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Tocar a música em loop sempre que o menu está aberto
        if (menuMusic == null) {
            menuMusic = MediaPlayer.create(this, R.raw.musica_fundo)
            menuMusic?.isLooping = true
            menuMusic?.setVolume(0.4f, 0.4f)
            menuMusic?.start()
        } else if (menuMusic?.isPlaying == false) {
            menuMusic?.start()
        }
    }

    override fun onPause() {
        super.onPause()
        // Pausar a música caso o utilizador minimize a aplicação
        menuMusic?.pause()
    }
}