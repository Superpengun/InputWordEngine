/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.superpenguin.foreigninputandoutput.method;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.superpenguin.foreigninputandoutput.accessibility.AccessibilityUtils;
import com.superpenguin.foreigninputandoutput.keyboard.MainKeyboardView;
import com.superpenguin.foreigninputandoutput.R;

public final class InputView extends FrameLayout {
    private final Rect mInputViewRect = new Rect();
    private MainKeyboardView mMainKeyboardView;
    private KeyboardTopPaddingForwarder mKeyboardTopPaddingForwarder;
    private MoreSuggestionsViewCanceler mMoreSuggestionsViewCanceler;
    private MotionEventForwarder<?> mActiveForwarder;

    public InputView(final Context context, final AttributeSet attrs) {
        super(context, attrs, 0);
    }

    @Override
    protected void onFinishInflate() {
        mMainKeyboardView = (MainKeyboardView)findViewById(R.id.keyboard_view_place);
        mKeyboardTopPaddingForwarder = new KeyboardTopPaddingForwarder(
                mMainKeyboardView);
        mMoreSuggestionsViewCanceler = new MoreSuggestionsViewCanceler(
                mMainKeyboardView);
    }

    public void setKeyboardTopPadding(final int keyboardTopPadding) {
        mKeyboardTopPaddingForwarder.setKeyboardTopPadding(keyboardTopPadding);
    }

    @Override
    protected boolean dispatchHoverEvent(final MotionEvent event) {
        if (AccessibilityUtils.getInstance().isTouchExplorationEnabled()
                && mMainKeyboardView.isShowingMoreKeysPanel()) {
            // With accessibility mode on, discard hover events while a more keys keyboard is shown.
            // The {@link MoreKeysKeyboard} receives hover events directly from the platform.
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent me) {
        final Rect rect = mInputViewRect;
        getGlobalVisibleRect(rect);
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index) + rect.left;
        final int y = (int)me.getY(index) + rect.top;

        // The touch events that hit the top padding of keyboard should be forwarded to
        // {@link SuggestionStripView}.
        if (mKeyboardTopPaddingForwarder.onInterceptTouchEvent(x, y, me)) {
            mActiveForwarder = mKeyboardTopPaddingForwarder;
            return true;
        }

        // To cancel {@link MoreSuggestionsView}, we should intercept a touch event to
        // {@link MainKeyboardView} and dismiss the {@link MoreSuggestionsView}.
        if (mMoreSuggestionsViewCanceler.onInterceptTouchEvent(x, y, me)) {
            mActiveForwarder = mMoreSuggestionsViewCanceler;
            return true;
        }

        mActiveForwarder = null;
        return false;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent me) {
        if (mActiveForwarder == null) {
            return super.onTouchEvent(me);
        }

        final Rect rect = mInputViewRect;
        getGlobalVisibleRect(rect);
        final int index = me.getActionIndex();
        final int x = (int)me.getX(index) + rect.left;
        final int y = (int)me.getY(index) + rect.top;
        return mActiveForwarder.onTouchEvent(x, y, me);
    }

    /**
     * This class forwards series of {@link MotionEvent}s from <code>SenderView</code> to
     */
    private static abstract class
            MotionEventForwarder<SenderView extends View> {
        protected final SenderView mSenderView;
        protected final Rect mEventSendingRect = new Rect();
        protected final Rect mEventReceivingRect = new Rect();

        public MotionEventForwarder(final SenderView senderView) {
            mSenderView = senderView;
        }

        // Return true if a touch event of global coordinate x, y needs to be forwarded.
        protected abstract boolean needsToForward(final int x, final int y);

        // Translate global x-coordinate to <code>ReceiverView</code> local coordinate.
        protected int translateX(final int x) {
            return x - mEventReceivingRect.left;
        }

        // Translate global y-coordinate to <code>ReceiverView</code> local coordinate.
        protected int translateY(final int y) {
            return y - mEventReceivingRect.top;
        }

        // Returns true if a {@link MotionEvent} is needed to be forwarded to
        // <code>ReceiverView</code>. Otherwise returns false.
        public boolean onInterceptTouchEvent(final int x, final int y, final MotionEvent me) {
            // Forwards a {link MotionEvent} only if both <code>SenderView</code> and
            // <code>ReceiverView</code> are visible.
            if (mSenderView.getVisibility() != View.VISIBLE) {
                return false;
            }
            mSenderView.getGlobalVisibleRect(mEventSendingRect);
            if (!mEventSendingRect.contains(x, y)) {
                return false;
            }

            if (me.getActionMasked() == MotionEvent.ACTION_DOWN) {
                // If the down event happens in the forwarding area, successive
                // {@link MotionEvent}s should be forwarded to <code>ReceiverView</code>.
                return needsToForward(x, y);
            }

            return false;
        }

        // Returns true if a {@link MotionEvent} is forwarded to <code>ReceiverView</code>.
        // Otherwise returns false.
        public boolean onTouchEvent(final int x, final int y, final MotionEvent me) {
            // Translate global coordinates to <code>ReceiverView</code> local coordinates.
            me.setLocation(translateX(x), translateY(y));
            return true;
        }
    }

    /**
     * This class forwards {@link MotionEvent}s happened in the top padding of
     */
    private static class KeyboardTopPaddingForwarder
            extends MotionEventForwarder<MainKeyboardView> {
        private int mKeyboardTopPadding;

        public KeyboardTopPaddingForwarder(final MainKeyboardView mainKeyboardView) {
            super(mainKeyboardView);
        }

        public void setKeyboardTopPadding(final int keyboardTopPadding) {
            mKeyboardTopPadding = keyboardTopPadding;
        }

        private boolean isInKeyboardTopPadding(final int y) {
            return y < mEventSendingRect.top + mKeyboardTopPadding;
        }

        @Override
        protected boolean needsToForward(final int x, final int y) {
            // Forwarding an event only when {@link MainKeyboardView} is visible.
            // Because the visibility of {@link MainKeyboardView} is controlled by its parent
            // view in {@link KeyboardSwitcher#setMainKeyboardFrame()}, we should check the
            // visibility of the parent view.
            final View mainKeyboardFrame = (View)mSenderView.getParent();
            return mainKeyboardFrame.getVisibility() == View.VISIBLE && isInKeyboardTopPadding(y);
        }

        @Override
        protected int translateY(final int y) {
            final int translatedY = super.translateY(y);
            if (isInKeyboardTopPadding(y)) {
                // The forwarded event should have coordinates that are inside of the target.
                return Math.min(translatedY, mEventReceivingRect.height() - 1);
            }
            return translatedY;
        }
    }

    /**
     * This class forwards {@link MotionEvent}s happened in the {@link MainKeyboardView} to
     * outside of it.
     */
    private static class MoreSuggestionsViewCanceler
            extends MotionEventForwarder<MainKeyboardView> {
        public MoreSuggestionsViewCanceler(final MainKeyboardView mainKeyboardView
                ) {
            super(mainKeyboardView);
        }

        @Override
        protected boolean needsToForward(final int x, final int y) {
            return  mEventSendingRect.contains(x, y);
        }
    }
}
