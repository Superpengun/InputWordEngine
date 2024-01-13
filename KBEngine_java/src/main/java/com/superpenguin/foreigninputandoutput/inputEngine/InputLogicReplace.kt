/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import com.superpenguin.foreigninputandoutput.event.Event
import com.superpenguin.foreigninputandoutput.event.InputTransaction
import com.superpenguin.foreigninputandoutput.keyboard.Keyboard
import com.superpenguin.foreigninputandoutput.method.Dictionary
import com.superpenguin.foreigninputandoutput.method.DictionaryFacilitator
import com.superpenguin.foreigninputandoutput.method.LastComposedWord
import com.superpenguin.foreigninputandoutput.method.NgramContext
import com.superpenguin.foreigninputandoutput.method.NgramContext.WordInfo
import com.superpenguin.foreigninputandoutput.method.Suggest
import com.superpenguin.foreigninputandoutput.method.Suggest.OnGetSuggestedWordsCallback
import com.superpenguin.foreigninputandoutput.method.SuggestedWords
import com.superpenguin.foreigninputandoutput.method.SuggestedWords.SuggestedWordInfo
import com.superpenguin.foreigninputandoutput.method.WordComposer
import com.superpenguin.foreigninputandoutput.method.common.Constants
import com.superpenguin.foreigninputandoutput.method.common.StringUtils
import com.superpenguin.foreigninputandoutput.method.define.DebugFlags
import com.superpenguin.foreigninputandoutput.method.inputlogic.SpaceState
import com.superpenguin.foreigninputandoutput.method.settings.SettingsValues
import com.superpenguin.foreigninputandoutput.method.settings.SettingsValuesForSuggestion
import com.superpenguin.foreigninputandoutput.method.settings.SpacingAndPunctuations
import com.superpenguin.foreigninputandoutput.utils.AsyncResultHolder
import com.superpenguin.foreigninputandoutput.utils.RecapitalizeStatus
import com.superpenguin.foreigninputandoutput.utils.StatsUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.TreeSet
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.annotation.Nonnull

/**
 * This class manages the input logic.
 */
class InputLogicReplace(
    private val superPenguinEngine: SuperPenguinEngineImpl,
    dictionaryFacilitator: DictionaryFacilitator
) {
    private val currentScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // TODO : make all these fields private as soon as possible.
    // Current space state of the input method. This can be any of the above constants.
    private var mSpaceState = 0
    private val ifShowTestTime = true

    // Never null
    private var mSuggestedWords = SuggestedWords.getEmptyInstance()
    val mSuggest: Suggest
    private val mDictionaryFacilitator: DictionaryFacilitator
    var mLastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD

    // This has package visibility so it can be accessed from InputLogicHandler.
    /* package */
    private val mWordComposer: WordComposer
    private val mConnection: RichInputConnectionReplace
    private val mRecapitalizeStatus =
        RecapitalizeStatus()
    private val mCurrentlyPressedHardwareKeys = TreeSet<Long>()

    init {
        mWordComposer = WordComposer()
        mConnection = RichInputConnectionReplace()
        mSuggest = Suggest(dictionaryFacilitator)
        mDictionaryFacilitator = dictionaryFacilitator
    }

    fun startInput(combiningSpec: String?) {
        mConnection.onStartInput()
        if (mWordComposer.typedWord.isNotEmpty()) {
            StatsUtils.onWordCommitUserTyped(
                mWordComposer.typedWord, mWordComposer.isBatchMode
            )
        }
        mWordComposer.restartCombining(combiningSpec)
        resetComposingState()
        mSpaceState = SpaceState.NONE
        mRecapitalizeStatus.disable() // Do not perform recapitalize until the cursor is moved once
        mCurrentlyPressedHardwareKeys.clear()
        mSuggestedWords = SuggestedWords.getEmptyInstance()
    }

    fun finishInput() {
        if (mWordComposer.isComposingWord) {
            mConnection.finishComposingText()
            StatsUtils.onWordCommitUserTyped(
                mWordComposer.typedWord, mWordComposer.isBatchMode
            )
        }
        resetComposingState()
    }

    fun recycle() {
        mDictionaryFacilitator.closeDictionaries()
    }

    fun originalAssociate(
        settingsValues: SettingsValues,
        word: String?, keyboardShiftState: Int
    ): ArrayList<String> {
        var startTimeMillis: Long = 0
        if (ifShowTestTime) {
            startTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "start originalAssociate")
        }
        val typedWordInfo = SuggestedWordInfo(
            word,
            "" /* prevWordsContext */, SuggestedWords.MAX_SUGGESTIONS + 1,
            SuggestedWordInfo.KIND_TYPED, Dictionary.DICTIONARY_USER_TYPED,
            SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
            SuggestedWordInfo.NOT_A_CONFIDENCE /* autoCommitFirstWordConfidence */
        )
        val event = Event.createSuggestionPickedEvent(typedWordInfo)
        val inputTransaction = InputTransaction(
            settingsValues,
            event, SystemClock.uptimeMillis(), mSpaceState, keyboardShiftState
        )
        // Manual pick affects the contents of the editor, so we take note of this. It's important
        // for the sequence of language switching.
        inputTransaction.setDidAffectContents()
        mConnection.beginBatchEdit()
        if (word != null) {
            if (// In the batch input mode, a manually picked suggested word should just replace
            // the current batch input text and there is no need for a phantom space.
                SpaceState.PHANTOM == mSpaceState && word.isNotEmpty() && !mWordComposer.isBatchMode
            ) {
                val firstChar = Character.codePointAt(word, 0)
                if (!settingsValues.isWordSeparator(firstChar)
                    || settingsValues.isUsuallyPrecededBySpace(firstChar)
                ) {
                    insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues)
                }
            }
        }
        if (word != null) {
            commitChosenWord(
                settingsValues, word
            )
        }
        mConnection.endBatchEdit()
        // Don't allow cancellation of manual pick
        mLastComposedWord.deactivate()
        // Space state must be updated before calling updateShiftState
        mSpaceState = SpaceState.PHANTOM
        val resultFuture = performUpdateSuggestionStripSync()
        if (ifShowTestTime) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(TAG, "originalAssociate() : $runTimeMillis ms to finish")
        }
        return try {
            resultFuture.join()
        } catch (e: Exception) {
            Log.d(TAG, "associateWord: 超时未获取返回空结果")
            e.printStackTrace() // 处理异常情况
            ArrayList() // 返回空列表或其他默认值
        }
    }

    private fun performAdditionToUserHistoryDictionary(
        settingsValues: SettingsValues,
        suggestion: String, @Nonnull ngramContext: NgramContext
    ) {
        // If correction is not enabled, we don't add words to the user history dictionary.
        // That's to avoid unintended additions in some sensitive fields, or fields that
        // expect to receive non-words.
        if (!settingsValues.mAutoCorrectionEnabledPerUserSettings) return
        if (mConnection.hasSlowInputConnection()) {
            // Since we don't unlearn when the user backspaces on a slow InputConnection,
            // turn off learning to guard against adding typos that the user later deletes.
            Log.w(TAG, "Skipping learning due to slow InputConnection.")
            return
        }
        if (TextUtils.isEmpty(suggestion)) return
        val wasAutoCapitalized = mWordComposer.wasAutoCapitalized() && !mWordComposer.isMostlyCaps
        val timeStampInSeconds = TimeUnit.MILLISECONDS.toSeconds(
            System.currentTimeMillis()
        ).toInt()
        mDictionaryFacilitator.addToUserHistory(
            suggestion, wasAutoCapitalized,
            ngramContext, timeStampInSeconds.toLong(), settingsValues.mBlockPotentiallyOffensive
        )
    }

    private fun performUpdateSuggestionStripSync(): CompletableFuture<ArrayList<String>> {
        var startTimeMillis: Long = 0
        if (DebugFlags.DEBUG_ENABLED) {
            startTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "performUpdateSuggestionStripSync()")
        }
        val resultList = ArrayList<String>() // 新建一个ArrayList用于保存结果
        val resultFuture = CompletableFuture<ArrayList<String>>()
        val holder =
            AsyncResultHolder<SuggestedWords?>(
                "Suggest"
            )
        superPenguinEngine.getSuggestedWords(
            0, SuggestedWords.NOT_A_SEQUENCE_NUMBER
        ) { suggestedWords: SuggestedWords ->
            val typedWordString = mWordComposer.typedWord
            val typedWordInfo = SuggestedWordInfo(
                typedWordString, "" /* prevWordsContext */,
                SuggestedWordInfo.MAX_SCORE,
                SuggestedWordInfo.KIND_TYPED, Dictionary.DICTIONARY_USER_TYPED,
                SuggestedWordInfo.NOT_AN_INDEX /* indexOfTouchPointOfSecondWord */,
                SuggestedWordInfo.NOT_A_CONFIDENCE
            )
            for (i in suggestedWords.mSuggestedWordInfoList.indices) {
                if (suggestedWords.mSuggestedWordInfoList[i].toString().contains(" ")) continue
                resultList.add(suggestedWords.mSuggestedWordInfoList[i].toString())
            }
            resultFuture.complete(resultList)
        }
        mConnection.reloadTextCache()
        // This line may cause the current thread to wait.
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(TAG, "performUpdateSuggestionStripSync() : $runTimeMillis ms to finish")
        }
        return resultFuture
    }

    fun originalQuery(word: String?): CompletableFuture<ArrayList<String>> {
        var startTimeMillis: Long = 0
        if (ifShowTestTime) {
            startTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "start originalQuery")
        }
        val codePoints = StringUtils.toCodePointArray(
            word!!
        )
        mWordComposer.setComposingWord(
            codePoints,
            superPenguinEngine.getCoordinatesForCurrentKeyboard(codePoints)
        )
        val resultList = ArrayList<String>() // 新建一个ArrayList用于保存结果
        val resultFuture = CompletableFuture<ArrayList<String>>()
        superPenguinEngine.getSuggestedWords(
            Suggest.SESSION_ID_TYPING,
            SuggestedWords.NOT_A_SEQUENCE_NUMBER
        ) { suggestedWords: SuggestedWords ->
            for (i in suggestedWords.mSuggestedWordInfoList.indices) {
                if (suggestedWords.mSuggestedWordInfoList[i].toString().contains(" ")) continue
                resultList.add(suggestedWords.mSuggestedWordInfoList[i].toString())
            }
            resultFuture.complete(resultList)
        }
        if (ifShowTestTime) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(TAG, "originalQuery() : $runTimeMillis ms to finish")
        }
        return resultFuture
    }

    private fun getActualCapsMode(
        settingsValues: SettingsValues,
        keyboardShiftMode: Int
    ): Int {
        if (keyboardShiftMode != WordComposer.CAPS_MODE_AUTO_SHIFTED) {
            return keyboardShiftMode
        }
        val auto = getCurrentAutoCapsState(settingsValues)
        if (0 != auto and TextUtils.CAP_MODE_CHARACTERS) {
            return WordComposer.CAPS_MODE_AUTO_SHIFT_LOCKED
        }
        return if (0 != auto) {
            WordComposer.CAPS_MODE_AUTO_SHIFTED
        } else WordComposer.CAPS_MODE_OFF
    }

    fun getCurrentAutoCapsState(settingsValues: SettingsValues): Int {
        if (!settingsValues.mAutoCap) return Constants.TextUtils.CAP_MODE_OFF
        val ei = currentInputEditorInfo
            ?: return Constants.TextUtils.CAP_MODE_OFF
        val inputType = ei.inputType
        // Warning: this depends on mSpaceState, which may not be the most current value. If
        // mSpaceState gets updated later, whoever called this may need to be told about it.
        return mConnection.getCursorCapsMode(
            inputType, settingsValues.mSpacingAndPunctuations,
            SpaceState.PHANTOM == mSpaceState
        )
    }

    val currentRecapitalizeState: Int
        get() = if (!mRecapitalizeStatus.isStarted
            || !mRecapitalizeStatus.isSetAt(
                mConnection.expectedSelectionStart,
                mConnection.expectedSelectionEnd
            )
        ) {
            // Not recapitalizing at the moment
            RecapitalizeStatus.NOT_A_RECAPITALIZE_MODE
        } else mRecapitalizeStatus.currentMode
    private val currentInputEditorInfo: EditorInfo?
        /**
         * @return the editor info for the current editor
         */
        get() = null

    private fun getNgramContextFromNthPreviousWordForSuggestion(
        spacingAndPunctuations: SpacingAndPunctuations, nthPreviousWord: Int
    ): NgramContext {
        if (spacingAndPunctuations.mCurrentLanguageHasSpaces) {
            // If we are typing in a language with spaces we can just look up the previous
            // word information from textview.
            return mConnection.getNgramContextFromNthPreviousWord(
                spacingAndPunctuations, nthPreviousWord
            )
        }
        return if (LastComposedWord.NOT_A_COMPOSED_WORD == mLastComposedWord) {
            NgramContext.BEGINNING_OF_SENTENCE
        } else NgramContext(
            WordInfo(
                mLastComposedWord.mCommittedWord.toString()
            )
        )
    }

    /**
     * Resets only the composing state.
     *
     *
     * Compare #resetEntireInputState, which also clears the suggestion strip and resets the
     * input connection caches. This only deals with the composing state.
     */
    private fun resetComposingState() {
        mWordComposer.reset()
        mLastComposedWord = LastComposedWord.NOT_A_COMPOSED_WORD
    }

    /**
     * Sends a DOWN key event followed by an UP key event to the editor.
     *
     *
     * If possible at all, avoid using this method. It causes all sorts of race conditions with
     * the text view because it goes through a different, asynchronous binder. Also, batch edits
     * are ignored for key events. Use the normal software input methods instead.
     */
    private fun sendDownUpKeyEvent() {
        val eventTime = SystemClock.uptimeMillis()
        mConnection.sendKeyEvent(
            KeyEvent(
                eventTime, eventTime,
                KeyEvent.ACTION_DOWN, -9, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
        mConnection.sendKeyEvent(
            KeyEvent(
                SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, -9, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE
            )
        )
    }

    /**
     * Sends a code point to the editor, using the most appropriate method.
     *
     *
     * Normally we send code points with commitText, but there are some cases (where backward
     * compatibility is a concern for example) where we want to use deprecated methods.
     *
     */
    // TODO: replace these two parameters with an InputTransaction
    private fun sendKeyCodePoint() {
        // TODO: Remove this special handling of digit letters.
        // For backward compatibility. See {@link InputMethodService#sendKeyChar(char)}.
        if (Constants.CODE_SPACE >= '0'.code && Constants.CODE_SPACE <= '9'.code) {
            sendDownUpKeyEvent()
            return
        }

        // TODO: we should do this also when the editor has TYPE_NULL
        mConnection.commitText(StringUtils.newSingleCodePointString(Constants.CODE_SPACE), 1)
    }

    /**
     * Insert an automatic space, if the options allow it.
     *
     *
     * This checks the options and the text before the cursor are appropriate before inserting
     * an automatic space.
     *
     * @param settingsValues the current values of the settings.
     */
    private fun insertAutomaticSpaceIfOptionsAndTextAllow(settingsValues: SettingsValues) {
        if (settingsValues.shouldInsertSpacesAutomatically()
            && settingsValues.mSpacingAndPunctuations.mCurrentLanguageHasSpaces
            && !mConnection.textBeforeCursorLooksLikeURL()
        ) {
            sendKeyCodePoint()
        }
    }

    /**
     * Commits the chosen word to the text field and saves it for later retrieval.
     *
     * @param settingsValues the current values of the settings.
     * @param chosenWord     the word we want to commit.
     */
    private fun commitChosenWord(settingsValues: SettingsValues, chosenWord: String) {
        var startTimeMillis: Long = 0
        if (DebugFlags.DEBUG_ENABLED) {
            startTimeMillis = System.currentTimeMillis()
            Log.d(TAG, "commitChosenWord() : [$chosenWord]")
        }
        // TODO: Locale should be determined based on context and the text given.
        // b/21926256
        //                suggestedWords, locale);
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(
                TAG, "commitChosenWord() : " + runTimeMillis + " ms to run "
                        + "SuggestionSpanUtils.getTextWithSuggestionSpan()"
            )
            startTimeMillis = System.currentTimeMillis()
        }
        // When we are composing word, get n-gram context from the 2nd previous word because the
        // 1st previous word is the word to be committed. Otherwise get n-gram context from the 1st
        // previous word.
        val ngramContext = mConnection.getNgramContextFromNthPreviousWord(
            settingsValues.mSpacingAndPunctuations, if (mWordComposer.isComposingWord) 2 else 1
        )
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(
                TAG, "commitChosenWord() : " + runTimeMillis + " ms to run "
                        + "Connection.getNgramContextFromNthPreviousWord()"
            )
            Log.d(TAG, "commitChosenWord() : NgramContext = $ngramContext")
            startTimeMillis = System.currentTimeMillis()
        }
        mConnection.commitText(chosenWord, 1)
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(
                TAG, "commitChosenWord() : " + runTimeMillis + " ms to run "
                        + "Connection.commitText"
            )
            startTimeMillis = System.currentTimeMillis()
        }
        // Add the word to the user history dictionary
        performAdditionToUserHistoryDictionary(settingsValues, chosenWord, ngramContext)
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(
                TAG, "commitChosenWord() : " + runTimeMillis + " ms to run "
                        + "performAdditionToUserHistoryDictionary()"
            )
            startTimeMillis = System.currentTimeMillis()
        }
        // TODO: figure out here if this is an auto-correct or if the best word is actually
        // what user typed. Note: currently this is done much later in
        // LastComposedWord#didCommitTypedWord by string equality of the remembered
        // strings.
        mLastComposedWord = mWordComposer.commitWord(
            LastComposedWord.COMMIT_TYPE_MANUAL_PICK,
            chosenWord, LastComposedWord.NOT_A_SEPARATOR, ngramContext
        )
        if (DebugFlags.DEBUG_ENABLED) {
            val runTimeMillis = System.currentTimeMillis() - startTimeMillis
            Log.d(
                TAG, "commitChosenWord() : " + runTimeMillis + " ms to run "
                        + "WordComposer.commitWord()"
            )
        }
    }

    /*
   inputStyle为0，sequenceNumber为-1，keyboardShiftMode为0（默认小写）
     */
    fun getSuggestedWords(
        settingsValues: SettingsValues,
        keyboard: Keyboard?, keyboardShiftMode: Int, inputStyle: Int,
        sequenceNumber: Int, callback: OnGetSuggestedWordsCallback?
    ) {
        mWordComposer.adviseCapitalizedModeBeforeFetchingSuggestions(
            getActualCapsMode(settingsValues, keyboardShiftMode)
        )
        mSuggest.getSuggestedWords(
            mWordComposer,
            getNgramContextFromNthPreviousWordForSuggestion(
                settingsValues.mSpacingAndPunctuations,  // Get the word on which we should search the bigrams. If we are composing
                // a word, it's whatever is *before* the half-committed word in the buffer,
                // hence 2; if we aren't, we should just skip whitespace if any, so 1.
                if (mWordComposer.isComposingWord) 2 else 1
            ),
            keyboard,
            SettingsValuesForSuggestion(settingsValues.mBlockPotentiallyOffensive),
            settingsValues.mAutoCorrectionEnabledPerUserSettings,
            inputStyle, sequenceNumber, callback
        )
    }

    companion object {
        private val TAG = InputLogicReplace::class.java.simpleName

    }
}
