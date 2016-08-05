package com.example.android.sunshine.app.wear;

import android.util.Log;

import com.example.android.sunshine.app.sync.SunshineSyncAdapter;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

/**
 * Created by neeraj on 5/8/16.
 */
public class SunshineWatchFaceListener extends WearableListenerService {

    private final String WEATHER_REQUEST = "/weather_request";
    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent dataEvent:dataEvents){
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED){
                if (dataEvent.getDataItem().getUri().getPath().compareTo(WEATHER_REQUEST) == 0){
                    Log.d("WearListener:", "Ondatachanged");
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}
