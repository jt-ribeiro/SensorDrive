package com.example.sensordrive

// ── OBJETOS DE JOGO ──

data class Cone(
    var x: Float,
    var y: Float,
    val isPurple: Boolean,
    var hit: Boolean = false,
    var passed: Boolean = false
)

data class BonusStar(
    var x: Float,
    var y: Float,
    var hit: Boolean = false
)

// ── EFEITOS VISUAIS ──

data class WindLine(
    var x: Float,
    var y: Float,
    val angle: Float,
    var speed: Float,
    val length: Float,
    val color: Int,
    var alpha: Int,
    var life: Int,
    val maxLife: Int
)

data class Popup(
    val text: String,
    val color: Int,
    var x: Float,
    var y: Float,
    var life: Int
)

// ── UI (MENU) ──

data class MenuButton(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val color: Int,
    val action: () -> Unit
)