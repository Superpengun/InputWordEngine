package com.superpenguin.foreigninputandoutput.inputEngine

import android.content.Context
import java.util.ArrayList

object KeyboardEngine {
    private val TAG = "KeyboardEngine"
    private lateinit var superPenguinEngineImpl: SuperPenguinEngineImpl
    fun init(context: Context) {
        superPenguinEngineImpl = SuperPenguinEngineImpl()
        superPenguinEngineImpl.onCreate(context)
    }

    fun release() {
        superPenguinEngineImpl.onDestroy()
    }

    fun queryWord(word: String): ArrayList<String> {
        return superPenguinEngineImpl.queryWord(word)
    }

    fun associateWord(word: String): ArrayList<String> {
        return superPenguinEngineImpl.associateWord(word)
    }
}