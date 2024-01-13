package com.superpenguin.foreigninputandoutput.inputEngine

import java.util.ArrayList

interface EngineConnection {
    fun getQueryResult(result: ArrayList<String>)

    fun getAssociateResult(result: ArrayList<String>)

}