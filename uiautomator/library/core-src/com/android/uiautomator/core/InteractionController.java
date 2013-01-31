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

package com.android.uiautomator.core;

import android.app.UiAutomation;
import android.graphics.Point;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerCoords;
import android.view.MotionEvent.PointerProperties;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.util.Predicate;

import java.util.concurrent.TimeoutException;

/**
 * The InteractionProvider is responsible for injecting user events such as touch events
 * (includes swipes) and text key events into the system. To do so, all it needs to know about
 * are coordinates of the touch events and text for the text input events.
 * The InteractionController performs no synchronization. It will fire touch and text input events
 * as fast as it receives them. All idle synchronization is performed prior to querying the
 * hierarchy. See {@link QueryController}
 */
class InteractionController {

    private static final String LOG_TAG = InteractionController.class.getSimpleName();

    private static final boolean DEBUG = Log.isLoggable(LOG_TAG, Log.DEBUG);

    private static final long DEFAULT_SCROLL_EVENT_TIMEOUT_MILLIS = 500;

    private final KeyCharacterMap mKeyCharacterMap =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private final UiAutomatorBridge mUiAutomatorBridge;

    private static final long REGULAR_CLICK_LENGTH = 100;

    private long mDownTime;

    // Inserted after each motion event injection.
    private static final int MOTION_EVENT_INJECTION_DELAY_MILLIS = 5;

    public InteractionController(UiAutomatorBridge bridge) {
        mUiAutomatorBridge = bridge;
    }

    /**
     * Click at coordinates and blocks until the first specified accessibility event.
     *
     * All clicks will cause some UI change to occur. If the device is busy, this will
     * block until the device begins to process the click at which point the call returns
     * and normal wait for idle processing may begin. If no evens are detected for the
     * timeout period specified, the call will return anyway.
     * @param x
     * @param y
     * @param timeout
     * @param eventType is an {@link AccessibilityEvent} type
     * @return True if busy state is detected else false for timeout waiting for busy state
     */
    public boolean clickAndWaitForEvent(final int x, final int y, long timeout,
            final int eventType) {
        return clickAndWaitForEvents(x, y, timeout, false, eventType);
    }

    /**
     * Click at coordinates and blocks until the specified accessibility events. It is possible to
     * set the wait for all events to occur, in no specific order, or to the wait for any.
     *
     * @param x
     * @param y
     * @param timeout
     * @param waitForAll boolean to indicate whether to wait for any or all events
     * @param eventTypes mask
     * @return true if events are received, else false if timeout.
     */
    public boolean clickAndWaitForEvents(final int x, final int y, long timeout,
            boolean waitForAll, int eventTypes) {
        String logString = String.format("clickAndWaitForEvents(%d, %d, %d, %s, %d)", x, y, timeout,
                Boolean.toString(waitForAll), eventTypes);
        Log.d(LOG_TAG, logString);

        Runnable command = new Runnable() {
            @Override
            public void run() {
                if(touchDown(x, y)) {
                    SystemClock.sleep(REGULAR_CLICK_LENGTH);
                    touchUp(x, y);
                }
            }
        };
        return runAndWaitForEvents(command, timeout, waitForAll, eventTypes) != null;
    }

    /**
     * Runs a command and waits for a specific accessibility event.
     * @param command is a Runnable to execute before waiting for the event.
     * @param timeout
     * @param eventType
     * @return The AccessibilityEvent if one is received, otherwise null.
     */
    private AccessibilityEvent runAndWaitForEvent(Runnable command, long timeout, int eventType) {
        return runAndWaitForEvents(command, timeout, false, eventType);
    }

    /**
     * Runs a command and waits for accessibility events. It is possible to set the wait for all
     * events to occur at least once for each, or wait for any one to occur at least once.
     *
     * @param command
     * @param timeout
     * @param waitForAll boolean to indicate whether to wait for any or all events
     * @param eventTypesMask
     * @return The AccessibilityEvent if one is received, otherwise null.
     */
    private AccessibilityEvent runAndWaitForEvents(Runnable command, long timeout,
            final boolean waitForAll, final int eventTypesMask) {
        if (eventTypesMask == 0)
            throw new IllegalArgumentException("events mask cannot be zero");

        class EventPredicate implements Predicate<AccessibilityEvent> {
            int mMask;
            EventPredicate(int mask) {
                mMask = mask;
            }
            @Override
            public boolean apply(AccessibilityEvent t) {
                // check current event in the list
                if ((t.getEventType() & mMask) != 0) {
                    if (!waitForAll)
                        return true;

                    // remove from mask since this condition is satisfied
                    mMask &= ~t.getEventType();

                    // Since we're waiting for all events to be matched at least once
                    if (mMask != 0)
                        return false;

                    // all matched
                    return true;
                }
                // not one of our events
                return false;
            }
        }

        AccessibilityEvent event = null;
        try {
            event = mUiAutomatorBridge.executeCommandAndWaitForAccessibilityEvent(command,
                    new EventPredicate(eventTypesMask), timeout);
        } catch (TimeoutException e) {
            Log.w(LOG_TAG, "runAndwaitForEvent timedout waiting for events: " + eventTypesMask);
            return null;
        } catch (Exception e) {
            Log.e(LOG_TAG, "exception from executeCommandAndWaitForAccessibilityEvent", e);
            return null;
        }
        return event;
    }

    /**
     * Send keys and blocks until the first specified accessibility event.
     *
     * Most key presses will cause some UI change to occur. If the device is busy, this will
     * block until the device begins to process the key press at which point the call returns
     * and normal wait for idle processing may begin. If no events are detected for the
     * timeout period specified, the call will return anyway with false.
     *
     * @param keyCode
     * @param metaState
     * @param eventType
     * @param timeout
     * @return true if events is received, otherwise false.
     */
    public boolean sendKeyAndWaitForEvent(final int keyCode, final int metaState,
            final int eventType, long timeout) {
        Runnable command = new Runnable() {
            @Override
            public void run() {
                final long eventTime = SystemClock.uptimeMillis();
                KeyEvent downEvent = KeyEvent.obtain(eventTime, eventTime, KeyEvent.ACTION_DOWN,
                        keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                        InputDevice.SOURCE_KEYBOARD, null);
                if (injectEventSync(downEvent)) {
                    KeyEvent upEvent = KeyEvent.obtain(eventTime, eventTime, KeyEvent.ACTION_UP,
                            keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                            InputDevice.SOURCE_KEYBOARD, null);
                    injectEventSync(upEvent);
                }
            }
        };

        return runAndWaitForEvent(command, timeout, eventType) != null;
    }

    /**
     * Clicks at coordinates without waiting for device idle. This may be used for operations
     * that require stressing the target.
     * @param x
     * @param y
     * @return true if the click executed successfully
     */
    public boolean click(int x, int y) {
        Log.d(LOG_TAG, "click (" + x + ", " + y + ")");

        if (touchDown(x, y)) {
            SystemClock.sleep(REGULAR_CLICK_LENGTH);
            if (touchUp(x, y))
                return true;
        }
        return false;
    }

    /**
     * Clicks at coordinates and waits for for a TYPE_WINDOW_STATE_CHANGED event followed
     * by TYPE_WINDOW_CONTENT_CHANGED. If timeout occurs waiting for TYPE_WINDOW_STATE_CHANGED,
     * no further waits will be performed and the function returns.
     * @param x
     * @param y
     * @param timeout
     * @return true if both events occurred in the expected order
     */
    public boolean clickAndWaitForNewWindow(final int x, final int y, long timeout) {
        return (clickAndWaitForEvents(x, y, timeout, true,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED));
    }

    public boolean longTap(int x, int y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "longTap (" + x + ", " + y + ")");
        }

        if (touchDown(x, y)) {
            SystemClock.sleep(mUiAutomatorBridge.getSystemLongPressTime());
            if(touchUp(x, y)) {
                return true;
            }
        }
        return false;
    }

    private boolean touchDown(int x, int y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "touchDown (" + x + ", " + y + ")");
        }
        mDownTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
                mDownTime, mDownTime, MotionEvent.ACTION_DOWN, x, y, 1);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return injectEventSync(event);
    }

    private boolean touchUp(int x, int y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "touchUp (" + x + ", " + y + ")");
        }
        final long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
                mDownTime, eventTime, MotionEvent.ACTION_UP, x, y, 1);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        mDownTime = 0;
        return injectEventSync(event);
    }

    private boolean touchMove(int x, int y) {
        if (DEBUG) {
            Log.d(LOG_TAG, "touchMove (" + x + ", " + y + ")");
        }
        final long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
                mDownTime, eventTime, MotionEvent.ACTION_MOVE, x, y, 1);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return injectEventSync(event);
    }

    /**
     * Handle swipes in any direction where the result is a scroll event. This call blocks
     * until the UI has fired a scroll event or timeout.
     * @param downX
     * @param downY
     * @param upX
     * @param upY
     * @param steps
     * @return true if we are not at the beginning or end of the scrollable view.
     */
    public boolean scrollSwipe(final int downX, final int downY, final int upX, final int upY,
            final int steps) {
        Log.d(LOG_TAG, "scrollSwipe (" +  downX + ", " + downY + ", " + upX + ", "
                + upY + ", " + steps +")");

        Runnable command = new Runnable() {
            @Override
            public void run() {
                swipe(downX, downY, upX, upY, steps);
            }
        };

        AccessibilityEvent event = runAndWaitForEvent(command,
                DEFAULT_SCROLL_EVENT_TIMEOUT_MILLIS, AccessibilityEvent.TYPE_VIEW_SCROLLED);
        if (event == null) {
            return false;
        }
        // AdapterViews have indices we can use to check for the beginning.
        if (event.getFromIndex() != -1 && event.getToIndex() != -1 && event.getItemCount() != -1) {
            boolean foundEnd = event.getFromIndex() == 0 ||
                    (event.getItemCount() - 1) == event.getToIndex();
            Log.d(LOG_TAG, "scrollSwipe reached scroll end: " + foundEnd);
            return !foundEnd;
        } else if (event.getScrollX() != -1 && event.getScrollY() != -1) {
            // Determine if we are scrolling vertically or horizontally.
            if (downX == upX) {
                // Vertical
                boolean foundEnd = event.getScrollY() == 0 ||
                        event.getScrollY() == event.getMaxScrollY();
                Log.d(LOG_TAG, "Vertical scrollSwipe reached scroll end: " + foundEnd);
                return !foundEnd;
            } else if (downY == upY) {
                // Horizontal
                boolean foundEnd = event.getScrollX() == 0 ||
                        event.getScrollX() == event.getMaxScrollX();
                Log.d(LOG_TAG, "Horizontal scrollSwipe reached scroll end: " + foundEnd);
                return !foundEnd;
            }
        }
        return event != null;
    }

    /**
     * Handle swipes in any direction.
     * @param downX
     * @param downY
     * @param upX
     * @param upY
     * @param steps
     * @return true if the swipe executed successfully
     */
    public boolean swipe(int downX, int downY, int upX, int upY, int steps) {
        boolean ret = false;
        int swipeSteps = steps;
        double xStep = 0;
        double yStep = 0;

        // avoid a divide by zero
        if(swipeSteps == 0)
            swipeSteps = 1;

        xStep = ((double)(upX - downX)) / swipeSteps;
        yStep = ((double)(upY - downY)) / swipeSteps;

        // first touch starts exactly at the point requested
        ret = touchDown(downX, downY);
        for(int i = 1; i < swipeSteps; i++) {
            ret &= touchMove(downX + (int)(xStep * i), downY + (int)(yStep * i));
            if(ret == false)
                break;
            // set some known constant delay between steps as without it this
            // become completely dependent on the speed of the system and results
            // may vary on different devices. This guarantees at minimum we have
            // a preset delay.
            SystemClock.sleep(MOTION_EVENT_INJECTION_DELAY_MILLIS);
        }
        ret &= touchUp(upX, upY);
        return(ret);
    }

    /**
     * Performs a swipe between points in the Point array.
     * @param segments is Point array containing at least one Point object
     * @param segmentSteps steps to inject between two Points
     * @return true on success
     */
    public boolean swipe(Point[] segments, int segmentSteps) {
        boolean ret = false;
        int swipeSteps = segmentSteps;
        double xStep = 0;
        double yStep = 0;

        // avoid a divide by zero
        if(segmentSteps == 0)
            segmentSteps = 1;

        // must have some points
        if(segments.length == 0)
            return false;

        // first touch starts exactly at the point requested
        ret = touchDown(segments[0].x, segments[0].y);
        for(int seg = 0; seg < segments.length; seg++) {
            if(seg + 1 < segments.length) {

                xStep = ((double)(segments[seg+1].x - segments[seg].x)) / segmentSteps;
                yStep = ((double)(segments[seg+1].y - segments[seg].y)) / segmentSteps;

                for(int i = 1; i < swipeSteps; i++) {
                    ret &= touchMove(segments[seg].x + (int)(xStep * i),
                            segments[seg].y + (int)(yStep * i));
                    if(ret == false)
                        break;
                    // set some known constant delay between steps as without it this
                    // become completely dependent on the speed of the system and results
                    // may vary on different devices. This guarantees at minimum we have
                    // a preset delay.
                    SystemClock.sleep(MOTION_EVENT_INJECTION_DELAY_MILLIS);
                }
            }
        }
        ret &= touchUp(segments[segments.length - 1].x, segments[segments.length -1].y);
        return(ret);
    }


    public boolean sendText(String text) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendText (" + text + ")");
        }

        KeyEvent[] events = mKeyCharacterMap.getEvents(text.toCharArray());

        if (events != null) {
            for (KeyEvent event2 : events) {
                // We have to change the time of an event before injecting it because
                // all KeyEvents returned by KeyCharacterMap.getEvents() have the same
                // time stamp and the system rejects too old events. Hence, it is
                // possible for an event to become stale before it is injected if it
                // takes too long to inject the preceding ones.
                KeyEvent event = KeyEvent.changeTimeRepeat(event2,
                        SystemClock.uptimeMillis(), 0);
                if (!injectEventSync(event)) {
                    return false;
                }
            }
        }
        return true;
    }

    public boolean sendKey(int keyCode, int metaState) {
        if (DEBUG) {
            Log.d(LOG_TAG, "sendKey (" + keyCode + ", " + metaState + ")");
        }

        final long eventTime = SystemClock.uptimeMillis();
        KeyEvent downEvent = KeyEvent.obtain(eventTime, eventTime, KeyEvent.ACTION_DOWN,
                keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD, null);
        if (injectEventSync(downEvent)) {
            KeyEvent upEvent = KeyEvent.obtain(eventTime, eventTime, KeyEvent.ACTION_UP,
                    keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD, null);
            if(injectEventSync(upEvent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if the device is in its natural orientation. This is determined by
     * checking whether the orientation is at 0 or 180 degrees.
     * @return true if it is in natural orientation
     * @throws RemoteException
     */
    public boolean isNaturalRotation() throws RemoteException {
        int ret = mUiAutomatorBridge.getRotation();
        return ret == UiAutomation.ROTATION_FREEZE_0 || ret == UiAutomation.ROTATION_FREEZE_180;
    }

    /**
     * Rotates right and also freezes rotation in that position by
     * disabling the sensors. If you want to un-freeze the rotation
     * and re-enable the sensors see {@link #unfreezeRotation()}. Note
     * that doing so may cause the screen contents to rotate
     * depending on the current physical position of the test device.
     * @throws RemoteException
     */
    public void setRotationRight() {
        mUiAutomatorBridge.setRotation(UiAutomation.ROTATION_FREEZE_270);
    }

    /**
     * Rotates left and also freezes rotation in that position by
     * disabling the sensors. If you want to un-freeze the rotation
     * and re-enable the sensors see {@link #unfreezeRotation()}. Note
     * that doing so may cause the screen contents to rotate
     * depending on the current physical position of the test device.
     * @throws RemoteException
     */
    public void setRotationLeft() {
        mUiAutomatorBridge.setRotation(UiAutomation.ROTATION_FREEZE_90);
    }

    /**
     * Rotates up and also freezes rotation in that position by
     * disabling the sensors. If you want to un-freeze the rotation
     * and re-enable the sensors see {@link #unfreezeRotation()}. Note
     * that doing so may cause the screen contents to rotate
     * depending on the current physical position of the test device.
     * @throws RemoteException
     */
    public void setRotationNatural() {
        mUiAutomatorBridge.setRotation(UiAutomation.ROTATION_FREEZE_0);
    }

    /**
     * Disables the sensors and freezes the device rotation at its
     * current rotation state.
     * @throws RemoteException
     */
    public void freezeRotation() {
        mUiAutomatorBridge.setRotation(UiAutomation.ROTATION_FREEZE_CURRENT);
    }

    /**
     * Re-enables the sensors and un-freezes the device rotation
     * allowing its contents to rotate with the device physical rotation.
     * @throws RemoteException
     */
    public void unfreezeRotation() {
        mUiAutomatorBridge.setRotation(UiAutomation.ROTATION_UNFREEZE);
    }

    /**
     * This method simply presses the power button if the screen is OFF else
     * it does nothing if the screen is already ON.
     * @return true if the device was asleep else false
     * @throws RemoteException
     */
    public boolean wakeDevice() throws RemoteException {
        if(!isScreenOn()) {
            sendKey(KeyEvent.KEYCODE_POWER, 0);
            return true;
        }
        return false;
    }

    /**
     * This method simply presses the power button if the screen is ON else
     * it does nothing if the screen is already OFF.
     * @return true if the device was awake else false
     * @throws RemoteException
     */
    public boolean sleepDevice() throws RemoteException {
        if(isScreenOn()) {
            this.sendKey(KeyEvent.KEYCODE_POWER, 0);
            return true;
        }
        return false;
    }

    /**
     * Checks the power manager if the screen is ON
     * @return true if the screen is ON else false
     * @throws RemoteException
     */
    public boolean isScreenOn() throws RemoteException {
        return mUiAutomatorBridge.isScreenOn();
    }

    private boolean injectEventSync(InputEvent event) {
        return mUiAutomatorBridge.injectInputEvent(event, true);
    }

    private int getPointerAction(int motionEnvent, int index) {
        return motionEnvent + (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
    }

    /**
     * Performs a multi-touch gesture
     *
     * Takes a series of touch coordinates for at least 2 pointers. Each pointer must have
     * all of its touch steps defined in an array of {@link PointerCoords}. By having the ability
     * to specify the touch points along the path of a pointer, the caller is able to specify
     * complex gestures like circles, irregular shapes etc, where each pointer may take a
     * different path.
     *
     * To create a single point on a pointer's touch path
     * <code>
     *       PointerCoords p = new PointerCoords();
     *       p.x = stepX;
     *       p.y = stepY;
     *       p.pressure = 1;
     *       p.size = 1;
     * </code>
     * @param touches each array of {@link PointerCoords} constitute a single pointer's touch path.
     *        Multiple {@link PointerCoords} arrays constitute multiple pointers, each with its own
     *        path. Each {@link PointerCoords} in an array constitute a point on a pointer's path.
     * @since API Level 18
     */
    public void generateMultiPointerGesture(PointerCoords[] ... touches) {
        if (touches.length < 2) {
            throw new IllegalArgumentException("Must provide coordinates for at least 2 pointers");
        }

        // Get the pointer with the max steps to inject.
        int maxSteps = 0;
        for (int x = 0; x < touches.length; x++)
            maxSteps = (maxSteps < touches[x].length) ? touches[x].length : maxSteps;

        // specify the properties for each pointer as finger touch
        PointerProperties[] properties = new PointerProperties[touches.length];
        PointerCoords[] pointerCoords = new PointerCoords[touches.length];
        for (int x = 0; x < touches.length; x++) {
            PointerProperties prop = new PointerProperties();
            prop.id = x;
            prop.toolType = MotionEvent.TOOL_TYPE_FINGER;
            properties[x] = prop;

            // for each pointer set the first coordinates for touch down
            pointerCoords[x] = touches[x][0];
        }

        // Touch down all pointers
        long downTime = SystemClock.uptimeMillis();
        MotionEvent event;
        event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN, 1,
                properties, pointerCoords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        injectEventSync(event);

        for (int x = 1; x < touches.length; x++) {
            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    getPointerAction(MotionEvent.ACTION_POINTER_DOWN, x), x + 1, properties,
                    pointerCoords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            injectEventSync(event);
        }

        // Move all pointers
        for (int i = 1; i < maxSteps - 1; i++) {
            // for each pointer
            for (int x = 0; x < touches.length; x++) {
                // check if it has coordinates to move
                if (touches[x].length > i)
                    pointerCoords[x] = touches[x][i];
                else
                    pointerCoords[x] = touches[x][touches[x].length - 1];
            }

            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, touches.length, properties, pointerCoords, 0, 0, 1, 1,
                    0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);

            injectEventSync(event);
            SystemClock.sleep(MOTION_EVENT_INJECTION_DELAY_MILLIS);
        }

        // For each pointer get the last coordinates
        for (int x = 0; x < touches.length; x++)
            pointerCoords[x] = touches[x][touches[x].length - 1];

        // touch up
        for (int x = 1; x < touches.length; x++) {
            event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                    getPointerAction(MotionEvent.ACTION_POINTER_UP, x), x + 1, properties,
                    pointerCoords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
            injectEventSync(event);
        }

        Log.i(LOG_TAG, "x " + pointerCoords[0].x);
        // first to touch down is last up
        event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1,
                properties, pointerCoords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        injectEventSync(event);
    }
}
