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

    @Override
    public void onDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent dataEvent:dataEvents){
            if (dataEvent.getType() == DataEvent.TYPE_CHANGED){
                if (dataEvent.getDataItem().getUri().getPath().compareTo("/weather_request") == 0){
                    Log.d("WearListener:", "Ondatachanged");
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}
