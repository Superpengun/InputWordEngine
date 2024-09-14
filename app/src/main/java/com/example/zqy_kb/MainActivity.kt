package com.example.zqy_kb

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.example.zqy_kb.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        binding.sampleText.text = stringFromJNI()
        binding.sampleText2.text = stringFromJNI4()
    }

    /**
     * A native method that is implemented by the 'zqy_kb' native library,
     * which is packaged with this application.
     */
    external fun stringFromJNI(): String

    external fun stringFromJNI2(): String

    external fun stringFromJNI3(): String

    external fun stringFromJNI4(): String

    companion object {
        // Used to load the 'zqy_kb' library on application startup.
        init {
            System.loadLibrary("one")
            System.loadLibrary("two")
        }
    }
}