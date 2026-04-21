package com.example.sensordrive

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

class MenuActivity : AppCompatActivity() {

    // Controlador de Música do Menu (Toca em loop enquanto o jogador está nesta janela)
    private var menuMusic: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        // ── Referências aos elementos da Interface (Camadas e Botões) ──
        val layoutMenu = findViewById<LinearLayout>(R.id.layoutMenu)
        val layoutInstrucoes = findViewById<LinearLayout>(R.id.layoutInstrucoes)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnInstrucoes = findViewById<Button>(R.id.btnInstrucoes)
        val btnVoltar = findViewById<Button>(R.id.btnVoltar)

        // ── Lógica de Navegação Interna (Overlays) ──

        // Abrir Instruções: Esconde a vista do Menu Principal e mostra o ecrã de "Como Jogar"
        btnInstrucoes.setOnClickListener {
            layoutMenu.visibility = View.GONE
            layoutInstrucoes.visibility = View.VISIBLE
        }

        // Voltar ao Menu: Faz o inverso, voltando ao ecrã inicial sem interromper a música
        btnVoltar.setOnClickListener {
            layoutInstrucoes.visibility = View.GONE
            layoutMenu.visibility = View.VISIBLE
        }

        // ── Iniciar o Jogo Real ──
        btnStart.setOnClickListener {
            // Parar e libertar a música do menu da memória para não tocar por cima do áudio do jogo
            menuMusic?.stop()
            menuMusic?.release()
            menuMusic = null

            // Passar para a MainActivity (onde está o motor gráfico GameView)
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Iniciar ou retomar a música de fundo sempre que a Activity está visível
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
        // Pausar a música caso o utilizador minimize a aplicação ou receba uma chamada
        menuMusic?.pause()
    }
}