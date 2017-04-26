package com.mrl.boxupload;

import android.app.IntentService;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.os.IBinder;
import android.support.annotation.IntDef;
import android.support.annotation.Nullable;

public class BoxUploadService extends Service implements BoxConnector.Callback
{
    BoxConnector mConnector;

    @Override
    public void onCreate()
    {
        super.onCreate();
        mConnector=new BoxConnector(this,this);
        mConnector.connectBox();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        // upload files (in a thread)
        mConnector.checkUploadFiles();
        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    @Override
    public void onStatusChange()
    {

    }
};
