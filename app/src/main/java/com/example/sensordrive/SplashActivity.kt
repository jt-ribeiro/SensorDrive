package com.example.sensordrive

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Esconder a barra superior de título do Android (Action Bar) para ficar Fullscreen
        supportActionBar?.hide()

        // Timer que espera 2.5 segundos (2500ms) antes de abrir o MenuActivity
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(this, MenuActivity::class.java)
            startActivity(intent)

            // Usamos o finish() para fechar a SplashActivity.
            // Assim, se o utilizador carregar no botão "Voltar" no menu, a app fecha em vez de voltar à Splash!
            finish()
        }, 2500)
    }
}