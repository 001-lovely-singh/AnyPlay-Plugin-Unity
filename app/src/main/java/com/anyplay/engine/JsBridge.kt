package com.anyplay.engine

/**
 * In the main app this talks to the game's WebView. In the Unity plugin the same overlay code
 * sends its keys straight to the X server (Wine) through [KeySender].
 * The overlay files call exactly these two methods.
 */
class JsBridge(private val sender: KeySender) {

    fun interface KeySender {
        fun send(down: Boolean, keyCode: Int, metaState: Int)
    }

    fun sendKeyDown(keyCode: Int, metaState: Int = 0) = sender.send(true, keyCode, metaState)

    fun sendKeyUp(keyCode: Int, metaState: Int = 0) = sender.send(false, keyCode, metaState)
}
