package com.example.zqy_kb

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.zqy_kb.databinding.ActivityMainBinding
import com.superpenguin.foreigninputandoutput.inputEngine.KeyboardEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext


class MainActivityForLogic : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mResultText: TextView? = null
    private val TAG = "TestEngine"
    private val scope = CoroutineScope(newSingleThreadContext("MySingleThread") + SupervisorJob())
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        mResultText = binding.sampleText
        setContentView(binding.root)
        bindClick()
    }

    private fun bindClick() {
        KeyboardEngine.init(applicationContext,"en")
        binding.button.setOnClickListener {
            runOnUiThread {
                val text =
                    KeyboardEngine.queryWord(binding.editTextText.text.toString()).joinToString()
                mResultText!!.text = text
            }
        }
        binding.button2.setOnClickListener {
            runOnUiThread {
                val text = KeyboardEngine.associateWord(binding.editTextText.text.toString())
                    .joinToString()
                binding.sampleText2.text = text
            }
        }
        binding.button3.setOnClickListener {
            stressQuery()
        }
        binding.button4.setOnClickListener {
            stressAssociate()
        }
        binding.button5.setOnClickListener {
            runOnUiThread {
                KeyboardEngine.switchLanguage(binding.editTextText.text.toString())
            }
        }
        binding.button6.setOnClickListener {
            runOnUiThread {
                KeyboardEngine.switchLanguage("en")
            }
        }
    }

    private fun stressQuery() {
        var num = try {
            binding.editTextText2.text.toString().toInt()
        }catch (_:Exception){
            1
        }
        if (num <= 1) {
            num = 1
        }
        val inputText = binding.editTextText3.text.toString()
        var items = inputText.split(",".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        if (items.isEmpty()){
            items = listOf("test").toTypedArray()
        }
            for (i in 0 until num) {
                scope.launch{
                    val randomWord = items.random()
                    val result = KeyboardEngine.queryWord(randomWord)
                    // 处理结果，例如打印或进行其他操作
                    if (result.isNotEmpty()) {
                        Log.d(TAG, "stressQuery: current is $i queryword is $randomWord result is ${result[0]}")
                    } else {
                        Log.d(TAG, "stressQuery: empty")
                    }
                }
                SystemClock.sleep(50)
            }

    }

    private fun stressAssociate() {
        var num = try {
            binding.editTextText2.text.toString().toInt()
        }catch (_:Exception){
            1
        }
        if (num <= 1) {
            num = 1
        }
        val words = listOf("an", "who", "what", "it")
        scope.launch {
            for (i in 0 until num) {
                val randomWord = words.random()
                val result = KeyboardEngine.associateWord(randomWord)
                // 处理结果，例如打印或进行其他操作
                if (result.isNotEmpty()) {
                    Log.d(TAG, "stressAssociate: current is $i result is ${result[0]}")
                } else {
                    Log.d(TAG, "stressAssociate: empty")
                }
                delay(500)
            }
        }
    }


    override fun finish() {
        KeyboardEngine.release()
        super.finish()
    }
}