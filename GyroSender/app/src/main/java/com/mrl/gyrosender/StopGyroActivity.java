package com.mrl.gyrosender;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class StopGyroActivity extends Activity
{

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        //setContentView(R.layout.activity_stop_gyro);
        Intent intent= new Intent(getBaseContext(), GyroService.class);
        stopService(intent);
        this.finish();
    }
}
