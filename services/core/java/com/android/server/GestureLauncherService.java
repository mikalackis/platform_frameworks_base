/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server;

import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.MutableBoolean;
import android.util.Slog;
import android.view.KeyEvent;
import android.util.Log;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.server.statusbar.StatusBarManagerInternal;

import ariel.providers.ArielSettings;

/**
 * The service that listens for gestures detected in sensor firmware and starts the intent
 * accordingly.
 * <p>For now, only camera launch gesture is supported, and in the future, more gestures can be
 * added.</p>
 * @hide
 */
public class GestureLauncherService extends SystemService {
    private static final boolean DBG = false;
    private static final String TAG = "GestureLauncherService";

    /**
     * Time in milliseconds in which the power button must be pressed twice so it will be considered
     * as a camera launch.
     */
    private static final long CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS = 300;
    private static final long CAMERA_POWER_DOUBLE_TAP_MIN_TIME_MS = 120;

    /** The listener that receives the gesture event. */
    private final GestureEventListener mGestureListener = new GestureEventListener();

    private Sensor mCameraLaunchSensor;
    private Context mContext;

    /** The wake lock held when a gesture is detected. */
    private WakeLock mWakeLock;
    private boolean mRegistered;
    private int mUserId;

    // Below are fields used for event logging only.
    /** Elapsed real time when the camera gesture is turned on. */
    private long mCameraGestureOnTimeMs = 0L;

    /** Elapsed real time when the last camera gesture was detected. */
    private long mCameraGestureLastEventTime = 0L;

    /**
     * How long the sensor 1 has been turned on since camera launch sensor was
     * subscribed to and when the last camera launch gesture was detected.
     * <p>Sensor 1 is the main sensor used to detect camera launch gesture.</p>
     */
    private long mCameraGestureSensor1LastOnTimeMs = 0L;

    /**
     * If applicable, how long the sensor 2 has been turned on since camera
     * launch sensor was subscribed to and when the last camera launch
     * gesture was detected.
     * <p>Sensor 2 is the secondary sensor used to detect camera launch gesture.
     * This is optional and if only sensor 1 is used for detect camera launch
     * gesture, this value would always be 0.</p>
     */
    private long mCameraGestureSensor2LastOnTimeMs = 0L;

    /**
     * Extra information about the event when the last camera launch gesture
     * was detected.
     */
    private int mCameraLaunchLastEventExtra = 0;

    /**
     * Whether camera double tap power button gesture is currently enabled;
     */
    private boolean mCameraDoubleTapPowerEnabled;
    private long mLastPowerDown;

    private static final int MSG_POWER_DELAYED_PRESS = 20;
    private static final int CAMERA_COUNT = 2;
    private static final int ARIEL_PANIC_MODE_COUNT = 5;

    private static final long DEFAULT_MULTI_PRESS_TIMEOUT = 300;

    private boolean mLaunched = false;
    private boolean mIntercept = false;
    private int mNumberOfTaps;
    private Handler mHandler = new PolicyHandler();
    private long mDoubleTapInterval;

    public GestureLauncherService(Context context) {
        super(context);
        mContext = context;
    }

    public void onStart() {
        LocalServices.addService(GestureLauncherService.class, this);
    }

    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            Resources resources = mContext.getResources();
            if (!isGestureLauncherEnabled(resources)) {
                if (DBG) Slog.d(TAG, "Gesture launcher is disabled in system properties.");
                return;
            }

            PowerManager powerManager = (PowerManager) mContext.getSystemService(
                    Context.POWER_SERVICE);
            mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "GestureLauncherService");
            updateCameraRegistered();
            updateCameraDoubleTapPowerEnabled();

            mUserId = ActivityManager.getCurrentUser();
            mContext.registerReceiver(mUserReceiver, new IntentFilter(Intent.ACTION_USER_SWITCHED));
            registerContentObservers();
        }
    }

    private void registerContentObservers() {
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.CAMERA_GESTURE_DISABLED),
                false, mSettingObserver, mUserId);
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED),
                false, mSettingObserver, mUserId);
    }

    private void updateCameraRegistered() {
        Resources resources = mContext.getResources();
        if (isCameraLaunchSettingEnabled(mContext, mUserId)) {
            registerCameraLaunchGesture(resources);
        } else {
            unregisterCameraLaunchGesture();
        }
    }

    private void updateCameraDoubleTapPowerEnabled() {
        boolean enabled = isCameraDoubleTapPowerSettingEnabled(mContext, mUserId);
        synchronized (this) {
            mCameraDoubleTapPowerEnabled = enabled;
        }
    }

    private void unregisterCameraLaunchGesture() {
        if (mRegistered) {
            mRegistered = false;
            mCameraGestureOnTimeMs = 0L;
            mCameraGestureLastEventTime = 0L;
            mCameraGestureSensor1LastOnTimeMs = 0;
            mCameraGestureSensor2LastOnTimeMs = 0;
            mCameraLaunchLastEventExtra = 0;

            SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                    Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(mGestureListener);
        }
    }

    /**
     * Registers for the camera launch gesture.
     */
    private void registerCameraLaunchGesture(Resources resources) {
        if (mRegistered) {
            return;
        }
        mCameraGestureOnTimeMs = SystemClock.elapsedRealtime();
        mCameraGestureLastEventTime = mCameraGestureOnTimeMs;
        SensorManager sensorManager = (SensorManager) mContext.getSystemService(
                Context.SENSOR_SERVICE);
        int cameraLaunchGestureId = resources.getInteger(
                com.android.internal.R.integer.config_cameraLaunchGestureSensorType);
        if (cameraLaunchGestureId != -1) {
            mRegistered = false;
            String sensorName = resources.getString(
                    com.android.internal.R.string.config_cameraLaunchGestureSensorStringType);
            mCameraLaunchSensor = sensorManager.getDefaultSensor(
                    cameraLaunchGestureId,
                    true /*wakeUp*/);

            // Compare the camera gesture string type to that in the resource file to make
            // sure we are registering the correct sensor. This is redundant check, it
            // makes the code more robust.
            if (mCameraLaunchSensor != null) {
                if (sensorName.equals(mCameraLaunchSensor.getStringType())) {
                    mRegistered = sensorManager.registerListener(mGestureListener,
                            mCameraLaunchSensor, 0);
                } else {
                    String message = String.format("Wrong configuration. Sensor type and sensor "
                                    + "string type don't match: %s in resources, %s in the sensor.",
                            sensorName, mCameraLaunchSensor.getStringType());
                    throw new RuntimeException(message);
                }
            }
            if (DBG) Slog.d(TAG, "Camera launch sensor registered: " + mRegistered);
        } else {
            if (DBG) Slog.d(TAG, "Camera launch sensor is not specified.");
        }
    }

    public static boolean isCameraLaunchSettingEnabled(Context context, int userId) {
        return isCameraLaunchEnabled(context.getResources())
                && (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.CAMERA_GESTURE_DISABLED, 0, userId) == 0);
    }

    public static boolean isCameraDoubleTapPowerSettingEnabled(Context context, int userId) {
        return isCameraDoubleTapPowerEnabled(context.getResources())
                && (Settings.Secure.getIntForUser(context.getContentResolver(),
                Settings.Secure.CAMERA_DOUBLE_TAP_POWER_GESTURE_DISABLED, 0, userId) == 0);
    }

    /**
     * Whether to enable the camera launch gesture.
     */
    public static boolean isCameraLaunchEnabled(Resources resources) {
        boolean configSet = resources.getInteger(
                com.android.internal.R.integer.config_cameraLaunchGestureSensorType) != -1;
        return configSet &&
                !SystemProperties.getBoolean("gesture.disable_camera_launch", false);
    }

    public static boolean isCameraDoubleTapPowerEnabled(Resources resources) {
        return resources.getBoolean(
                com.android.internal.R.bool.config_cameraDoubleTapPowerGestureEnabled);
    }

    /**
     * Whether GestureLauncherService should be enabled according to system properties.
     */
    public static boolean isGestureLauncherEnabled(Resources resources) {
        return isCameraLaunchEnabled(resources) || isCameraDoubleTapPowerEnabled(resources);
    }

    public boolean interceptPowerKeyDown(KeyEvent event, boolean interactive,
                                         MutableBoolean outLaunched) {
        synchronized (this) {
            // remove any pending messages because there is a new tap
            // that can change everything
            if (DBG) Slog.d(TAG, "Removing pending messages");
            mHandler.removeMessages(MSG_POWER_DELAYED_PRESS);

            mDoubleTapInterval = event.getEventTime() - mLastPowerDown;
            if (mCameraDoubleTapPowerEnabled
                    && mDoubleTapInterval < CAMERA_POWER_DOUBLE_TAP_MAX_TIME_MS
                    && mDoubleTapInterval > CAMERA_POWER_DOUBLE_TAP_MIN_TIME_MS) {
                if (DBG) Slog.d(TAG, "Tap detected in interval");
                mLaunched = true;
                mIntercept = interactive;
                mNumberOfTaps += 1;
                // this could be a multitap, try to process it
                if (DBG) Slog.d(TAG, "Register action check");
                Message msg = mHandler.obtainMessage(MSG_POWER_DELAYED_PRESS,
                        mNumberOfTaps, 0, event.getEventTime());
                msg.setAsynchronous(true);
                mHandler.sendMessageDelayed(msg, DEFAULT_MULTI_PRESS_TIMEOUT);
            }
            else{
                if (DBG) Slog.d(TAG, "New tapping session");
                mNumberOfTaps = 1;
                mLaunched = false;
                mIntercept = false;
            }
            mLastPowerDown = event.getEventTime();
        }
        if (DBG) Slog.d(TAG, "Return value: "+(mIntercept && mLaunched));
        outLaunched.value = mLaunched;
        return mIntercept && mLaunched;
    }

    /**
     * @return true if camera was launched, false otherwise.
     */
    private boolean handleCameraLaunchGesture(boolean useWakelock, int source) {
        boolean userSetupComplete = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;
        if (!userSetupComplete) {
            if (DBG) Slog.d(TAG, String.format(
                    "userSetupComplete = %s, ignoring camera launch gesture.",
                    userSetupComplete));
            return false;
        }
        if (DBG) Slog.d(TAG, String.format(
                "userSetupComplete = %s, performing camera launch gesture.",
                userSetupComplete));

        if (useWakelock) {
            // Make sure we don't sleep too early
            mWakeLock.acquire(500L);
        }
        StatusBarManagerInternal service = LocalServices.getService(
                StatusBarManagerInternal.class);
        service.onCameraLaunchGestureDetected(source);
        return true;
    }

    private final BroadcastReceiver mUserReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                mUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
                registerContentObservers();
                updateCameraRegistered();
                updateCameraDoubleTapPowerEnabled();
            }
        }
    };

    private final ContentObserver mSettingObserver = new ContentObserver(new Handler()) {
        public void onChange(boolean selfChange, android.net.Uri uri, int userId) {
            if (userId == mUserId) {
                updateCameraRegistered();
                updateCameraDoubleTapPowerEnabled();
            }
        }
    };

    private final class GestureEventListener implements SensorEventListener {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (!mRegistered) {
                if (DBG) Slog.d(TAG, "Ignoring gesture event because it's unregistered.");
                return;
            }
            if (event.sensor == mCameraLaunchSensor) {
                if (DBG) {
                    float[] values = event.values;
                    Slog.d(TAG, String.format("Received a camera launch event: " +
                            "values=[%.4f, %.4f, %.4f].", values[0], values[1], values[2]));
                }
                if (handleCameraLaunchGesture(true /* useWakelock */,
                        StatusBarManager.CAMERA_LAUNCH_SOURCE_WIGGLE)) {
                    MetricsLogger.action(mContext, MetricsEvent.ACTION_WIGGLE_CAMERA_GESTURE);
                    trackCameraLaunchEvent(event);
                }
                return;
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Ignored.
        }

        private void trackCameraLaunchEvent(SensorEvent event) {
            long now = SystemClock.elapsedRealtime();
            long totalDuration = now - mCameraGestureOnTimeMs;
            // values[0]: ratio between total time duration when accel is turned on and time
            //            duration since camera launch gesture is subscribed.
            // values[1]: ratio between total time duration when gyro is turned on and time duration
            //            since camera launch gesture is subscribed.
            // values[2]: extra information
            float[] values = event.values;

            long sensor1OnTime = (long) (totalDuration * (double) values[0]);
            long sensor2OnTime = (long) (totalDuration * (double) values[1]);
            int extra = (int) values[2];

            // We only log the difference in the event log to make aggregation easier.
            long gestureOnTimeDiff = now - mCameraGestureLastEventTime;
            long sensor1OnTimeDiff = sensor1OnTime - mCameraGestureSensor1LastOnTimeMs;
            long sensor2OnTimeDiff = sensor2OnTime - mCameraGestureSensor2LastOnTimeMs;
            int extraDiff = extra - mCameraLaunchLastEventExtra;

            // Gating against negative time difference. This doesn't usually happen, but it may
            // happen because of numeric errors.
            if (gestureOnTimeDiff < 0 || sensor1OnTimeDiff < 0 || sensor2OnTimeDiff < 0) {
                if (DBG) Slog.d(TAG, "Skipped event logging because negative numbers.");
                return;
            }

            if (DBG) Slog.d(TAG, String.format("totalDuration: %d, sensor1OnTime: %s, " +
                            "sensor2OnTime: %d, extra: %d",
                    gestureOnTimeDiff,
                    sensor1OnTimeDiff,
                    sensor2OnTimeDiff,
                    extraDiff));
            EventLogTags.writeCameraGestureTriggered(
                    gestureOnTimeDiff,
                    sensor1OnTimeDiff,
                    sensor2OnTimeDiff,
                    extraDiff);

            mCameraGestureLastEventTime = now;
            mCameraGestureSensor1LastOnTimeMs = sensor1OnTime;
            mCameraGestureSensor2LastOnTimeMs = sensor2OnTime;
            mCameraLaunchLastEventExtra = extra;
        }
    }

    private class PolicyHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_POWER_DELAYED_PRESS:
                    backMultiPressAction((Long) msg.obj, msg.arg1);
                    finishBackKeyPress();
                    break;
            }
        }
    }

    private void backMultiPressAction(long eventTime, int count) {
        if(count == CAMERA_COUNT){
            // start camera
            Slog.i(TAG, "Power button double tap gesture detected, launching camera");
            mLaunched = handleCameraLaunchGesture(false /* useWakelock */,
                    StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP);
            if (mLaunched) {
                MetricsLogger.action(mContext, MetricsEvent.ACTION_DOUBLE_TAP_POWER_CAMERA_GESTURE,
                        (int) mDoubleTapInterval);
            }
        }
        else if(count >= ARIEL_PANIC_MODE_COUNT){
            Slog.i(TAG, "Ariel panic mode power button count detected!");
            // activate panic
            ArielSettings.Secure.putInt(mContext.getContentResolver(),
                    ArielSettings.Secure.ARIEL_SYSTEM_STATUS,
                    ArielSettings.Secure.ARIEL_SYSTEM_STATUS_PANIC);
        }
    }

    private void finishBackKeyPress() {
        mNumberOfTaps = 0;
        mLaunched = false;
        mIntercept = false;
    }
}