package com.example.dictionary.utils

import android.util.Log

object JniUtils {
    private val TAG = JniUtils::class.java.simpleName
    private val NAME = "jni_latinime"
    init {
        try {
            System.loadLibrary(NAME)
        } catch (ule: UnsatisfiedLinkError) {
            Log.e(TAG, "Could not load native library $NAME", ule)
        }
    }

    fun loadNativeLibrary() {
        // Ensures the static initializer is called
    }
}
