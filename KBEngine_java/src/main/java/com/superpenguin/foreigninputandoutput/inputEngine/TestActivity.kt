package com.superpenguin.foreigninputandoutput.inputEngine

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.superpenguin.foreigninputandoutput.R

class TestActivity : AppCompatActivity() {

    private val TAG = "TestActivity"
    private var mResultText: TextView? = null
    private var mGetSuggest: Button? = null
    private var mGetAssociate: Button? = null
    private var mInputText: EditText? = null
    private var mInputView: View? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        mResultText = findViewById(R.id.tv_result)
        mGetSuggest = findViewById(R.id.bt_getsuggest)
        mGetAssociate = findViewById(R.id.bt_getassociate)
        mInputText = findViewById(R.id.et_input)
        init()
        bindListener()
    }

    fun init() {
        KeyboardEngine.init(this)
    }

    fun release() {
    }

    private fun bindListener() {
        mGetSuggest?.setOnClickListener {
            var queryText = "query"
            if (mInputText!!.text != null) {
                queryText = mInputText!!.text.toString()
            }
            runOnUiThread {
                val text = KeyboardEngine.queryWord(queryText).joinToString()
                mResultText!!.text = text
            }
        }

        mGetAssociate?.setOnClickListener {
            var queryText = "associate"
            if (mInputText!!.text != null) {
                queryText = mInputText!!.text.toString()
            }
            runOnUiThread {
                val text = KeyboardEngine.associateWord(queryText).joinToString()
                mResultText!!.text = text
            }
        }
    }
}