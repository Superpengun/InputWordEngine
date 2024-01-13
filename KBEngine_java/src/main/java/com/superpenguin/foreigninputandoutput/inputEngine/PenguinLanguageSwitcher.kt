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
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import com.superpenguin.foreigninputandoutput.R
import com.superpenguin.foreigninputandoutput.keyboard.Keyboard
import com.superpenguin.foreigninputandoutput.keyboard.KeyboardId
import com.superpenguin.foreigninputandoutput.keyboard.KeyboardLayoutSet
import com.superpenguin.foreigninputandoutput.keyboard.KeyboardLayoutSet.KeyboardLayoutSetException
import com.superpenguin.foreigninputandoutput.keyboard.KeyboardTheme
import com.superpenguin.foreigninputandoutput.keyboard.MainKeyboardView
import com.superpenguin.foreigninputandoutput.keyboard.internal.KeyboardState
import com.superpenguin.foreigninputandoutput.keyboard.internal.KeyboardState.SwitchActions
import com.superpenguin.foreigninputandoutput.keyboard.internal.KeyboardTextsSet
import com.superpenguin.foreigninputandoutput.method.InputView
import com.superpenguin.foreigninputandoutput.method.RichInputMethodManager
import com.superpenguin.foreigninputandoutput.method.WordComposer
import com.superpenguin.foreigninputandoutput.method.define.ProductionFlags
import com.superpenguin.foreigninputandoutput.method.settings.Settings
import com.superpenguin.foreigninputandoutput.method.settings.SettingsValues
import com.superpenguin.foreigninputandoutput.utils.CapsModeUtils
import com.superpenguin.foreigninputandoutput.utils.LanguageOnSpacebarUtils
import com.superpenguin.foreigninputandoutput.utils.RecapitalizeStatus
import com.superpenguin.foreigninputandoutput.utils.ResourceUtils
import javax.annotation.Nonnull

class PenguinLanguageSwitcher private constructor() : SwitchActions {
    private var mCurrentInputView: InputView? = null
    private var mMainKeyboardFrame: View? = null
    var mainKeyboardView: MainKeyboardView? = null
    private var superPenguinEngine: SuperPenguinEngineImpl? = null
    private var mRichImm: RichInputMethodManager? = null
    private var mState: KeyboardState? = null
    private var mKeyboardLayoutSet: KeyboardLayoutSet? = null

    // TODO: The following {@link KeyboardTextsSet} should be in {@link KeyboardLayoutSet}.
    private val mKeyboardTextsSet = KeyboardTextsSet()
    private var mKeyboardTheme: KeyboardTheme? = null
    private var mThemeContext: Context? = null
    private fun initInternal(superPenguinEngine: SuperPenguinEngineImpl) {
        this.superPenguinEngine = superPenguinEngine
        mRichImm = RichInputMethodManager.getInstance()
        mState = KeyboardState(this)
    }

    private fun updateKeyboardThemeAndContextThemeWrapper(
        context: Context?,
        keyboardTheme: KeyboardTheme
    ) {
        if (mThemeContext == null || keyboardTheme != mKeyboardTheme) {
            mKeyboardTheme = keyboardTheme
            mThemeContext = ContextThemeWrapper(context, keyboardTheme.mStyleId)
            KeyboardLayoutSet.onKeyboardThemeChanged()
        }
    }

    fun loadKeyboard(
        editorInfo: EditorInfo?, settingsValues: SettingsValues,
        currentAutoCapsState: Int, currentRecapitalizeState: Int
    ) {
        val builder = KeyboardLayoutSet.Builder(
            mThemeContext, editorInfo
        )
        val res = mThemeContext!!.resources
        val keyboardWidth = ResourceUtils.getDefaultKeyboardWidth(res)
        val keyboardHeight = ResourceUtils.getKeyboardHeight(res, settingsValues)
        builder.setKeyboardGeometry(keyboardWidth, keyboardHeight)
        builder.setSubtype(mRichImm!!.currentSubtype)
        builder.setVoiceInputKeyEnabled(settingsValues.mShowsVoiceInputKey)
        builder.setLanguageSwitchKeyEnabled(false)
        builder.setSplitLayoutEnabledByUser(
            ProductionFlags.IS_SPLIT_KEYBOARD_SUPPORTED
                    && settingsValues.mIsSplitKeyboardEnabled
        )
        mKeyboardLayoutSet = builder.build()
        try {
            mState!!.onLoadKeyboard(currentAutoCapsState, currentRecapitalizeState)
            mKeyboardTextsSet.setLocale(mRichImm!!.currentSubtypeLocale, mThemeContext)
        } catch (e: KeyboardLayoutSetException) {
            Log.w(TAG, "loading keyboard failed: " + e.mKeyboardId, e.cause)
        }
    }

    private fun setKeyboard(
        keyboardId: Int,
        @Nonnull toggleState: KeyboardSwitchState
    ) {
        // Make {@link MainKeyboardView} visible and hide {@link EmojiPalettesView}.
        val currentSettingsValues = Settings.getInstance().current
        setMainKeyboardFrame(currentSettingsValues, toggleState)
        // TODO: pass this object to setKeyboard instead of getting the current values.
        val keyboardView = mainKeyboardView
        val oldKeyboard = keyboardView!!.keyboard
        val newKeyboard = mKeyboardLayoutSet!!.getKeyboard(keyboardId)
        keyboardView.setKeyboard(newKeyboard)
        mCurrentInputView!!.setKeyboardTopPadding(newKeyboard.mTopPadding)
        keyboardView.setKeyPreviewPopupEnabled(
            currentSettingsValues.mKeyPreviewPopupOn,
            currentSettingsValues.mKeyPreviewPopupDismissDelay
        )
        keyboardView.setKeyPreviewAnimationParams(
            currentSettingsValues.mHasCustomKeyPreviewAnimationParams,
            currentSettingsValues.mKeyPreviewShowUpStartXScale,
            currentSettingsValues.mKeyPreviewShowUpStartYScale,
            currentSettingsValues.mKeyPreviewShowUpDuration,
            currentSettingsValues.mKeyPreviewDismissEndXScale,
            currentSettingsValues.mKeyPreviewDismissEndYScale,
            currentSettingsValues.mKeyPreviewDismissDuration
        )
        keyboardView.updateShortcutKey(mRichImm!!.isShortcutImeReady)
        val subtypeChanged = (oldKeyboard == null
                || newKeyboard.mId.mSubtype != oldKeyboard.mId.mSubtype)
        val languageOnSpacebarFormatType = LanguageOnSpacebarUtils
            .getLanguageOnSpacebarFormatType(newKeyboard.mId.mSubtype)
        val hasMultipleEnabledIMEsOrSubtypes = mRichImm!!
            .hasMultipleEnabledIMEsOrSubtypes(true /* shouldIncludeAuxiliarySubtypes */)
        keyboardView.startDisplayLanguageOnSpacebar(
            subtypeChanged, languageOnSpacebarFormatType,
            hasMultipleEnabledIMEsOrSubtypes
        )
    }

    val keyboard: Keyboard?
        get() = if (mainKeyboardView != null) {
            mainKeyboardView!!.keyboard
        } else null

    // TODO: Remove this method. Come up with a more comprehensive way to reset the keyboard layout
    // when a keyboard layout set doesn't get reloaded in LatinIME.onStartInputViewInternal().
    fun resetKeyboardStateToAlphabet(
        currentAutoCapsState: Int,
        currentRecapitalizeState: Int
    ) {
        mState!!.onResetKeyboardStateToAlphabet(currentAutoCapsState, currentRecapitalizeState)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setAlphabetKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET, KeyboardSwitchState.OTHER)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setAlphabetManualShiftedKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetManualShiftedKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED, KeyboardSwitchState.OTHER)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setAlphabetAutomaticShiftedKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetAutomaticShiftedKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, KeyboardSwitchState.OTHER)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setAlphabetShiftLockedKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockedKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardSwitchState.OTHER)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setAlphabetShiftLockShiftedKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setAlphabetShiftLockShiftedKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED, KeyboardSwitchState.OTHER)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setSymbolsKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS, KeyboardSwitchState.OTHER)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setSymbolsShiftedKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setSymbolsShiftedKeyboard")
        }
        setKeyboard(KeyboardId.ELEMENT_SYMBOLS_SHIFTED, KeyboardSwitchState.SYMBOLS_SHIFTED)
    }

    fun isImeSuppressedByHardwareKeyboard(
        @Nonnull settingsValues: SettingsValues,
        @Nonnull toggleState: KeyboardSwitchState
    ): Boolean {
        return settingsValues.mHasHardwareKeyboard && toggleState == KeyboardSwitchState.HIDDEN
    }

    private fun setMainKeyboardFrame(
        @Nonnull settingsValues: SettingsValues,
        @Nonnull toggleState: KeyboardSwitchState
    ) {
        val visibility = if (isImeSuppressedByHardwareKeyboard(
                settingsValues,
                toggleState
            )
        ) View.GONE else View.VISIBLE
        mainKeyboardView!!.visibility = visibility
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mMainKeyboardFrame!!.visibility = visibility
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun setEmojiKeyboard() {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(TAG, "setEmojiKeyboard")
        }
        mMainKeyboardFrame!!.visibility = View.GONE
        // The visibility of {@link #mKeyboardView} must be aligned with {@link #MainKeyboardFrame}.
        // @see #getVisibleKeyboardView() and
        // @see LatinIME#onComputeInset(android.inputmethodservice.InputMethodService.Insets)
        mainKeyboardView!!.visibility = View.GONE
    }

    enum class KeyboardSwitchState(val mKeyboardId: Int) {
        HIDDEN(-1),
        SYMBOLS_SHIFTED(KeyboardId.ELEMENT_SYMBOLS_SHIFTED),
        EMOJI(KeyboardId.ELEMENT_EMOJI_RECENTS),
        OTHER(-1)
    }

    // Future method for requesting an updating to the shift state.
    override fun requestUpdatingShiftState(autoCapsFlags: Int, recapitalizeMode: Int) {
        if (SwitchActions.DEBUG_ACTION) {
            Log.d(
                TAG, "requestUpdatingShiftState: "
                        + " autoCapsFlags=" + CapsModeUtils.flagsToString(autoCapsFlags)
                        + " recapitalizeMode=" + RecapitalizeStatus.modeToString(recapitalizeMode)
            )
        }
        mState!!.onUpdateShiftState(autoCapsFlags, recapitalizeMode)
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun startDoubleTapShiftKeyTimer() {
        if (SwitchActions.DEBUG_TIMER_ACTION) {
            Log.d(TAG, "startDoubleTapShiftKeyTimer")
        }
        val keyboardView = mainKeyboardView
        keyboardView?.startDoubleTapShiftKeyTimer()
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun cancelDoubleTapShiftKeyTimer() {
        if (SwitchActions.DEBUG_TIMER_ACTION) {
            Log.d(TAG, "setAlphabetKeyboard")
        }
        val keyboardView = mainKeyboardView
        keyboardView?.cancelDoubleTapShiftKeyTimer()
    }

    // Implements {@link KeyboardState.SwitchActions}.
    override fun isInDoubleTapShiftKeyTimeout(): Boolean {
        if (SwitchActions.DEBUG_TIMER_ACTION) {
            Log.d(TAG, "isInDoubleTapShiftKeyTimeout")
        }
        val keyboardView = mainKeyboardView
        return keyboardView != null && keyboardView.isInDoubleTapShiftKeyTimeout
    }

    fun onCreateInputView(isHardwareAcceleratedDrawingEnabled: Boolean) {
        if (mainKeyboardView != null) {
            mainKeyboardView!!.closing()
        }
        updateKeyboardThemeAndContextThemeWrapper(
            superPenguinEngine!!.context,
            KeyboardTheme.getKeyboardTheme(superPenguinEngine!!.context /* context */)
        )
        mCurrentInputView = LayoutInflater.from(mThemeContext).inflate(
            R.layout.input_view_replace, null
        ) as InputView
        mMainKeyboardFrame = mCurrentInputView!!.findViewById(R.id.main_keyboard_frame_replace)
        mainKeyboardView = mCurrentInputView!!.findViewById(R.id.keyboard_view_place)
        mainKeyboardView!!.setHardwareAcceleratedDrawingEnabled(isHardwareAcceleratedDrawingEnabled)
    }

    val keyboardShiftMode: Int
        get() {
            val keyboard = keyboard ?: return WordComposer.CAPS_MODE_OFF
            return when (keyboard.mId.mElementId) {
                KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED, KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCK_SHIFTED -> WordComposer.CAPS_MODE_MANUAL_SHIFT_LOCKED
                KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED -> WordComposer.CAPS_MODE_MANUAL_SHIFTED
                KeyboardId.ELEMENT_ALPHABET_AUTOMATIC_SHIFTED -> WordComposer.CAPS_MODE_AUTO_SHIFTED
                else -> WordComposer.CAPS_MODE_OFF
            }
        }

    companion object {
        private val TAG = PenguinLanguageSwitcher::class.java.simpleName
        val instance = PenguinLanguageSwitcher()
        fun init(superPenguinEngine: SuperPenguinEngineImpl) {
            instance.initInternal(superPenguinEngine)
        }
    }
}
