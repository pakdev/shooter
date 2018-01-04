package com.petesburgh.shooter

class Frame constructor(val width: Int, val height: Int, val displayWidth: Int, val displayHeight: Int) {
    val scaleX: Float
        get() = displayWidth as Float / width


    val scaleY: Float
        get() = displayHeight as Float / height
}
