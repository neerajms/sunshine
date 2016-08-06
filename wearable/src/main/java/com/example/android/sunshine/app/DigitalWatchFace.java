/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class DigitalWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        private final String LOW_KEY = "key_low";
        private final String HIGH_KEY = "key_high";
        private final String WEATHERID_KEY = "key_weatherid";
        private final String WEATHER_DATAMAP = "/weather";
        private final String WEATHER_REQUEST = "/weather_request";
        private final String ASSET_KEY = "key_asset";
        private final int TIMEOUT_MS = 1000;
        private final Handler mUpdateTimeHandler = new EngineHandler(this);

        private Bitmap mWeatherIcon;
        private Double mLowTemp;
        private Double mHighTemp;
        private Asset mAsset;
        private final String TAG = "CanvasWatchService:";

        private final String TIME_STAMP_KEY = "time_stamp";
        public final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        private boolean mRegisteredTimeZoneReceiver = false;

        private Paint mBackgroundPaint;
        private Paint mTextPaintNormal;
        private Paint mTextPaintBold;
        private Paint mTextPaintTemperature;
        private Paint mBitmapPaint;

        private boolean mAmbient;
        private Time mTime;
        private int mTapCount;

        private float mXOffsetHour;
        private float mXOffsetMinute;
        private float mYOffsetClock;
        private float mXOffsetLow;
        private float mXOffsetHigh;
        private float mYOffsetTemperature;
        private float mXOffsetBitmap;
        private float mYOffsetBitmap;
        private GoogleApiClient mGoogleApiClient;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        private boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(DigitalWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = DigitalWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaintNormal = new Paint();
            mTextPaintNormal = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE, 20.0f);

            mTextPaintBold = new Paint();
            mTextPaintBold = createTextPaint(resources.getColor(R.color.digital_text), BOLD_TYPEFACE, 20.0f);

            mTextPaintTemperature = new Paint();
            mTextPaintTemperature = createTextPaint(resources.getColor(R.color.digital_text), NORMAL_TYPEFACE, 40.0f);

            mBitmapPaint = new Paint();

            mTime = new Time();

            mGoogleApiClient = new GoogleApiClient.Builder(DigitalWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface, float textSize) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            paint.setTextSize(textSize);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();


                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();

                mGoogleApiClient.connect();
            } else {
                unregisterReceiver();
                if (mGoogleApiClient.isConnected() && mGoogleApiClient != null) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            DigitalWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            DigitalWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = DigitalWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            mXOffsetHour = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round_hour : R.dimen.digital_x_offset_hour);
            mXOffsetMinute = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round_minute : R.dimen.digital_x_offset_minute);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mXOffsetBitmap = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_bitmap_round : R.dimen.digital_x_offset_bitmap);
            mYOffsetBitmap = resources.getDimension(R.dimen.digital_y_offset_bitmap_round);
            mYOffsetClock = resources.getDimension(R.dimen.digital_y_offset_clock);
            mYOffsetTemperature = resources.getDimension(R.dimen.digital_y_offset_temperature_round);
            mXOffsetHigh = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_high_temperature_round : R.dimen.digital_x_offset_high_temperature);
            mXOffsetLow = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_low_temperature_round : R.dimen.digital_x_offset_low_temperature);

            mTextPaintNormal.setTextSize(textSize);
            mTextPaintBold.setTextSize(textSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintNormal.setAntiAlias(!inAmbientMode);
                    mTextPaintBold.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            mTime.setToNow();
            String textHour = String.format("%02d", mTime.hour);
            String textMinute = String.format(":%02d", mTime.minute);
            if (mLowTemp != null) {
                String textLowTemp = String.format("%.0f", mLowTemp) + (char) 0x00B0;
                canvas.drawText(textLowTemp, mXOffsetLow, mYOffsetTemperature, mTextPaintTemperature);
            }
            if (mHighTemp != null) {
                String textHighTemp = String.format("%.0f", mHighTemp) + (char) 0x00B0;
                canvas.drawText(textHighTemp, mXOffsetHigh, mYOffsetTemperature, mTextPaintTemperature);
            }
            if (mWeatherIcon != null && !isInAmbientMode()) {
                canvas.drawBitmap(mWeatherIcon, mXOffsetBitmap, mYOffsetBitmap, mBitmapPaint);
            }
            canvas.drawText(textHour, mXOffsetHour, mYOffsetClock, mTextPaintBold);
            canvas.drawText(textMinute, mXOffsetMinute, mYOffsetClock, mTextPaintNormal);
        }

        public Bitmap loadBitmapFromAsset(Asset asset) {
            if (asset == null) {
                throw new IllegalArgumentException("Asset must be non-null");
            }
            ConnectionResult result =
                    mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!result.isSuccess()) {
                return null;
            }
            // convert asset into a file descriptor and block until it's ready
            InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                    mGoogleApiClient, asset).await().getInputStream();

            if (assetInputStream == null) {
                Log.w(TAG, "Requested an unknown Asset.");
                return null;
            }
            // decode the stream into a bitmap
            return BitmapFactory.decodeStream(assetInputStream);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Wearable.DataApi.addListener(mGoogleApiClient, this);
            requestWeatherInfo();
        }

        public void requestWeatherInfo() {
            PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(WEATHER_REQUEST).setUrgent();
            putDataMapRequest.getDataMap().putLong(TIME_STAMP_KEY, System.currentTimeMillis());

            PutDataRequest putDataRequest = putDataMapRequest.asPutDataRequest();
            putDataRequest.setUrgent();

            Wearable.DataApi.putDataItem(mGoogleApiClient, putDataRequest)
                    .setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                        @Override
                        public void onResult(@NonNull DataApi.DataItemResult dataItemResult) {
                            if (dataItemResult.getStatus().isSuccess()) {
                                Log.d(TAG, "Data to phone sent");
                            } else {
                                Log.d(TAG, "Data to phone not sent");
                            }
                        }
                    });
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem dataItem = event.getDataItem();
                    if (dataItem.getUri().getPath().compareTo(WEATHER_DATAMAP) == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(dataItem).getDataMap();
                        if (dataMap.containsKey(LOW_KEY)) {
                            mLowTemp = dataMap.getDouble(LOW_KEY);
                        }
                        if (dataMap.containsKey(HIGH_KEY)) {
                            mHighTemp = dataMap.getDouble(HIGH_KEY);
                        }
                        if (dataMap.containsKey(ASSET_KEY)) {
                            mAsset = dataMap.getAsset(ASSET_KEY);
                            new GetBitmap().execute(mAsset);
                        }
                    }
                    invalidate();
                }
            }
        }

        public class GetBitmap extends AsyncTask<Asset, Void, Void> {

            @Override
            protected Void doInBackground(Asset... params) {
                Asset asset = params[0];
                float size = Float.valueOf(getResources().getDimension(R.dimen.weather_icon_size));
                mWeatherIcon = Bitmap.createScaledBitmap(loadBitmapFromAsset(asset), (int) size, (int) size, false);
                postInvalidate();
                return null;
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

        }
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<DigitalWatchFace.Engine> mWeakReference;

        public EngineHandler(DigitalWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            DigitalWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }
}