package com.example.sensordrive



import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.jvm.java

class MenuActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_menu)

        val btnStart = findViewById<Button>(R.id.btnStart)
        btnStart.setOnClickListener {
            // Salta para o jogo
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("SensorDrivePrefs", Context.MODE_PRIVATE)
        val hiScore = prefs.getInt("hiScore", 0)
        findViewById<TextView>(R.id.txtHighScore).text = "RECORDE: $hiScore"
    }
}