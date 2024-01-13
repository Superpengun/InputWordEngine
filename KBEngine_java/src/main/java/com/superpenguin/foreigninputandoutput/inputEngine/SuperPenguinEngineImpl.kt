/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.superpenguin.foreigninputandoutput.inputEngine

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Debug
import android.preference.PreferenceManager
import android.util.Log
import com.superpenguin.foreigninputandoutput.accessibility.AccessibilityUtils
import com.superpenguin.foreigninputandoutput.annotations.UsedForTesting
import com.superpenguin.foreigninputandoutput.method.DictionaryFacilitator.DictionaryInitializationListener
import com.superpenguin.foreigninputandoutput.method.DictionaryFacilitatorProvider
import com.superpenguin.foreigninputandoutput.method.InputAttributes
import com.superpenguin.foreigninputandoutput.method.RichInputMethodManager
import com.superpenguin.foreigninputandoutput.method.Suggest.OnGetSuggestedWordsCallback
import com.superpenguin.foreigninputandoutput.method.SuggestedWords
import com.superpenguin.foreigninputandoutput.method.common.Constants
import com.superpenguin.foreigninputandoutput.method.common.CoordinateUtils
import com.superpenguin.foreigninputandoutput.method.define.DebugFlags
import com.superpenguin.foreigninputandoutput.method.personalization.PersonalizationHelper
import com.superpenguin.foreigninputandoutput.method.settings.Settings
import com.superpenguin.foreigninputandoutput.method.settings.SettingsValues
import com.superpenguin.foreigninputandoutput.utils.JniUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.CoroutineContext

class SuperPenguinEngineImpl : DictionaryInitializationListener {
    private val mSettings: Settings = Settings.getInstance()
    private val mDictionaryFacilitator =
        DictionaryFacilitatorProvider.getDictionaryFacilitator(false /* isNeededForSpellChecking */)
    private val mInputLogic = InputLogicReplace(this, mDictionaryFacilitator)
    private var mRichImm: RichInputMethodManager? = null

    @UsedForTesting
    val mPenguinLanguageSwitcher: PenguinLanguageSwitcher = PenguinLanguageSwitcher.instance

    // Working variable for {@link #startShowingInputView()} and
    // {@link #onEvaluateInputViewShown()}.
    private val currentScope = CoroutineScope(Dispatchers.Main)
    private lateinit var mInputScope: CoroutineScope
    private var singleInputScope: CoroutineContext? = null
    var context: Context? = null

    fun queryWord(word: String?): ArrayList<String> = runBlocking{
        withContext(singleInputScope!!){
            try {
                val resultFuture = mInputLogic.originalQuery(word)
                resultFuture.join()
            } catch (e: Exception) {
                Log.d(TAG, "queryWord: 超时未获取返回空结果")
                e.printStackTrace() // 处理异常情况
                ArrayList() // 返回空列表或其他默认值
            }
        }
    }

    fun associateWord(word: String?): ArrayList<String> = runBlocking{
        withContext(singleInputScope!!){
            originalAssociate(word)
        }
    }

    fun onCreate(context: Context?) {
        this.context = context
        Settings.init(this.context)
        DebugFlags.init(PreferenceManager.getDefaultSharedPreferences(this.context))
        RichInputMethodManager.init(this.context)
        mRichImm = RichInputMethodManager.getInstance()
        PenguinLanguageSwitcher.init(this)
        AccessibilityUtils.init(this.context)
        loadSettings()

        // Register to receive ringer mode change.
        val filter = IntentFilter()
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)

        // Register to receive installation and removal of a dictionary pack.
        val packageFilter = IntentFilter()
        packageFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
        packageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        packageFilter.addDataScheme(SCHEME_PACKAGE)
        mPenguinLanguageSwitcher.onCreateInputView(true)
        if (singleInputScope == null) {
            singleInputScope = newSingleThreadContext("input")
            mInputScope = CoroutineScope(singleInputScope!!)
        }
        currentScope.launch {
            onStartInput()
        }
    }

    // Has to be package-visible for unit tests
    @UsedForTesting
    fun loadSettings() {
        val locale = mRichImm!!.currentSubtypeLocale
        val inputAttributes = InputAttributes()
        mSettings.loadSettings(context, locale, inputAttributes)
        val currentSettingsValues = mSettings.current
        // This method is called on startup and language switch, before the new layout has
        // been displayed. Opening dictionaries never affects responsivity as dictionaries are
        // asynchronously loaded.
        refreshPersonalizationDictionarySession(currentSettingsValues)
    }

    private fun refreshPersonalizationDictionarySession(currentSettingsValues: SettingsValues) {
        if (!currentSettingsValues.mUsePersonalizedDicts) {
            // Remove user history dictionaries.
            PersonalizationHelper.removeAllUserHistoryDictionaries(context)
            mDictionaryFacilitator.clearUserHistoryDictionary(context)
        }
    }

    // Note that this method is called from a non-UI thread.
    override fun onUpdateMainDictionaryAvailability(isMainDictionaryAvailable: Boolean) {
        val mainKeyboardView = mPenguinLanguageSwitcher.mainKeyboardView
        mainKeyboardView?.setMainDictionaryAvailability(isMainDictionaryAvailable)
    }

    private fun resetDictionaryFacilitatorIfNecessary() {
        val subtypeSwitcherLocale = Locale.ENGLISH
        val subtypeLocale: Locale = subtypeSwitcherLocale
        if (mDictionaryFacilitator.isForLocale(subtypeLocale) && mDictionaryFacilitator.isForAccount(
                mSettings.current.mAccount
            )
        ) {
            return
        }
        resetDictionaryFacilitator(subtypeLocale)
    }

    /**
     * Reset the facilitator by loading dictionaries for the given locale and
     * the current settings values.
     *
     * @param locale the locale
     */
    // TODO: make sure the current settings always have the right locales, and read from them.
    private fun resetDictionaryFacilitator(locale: Locale) {
        val settingsValues = mSettings.current
        mDictionaryFacilitator.resetDictionaries(
            context /* context */,
            locale,
            settingsValues.mUseContactsDict,
            settingsValues.mUsePersonalizedDicts,
            false /* forceReloadMainDictionary */,
            settingsValues.mAccount,
            "" /* dictNamePrefix */,
            this /* DictionaryInitializationListener */
        )
        if (settingsValues.mAutoCorrectionEnabledPerUserSettings) {
            mInputLogic.mSuggest.setAutoCorrectionThreshold(settingsValues.mAutoCorrectionThreshold)
        }
        mInputLogic.mSuggest.setPlausibilityThreshold(settingsValues.mPlausibilityThreshold)
    }

    fun onDestroy() {
        currentScope.launch {
            cleanupInternalStateForFinishInput()
            onFinishInputInternal()
        }
        mDictionaryFacilitator.closeDictionaries()
        mSettings.onDestroy()
    }

    @UsedForTesting
    fun recycle() {
        mInputLogic.recycle()
    }

    private fun onStartInput() {
        mDictionaryFacilitator.onStartInput()
        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        mRichImm!!.refreshSubtypeCaches()
        val switcher = mPenguinLanguageSwitcher
        val mainKeyboardView = switcher.mainKeyboardView
        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        var currentSettingsValues = mSettings.current

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return
        }

        // Forward this event to the accessibility utilities, if enabled.
        val accessUtils = AccessibilityUtils.getInstance()
        if (accessUtils.isTouchExplorationEnabled) {
            accessUtils.onStartInputViewInternal(mainKeyboardView, null, false)
        }
        val isDifferentTextField = true
        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        val suggest = mInputLogic.mSuggest
        // The app calling setText() has the effect of clearing the composing
        // span, so we should reset our state unconditionally, even if restarting is true.
        // We also tell the input logic about the combining rules for the current subtype, so
        // it can adjust its combiners if needed.
        mInputLogic.startInput(mRichImm!!.combiningRulesExtraValueOfCurrentSubtype)
        resetDictionaryFacilitatorIfNecessary()

        if (isDifferentTextField || !currentSettingsValues.hasSameOrientation(context!!.resources.configuration)) {
            loadSettings()
        }
        if (isDifferentTextField) {
            mainKeyboardView.closing()
            currentSettingsValues = mSettings.current
            if (currentSettingsValues.mAutoCorrectionEnabledPerUserSettings) {
                suggest.setAutoCorrectionThreshold(currentSettingsValues.mAutoCorrectionThreshold)
            }
            suggest.setPlausibilityThreshold(currentSettingsValues.mPlausibilityThreshold)
            switcher.loadKeyboard(
                null,
                currentSettingsValues,
                currentAutoCapsState,
                currentRecapitalizeState
            )
        } else {
            // TODO: Come up with a more comprehensive way to reset the keyboard layout when
            // a keyboard layout set doesn't get reloaded in this method.
            switcher.resetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState)
            // In apps like Talk, we come here when the text is sent and the field gets emptied and
            // we need to re-evaluate the shift state, but not the whole layout which would be
            // disruptive.
            // Space state must be updated before calling updateShiftState
        }
        // This will set the punctuation suggestions if next word suggestion is off;
        // otherwise it will clear the suggestion strip.
        mainKeyboardView.setMainDictionaryAvailability(mDictionaryFacilitator.hasAtLeastOneInitializedMainDictionary())
        mainKeyboardView.setKeyPreviewPopupEnabled(
            currentSettingsValues.mKeyPreviewPopupOn,
            currentSettingsValues.mKeyPreviewPopupDismissDelay
        )
        mainKeyboardView.setSlidingKeyInputPreviewEnabled(currentSettingsValues.mSlidingKeyInputPreviewEnabled)
        mainKeyboardView.setGestureHandlingEnabledByUser(
            currentSettingsValues.mGestureInputEnabled,
            currentSettingsValues.mGestureTrailEnabled,
            currentSettingsValues.mGestureFloatingPreviewTextEnabled
        )
        if (TRACE) Debug.startMethodTracing("/data/trace/latinime")
    }

    private fun onFinishInputInternal() {
        mDictionaryFacilitator.onFinishInput(context)
        val mainKeyboardView = mPenguinLanguageSwitcher.mainKeyboardView
        mainKeyboardView?.closing()
    }

    private fun cleanupInternalStateForFinishInput() {
        mInputLogic.finishInput()
    }

    private val currentAutoCapsState: Int
        get() = mInputLogic.getCurrentAutoCapsState(mSettings.current)
    private val currentRecapitalizeState: Int
        get() = mInputLogic.currentRecapitalizeState

    /**
     * @param codePoints code points to get coordinates for.
     * @return x, y coordinates for this keyboard, as a flattened array.
     */
    fun getCoordinatesForCurrentKeyboard(codePoints: IntArray): IntArray {
        val keyboard = mPenguinLanguageSwitcher.keyboard
            ?: return CoordinateUtils.newCoordinateArray(
                codePoints.size,
                Constants.NOT_A_COORDINATE,
                Constants.NOT_A_COORDINATE
            )
        return keyboard.getCoordinates(codePoints)
    }

    fun getSuggestedWords(
        inputStyle: Int,
        sequenceNumber: Int,
        callback: OnGetSuggestedWordsCallback
    ) {
        val keyboard = mPenguinLanguageSwitcher.keyboard
        if (keyboard == null) {
            callback.onGetSuggestedWords(SuggestedWords.getEmptyInstance())
            return
        }
        mInputLogic.getSuggestedWords(
            mSettings.current,
            keyboard,
            mPenguinLanguageSwitcher.keyboardShiftMode,
            inputStyle,
            sequenceNumber,
            callback
        )
    }

    private fun originalAssociate(word: String?): ArrayList<String> {
        return mInputLogic.originalAssociate(
            mSettings.current,
            word,
            mPenguinLanguageSwitcher.keyboardShiftMode
        )
    }

    companion object {
        val TAG: String = SuperPenguinEngineImpl::class.java.simpleName
        private const val TRACE = false
        private const val SCHEME_PACKAGE = "package"

        init {
            JniUtils.loadNativeLibrary()
        }
    }
}
