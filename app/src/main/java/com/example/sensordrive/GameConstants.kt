package com.example.sensordrive

object GameConstants {
    // ── PALETA NEON (Convertida para Hexadecimal Nativo) ──
    const val CYAN        = 0xFF00FFFF.toInt()
    const val MAGENTA     = 0xFFFF44FF.toInt()
    const val NEON_YELLOW = 0xFFFFEE00.toInt()
    const val NEON_RED    = 0xFFFF3333.toInt()
    const val NEON_GREEN  = 0xFF00FF88.toInt()
    const val DARK_BG     = 0xFF07001A.toInt()

    // ── FÍSICA E SENSORES ──
    const val SENSOR_FILTER_ALPHA = 0.15f
    const val SENSOR_DEADZONE     = 0.03f
    const val SENSOR_MAX_TILT     = 0.40f

    // ── MECÂNICAS BASE ──
    const val INITIAL_GAME_SPEED = 7f
    const val MAX_GAME_SPEED = 16f
    const val MAX_NITRO_CHARGE = 100f
    const val DISTANCE_MULTIPLIER_THRESHOLD = 5000f

    // A VELOCIDADE DA ESTRADA VOLTOU! 🏎️💨
    const val ROAD_SCROLL_SPEED = 18f

    // ── TEMPOS (EM MILISSEGUNDOS) ──
    const val BONUS_NITRO_DURATION_MS = 5000L
    const val CONFUSION_DURATION_MS = 3000L
}