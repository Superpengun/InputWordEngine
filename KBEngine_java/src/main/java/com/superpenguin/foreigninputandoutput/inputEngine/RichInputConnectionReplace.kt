/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.CharacterStyle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.superpenguin.foreigninputandoutput.method.NgramContext
import com.superpenguin.foreigninputandoutput.method.common.Constants
import com.superpenguin.foreigninputandoutput.method.common.StringUtils
import com.superpenguin.foreigninputandoutput.method.common.UnicodeSurrogate
import com.superpenguin.foreigninputandoutput.method.inputlogic.PrivateCommandPerformer
import com.superpenguin.foreigninputandoutput.method.settings.SpacingAndPunctuations
import com.superpenguin.foreigninputandoutput.utils.CapsModeUtils
import com.superpenguin.foreigninputandoutput.utils.DebugLogUtils
import com.superpenguin.foreigninputandoutput.utils.NgramContextUtils
import java.util.concurrent.TimeUnit
import javax.annotation.Nonnull

/**
 * Enrichment class for InputConnection to simplify interaction and add functionality.
 * This class serves as a wrapper to be able to simply add hooks to any calls to the underlying
 * InputConnection. It also keeps track of a number of things to avoid having to call upon IPC
 * all the time to find out what text is in the buffer, when we need it to determine caps mode
 * for example.
 */
class RichInputConnectionReplace : PrivateCommandPerformer {
    /**
     * This variable contains an expected value for the selection start position. This is where the
     * cursor or selection start may end up after all the keyboard-triggered updates have passed. We
     * keep this to compare it to the actual selection start to guess whether the move was caused by
     * a keyboard command or not.
     * It's not really the selection start position: the selection start may not be there yet, and
     * in some cases, it may never arrive there.
     */
    var expectedSelectionStart = INVALID_CURSOR_POSITION // in chars, not code points
        private set

    /**
     * The expected selection end.  Only differs from mExpectedSelStart if a non-empty selection is
     * expected.  The same caveats as mExpectedSelStart apply.
     */
    var expectedSelectionEnd = INVALID_CURSOR_POSITION // in chars, not code points
        private set

    /**
     * This contains the committed text immediately preceding the cursor and the composing
     * text, if any. It is refreshed when the cursor moves by calling upon the TextView.
     */
    private val mCommittedTextBeforeComposingText = StringBuilder()

    /**
     * This contains the currently composing text, as LatinIME thinks the TextView is seeing it.
     */
    private val mComposingText = StringBuilder()

    /**
     * This variable is a temporary object used in [.commitText]
     * to avoid object creation.
     */
    private val mTempObjectForCommitText = SpannableStringBuilder()
    private val mIC: InputConnection
    private var mNestLevel = 0

    /**
     * The timestamp of the last slow InputConnection operation
     */
    private var mLastSlowInputConnectionTime = -SLOW_INPUTCONNECTION_PERSIST_MS

    init {
        mIC = object : InputConnection {
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
                return null
            }

            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
                return null
            }

            override fun getSelectedText(flags: Int): CharSequence? {
                return null
            }

            override fun getCursorCapsMode(reqModes: Int): Int {
                return 0
            }

            override fun getExtractedText(
                request: ExtractedTextRequest,
                flags: Int
            ): ExtractedText? {
                return null
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                return false
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                return false
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                return false
            }

            override fun setComposingRegion(start: Int, end: Int): Boolean {
                return false
            }

            override fun finishComposingText(): Boolean {
                return false
            }

            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                return false
            }

            override fun commitCompletion(text: CompletionInfo): Boolean {
                return false
            }

            override fun commitCorrection(correctionInfo: CorrectionInfo): Boolean {
                return false
            }

            override fun setSelection(start: Int, end: Int): Boolean {
                return false
            }

            override fun performEditorAction(editorAction: Int): Boolean {
                return false
            }

            override fun performContextMenuAction(id: Int): Boolean {
                return false
            }

            override fun beginBatchEdit(): Boolean {
                return false
            }

            override fun endBatchEdit(): Boolean {
                return false
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return false
            }

            override fun clearMetaKeyStates(states: Int): Boolean {
                return false
            }

            override fun reportFullscreenMode(enabled: Boolean): Boolean {
                return false
            }

            override fun performPrivateCommand(action: String, data: Bundle): Boolean {
                return false
            }

            override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
                return false
            }

            override fun getHandler(): Handler? {
                return null
            }

            override fun closeConnection() {}
            override fun commitContent(
                inputContentInfo: InputContentInfo,
                flags: Int,
                opts: Bundle?
            ): Boolean {
                return false
            }
        }
    }

    private val isConnected: Boolean
        get() = true

    /**
     * Returns whether or not the underlying InputConnection is slow. When true, we want to avoid
     * calling InputConnection methods that trigger an IPC round-trip (e.g., getTextAfterCursor).
     */
    fun hasSlowInputConnection(): Boolean {
        return (SystemClock.uptimeMillis() - mLastSlowInputConnectionTime
                <= SLOW_INPUTCONNECTION_PERSIST_MS)
    }

    fun onStartInput() {
        mLastSlowInputConnectionTime = -SLOW_INPUTCONNECTION_PERSIST_MS
    }

    private fun checkConsistencyForDebug() {
        val r = ExtractedTextRequest()
        r.hintMaxChars = 0
        r.hintMaxLines = 0
        r.token = 1
        r.flags = 0
        val et = mIC.getExtractedText(r, 0)
        val beforeCursor = getTextBeforeCursor(
            Constants.EDITOR_CONTENTS_CACHE_SIZE,
            0
        )
        val internal = StringBuilder(mCommittedTextBeforeComposingText)
            .append(mComposingText)
        if (null == et || null == beforeCursor) return
        val actualLength = beforeCursor.length.coerceAtMost(internal.length)
        if (internal.length > actualLength) {
            internal.delete(0, internal.length - actualLength)
        }
    }

    fun beginBatchEdit() {
        if (++mNestLevel == 1) {
            if (isConnected) {
                mIC.beginBatchEdit()
            }
        } else {
            if (DBG) {
                throw RuntimeException("Nest level too deep")
            }
            Log.e(TAG, "Nest level too deep : $mNestLevel")
        }
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    fun endBatchEdit() {
        if (mNestLevel <= 0) Log.e(TAG, "Batch edit not in progress!") // TODO: exception instead
        if (--mNestLevel == 0 && isConnected) {
            mIC.endBatchEdit()
        }
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
    }

    /**
     * Reload the cached text from the InputConnection.
     *
     * @return true if successful
     */
    fun reloadTextCache(): Boolean {
        mCommittedTextBeforeComposingText.setLength(0)
        // Call upon the inputconnection directly since our own method is using the cache, and
        // we want to refresh it.
        val textBeforeCursor = getTextBeforeCursorAndDetectLaggyConnection(
            OPERATION_RELOAD_TEXT_CACHE,
            SLOW_INPUT_CONNECTION_ON_FULL_RELOAD_MS,
            Constants.EDITOR_CONTENTS_CACHE_SIZE,
            0 /* flags */
        )
        if (null == textBeforeCursor) {
            // For some reason the app thinks we are not connected to it. This looks like a
            // framework bug... Fall back to ground state and return false.
            expectedSelectionStart = INVALID_CURSOR_POSITION
            expectedSelectionEnd = INVALID_CURSOR_POSITION
            return false
        }
        mCommittedTextBeforeComposingText.append(textBeforeCursor)
        return true
    }

    private fun checkBatchEdit() {
        if (mNestLevel != 1) {
            // TODO: exception instead
            Log.e(TAG, "Batch edit level incorrect : $mNestLevel")
            Log.e(TAG, DebugLogUtils.getStackTrace(4))
        }
    }

    fun finishComposingText() {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        // TODO: this is not correct! The cursor is not necessarily after the composing text.
        // In the practice right now this is only called when input ends so it will be reset so
        // it works, but it's wrong and should be fixed.
        mCommittedTextBeforeComposingText.append(mComposingText)
        mComposingText.setLength(0)
        if (isConnected) {
            mIC.finishComposingText()
        }
    }

    /**
     * Calls [InputConnection.commitText].
     *
     * @param text The text to commit. This may include styles.
     * @param newCursorPosition The new cursor position around the text.
     */
    fun commitText(text: CharSequence, newCursorPosition: Int) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
        mCommittedTextBeforeComposingText.append(text)
        // TODO: the following is exceedingly error-prone. Right now when the cursor is in the
        // middle of the composing word mComposingText only holds the part of the composing text
        // that is before the cursor, so this actually works, but it's terribly confusing. Fix this.
        expectedSelectionStart += text.length - mComposingText.length
        expectedSelectionEnd = expectedSelectionStart
        mComposingText.setLength(0)
        if (isConnected) {
            mTempObjectForCommitText.clear()
            mTempObjectForCommitText.append(text)
            val spans = mTempObjectForCommitText.getSpans(
                0, text.length, CharacterStyle::class.java
            )
            for (span in spans) {
                val spanStart = mTempObjectForCommitText.getSpanStart(span)
                val spanEnd = mTempObjectForCommitText.getSpanEnd(span)
                val spanFlags = mTempObjectForCommitText.getSpanFlags(span)
                // We have to adjust the end of the span to include an additional character.
                // This is to avoid splitting a unicode surrogate pair.
                // See com.android.inputmethod.latin.common.Constants.UnicodeSurrogate
                // See https://b.corp.google.com/issues/19255233
                if (0 < spanEnd && spanEnd < mTempObjectForCommitText.length) {
                    val spanEndChar = mTempObjectForCommitText[spanEnd - 1]
                    val nextChar = mTempObjectForCommitText[spanEnd]
                    if (UnicodeSurrogate.isLowSurrogate(spanEndChar)
                        && UnicodeSurrogate.isHighSurrogate(nextChar)
                    ) {
                        mTempObjectForCommitText.setSpan(span, spanStart, spanEnd + 1, spanFlags)
                    }
                }
            }
            mIC.commitText(mTempObjectForCommitText, newCursorPosition)
        }
    }

    /**
     * Gets the caps modes we should be in after this specific string.
     * This returns a bit set of TextUtils#CAP_MODE_*, masked by the inputType argument.
     * This method also supports faking an additional space after the string passed in argument,
     * to support cases where a space will be added automatically, like in phantom space
     * state for example.
     * Note that for English, we are using American typography rules (which are not specific to
     * American English, it's just the most common set of rules for English).
     *
     * @param inputType a mask of the caps modes to test for.
     * @param spacingAndPunctuations the values of the settings to use for locale and separators.
     * @param hasSpaceBefore if we should consider there should be a space after the string.
     * @return the caps modes that should be on as a set of bits
     */
    fun getCursorCapsMode(
        inputType: Int,
        spacingAndPunctuations: SpacingAndPunctuations?, hasSpaceBefore: Boolean
    ): Int {
        if (!isConnected) {
            return Constants.TextUtils.CAP_MODE_OFF
        }
        if (!TextUtils.isEmpty(mComposingText)) {
            return if (hasSpaceBefore) {
                // If we have some composing text and a space before, then we should have
                // MODE_CHARACTERS and MODE_WORDS on.
                TextUtils.CAP_MODE_CHARACTERS or TextUtils.CAP_MODE_WORDS and inputType
            } else TextUtils.CAP_MODE_CHARACTERS and inputType
            // We have some composing text - we should be in MODE_CHARACTERS only.
        }
        // TODO: this will generally work, but there may be cases where the buffer contains SOME
        // information but not enough to determine the caps mode accurately. This may happen after
        // heavy pressing of delete, for example DEFAULT_TEXT_CACHE_SIZE - 5 times or so.
        // getCapsMode should be updated to be able to return a "not enough info" result so that
        // we can get more context only when needed.
        if (TextUtils.isEmpty(mCommittedTextBeforeComposingText) && 0 != expectedSelectionStart) {
            if (!reloadTextCache()) {
                Log.w(
                    TAG, "Unable to connect to the editor. "
                            + "Setting caps mode without knowing text."
                )
            }
        }
        // This never calls InputConnection#getCapsMode - in fact, it's a static method that
        // never blocks or initiates IPC.
        // TODO: don't call #toString() here. Instead, all accesses to
        // mCommittedTextBeforeComposingText should be done on the main thread.
        return CapsModeUtils.getCapsMode(
            mCommittedTextBeforeComposingText.toString(), inputType,
            spacingAndPunctuations, hasSpaceBefore
        )
    }

    private fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val cachedLength = mCommittedTextBeforeComposingText.length + mComposingText.length
        // If we have enough characters to satisfy the request, or if we have all characters in
        // the text field, then we can return the cached version right away.
        // However, if we don't have an expected cursor position, then we should always
        // go fetch the cache again (as it happens, INVALID_CURSOR_POSITION < 0, so we need to
        // test for this explicitly)
        if (INVALID_CURSOR_POSITION != expectedSelectionStart
            && (cachedLength >= n || cachedLength >= expectedSelectionStart)
        ) {
            val s = StringBuilder(mCommittedTextBeforeComposingText)
            // We call #toString() here to create a temporary object.
            // In some situations, this method is called on a worker thread, and it's possible
            // the main thread touches the contents of mComposingText while this worker thread
            // is suspended, because mComposingText is a StringBuilder. This may lead to crashes,
            // so we call #toString() on it. That will result in the return value being strictly
            // speaking wrong, but since this is used for basing bigram probability off, and
            // it's only going to matter for one getSuggestions call, it's fine in the practice.
            s.append(mComposingText)
            if (s.length > n) {
                s.delete(0, s.length - n)
            }
            return s
        }
        return getTextBeforeCursorAndDetectLaggyConnection(
            OPERATION_GET_TEXT_BEFORE_CURSOR,
            SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS,
            n, flags
        )
    }

    private fun getTextBeforeCursorAndDetectLaggyConnection(
        operation: Int, timeout: Long, n: Int, flags: Int
    ): CharSequence? {
        if (!isConnected) {
            return null
        }
        val startTime = SystemClock.uptimeMillis()
        val result = mIC.getTextBeforeCursor(n, flags)
        detectLaggyConnection(operation, timeout, startTime)
        return result
    }

    private fun detectLaggyConnection(operation: Int, timeout: Long, startTime: Long) {
        val duration = SystemClock.uptimeMillis() - startTime
        if (duration >= timeout) {
            val operationName = OPERATION_NAMES[operation]
            Log.w(TAG, "Slow InputConnection: $operationName took $duration ms.")
            mLastSlowInputConnectionTime = SystemClock.uptimeMillis()
        }
    }

    fun sendKeyEvent(keyEvent: KeyEvent) {
        if (DEBUG_BATCH_NESTING) checkBatchEdit()
        if (keyEvent.action == KeyEvent.ACTION_DOWN) {
            if (DEBUG_PREVIOUS_TEXT) checkConsistencyForDebug()
            when (keyEvent.keyCode) {
                KeyEvent.KEYCODE_ENTER -> {
                    mCommittedTextBeforeComposingText.append("\n")
                    expectedSelectionStart += 1
                    expectedSelectionEnd = expectedSelectionStart
                }

                KeyEvent.KEYCODE_DEL -> {
                    if (mComposingText.isEmpty()) {
                        if (mCommittedTextBeforeComposingText.isNotEmpty()) {
                            mCommittedTextBeforeComposingText.delete(
                                mCommittedTextBeforeComposingText.length - 1,
                                mCommittedTextBeforeComposingText.length
                            )
                        }
                    } else {
                        mComposingText.delete(mComposingText.length - 1, mComposingText.length)
                    }
                    if (expectedSelectionStart > 0 && expectedSelectionStart == expectedSelectionEnd) {
                        // TODO: Handle surrogate pairs.
                        expectedSelectionStart -= 1
                    }
                    expectedSelectionEnd = expectedSelectionStart
                }

                KeyEvent.KEYCODE_UNKNOWN -> if (null != keyEvent.characters) {
                    mCommittedTextBeforeComposingText.append(keyEvent.characters)
                    expectedSelectionStart += keyEvent.characters.length
                    expectedSelectionEnd = expectedSelectionStart
                }

                else -> {
                    val text = StringUtils.newSingleCodePointString(keyEvent.unicodeChar)
                    mCommittedTextBeforeComposingText.append(text)
                    expectedSelectionStart += text.length
                    expectedSelectionEnd = expectedSelectionStart
                }
            }
        }
        if (isConnected) {
            mIC.sendKeyEvent(keyEvent)
        }
    }

    @Suppress("unused")
    @Nonnull
    fun getNgramContextFromNthPreviousWord(
        spacingAndPunctuations: SpacingAndPunctuations?, n: Int
    ): NgramContext {
        if (!isConnected) {
            return NgramContext.EMPTY_PREV_WORDS_INFO
        }
        val prev = getTextBeforeCursor(NUM_CHARS_TO_GET_BEFORE_CURSOR, 0)
        if (DEBUG_PREVIOUS_TEXT && null != prev) {
            val checkLength = NUM_CHARS_TO_GET_BEFORE_CURSOR - 1
            if (prev.length <= checkLength) prev.toString() else prev.subSequence(
                prev.length - checkLength,
                prev.length
            ).toString()
            // TODO: right now the following works because mComposingText holds the part of the
            // composing text that is before the cursor, but this is very confusing. We should
            // fix it.
            val internal = StringBuilder()
                .append(mCommittedTextBeforeComposingText).append(mComposingText)
            if (internal.length > checkLength) {
                internal.delete(0, internal.length - checkLength)
            }
        }
        return NgramContextUtils.getNgramContextFromNthPreviousWord(
            prev, spacingAndPunctuations, n
        )
    }

    /**
     * Looks at the text just before the cursor to find out if it looks like a URL.
     * The weakest point here is, if we don't have enough text bufferized, we may fail to realize
     * we are in URL situation, but other places in this class have the same limitation and it
     * does not matter too much in the practice.
     */
    fun textBeforeCursorLooksLikeURL(): Boolean {
        return StringUtils.lastPartLooksLikeURL(mCommittedTextBeforeComposingText)
    }

    override fun performPrivateCommand(action: String, data: Bundle): Boolean {
        return if (!isConnected) {
            false
        } else mIC.performPrivateCommand(action, data)
    }

    companion object {
        private const val TAG = "RichInputConnectionReplace"
        private const val DBG = false
        private const val DEBUG_PREVIOUS_TEXT = false
        private const val DEBUG_BATCH_NESTING = false
        private const val NUM_CHARS_TO_GET_BEFORE_CURSOR = 40
        private const val INVALID_CURSOR_POSITION = -1

        /**
         * The amount of time a [.reloadTextCache] call needs to take for the keyboard to enter
         * the [.hasSlowInputConnection] state.
         */
        private const val SLOW_INPUT_CONNECTION_ON_FULL_RELOAD_MS: Long = 1000
        private const val SLOW_INPUT_CONNECTION_ON_PARTIAL_RELOAD_MS: Long = 200
        private const val OPERATION_GET_TEXT_BEFORE_CURSOR = 0
        private const val OPERATION_RELOAD_TEXT_CACHE = 3
        private val OPERATION_NAMES = arrayOf(
            "GET_TEXT_BEFORE_CURSOR",
            "GET_TEXT_AFTER_CURSOR",
            "GET_WORD_RANGE_AT_CURSOR",
            "RELOAD_TEXT_CACHE"
        )

        /**
         * The amount of time the keyboard will persist in the [.hasSlowInputConnection] state
         * after observing a slow InputConnection event.
         */
        private val SLOW_INPUTCONNECTION_PERSIST_MS = TimeUnit.MINUTES.toMillis(10)
    }
}
