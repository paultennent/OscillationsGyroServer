package com.mrl.flashcamerasource;

import android.content.Context;
import android.content.Intent;

/**
 * Created by jqm on 06/03/2017.
 */

public class ClientWifiSelector
{

    public boolean SelectNetworkForBarcode(Context ctx, String code)
    {
        try
        {
            // drop the last (check) digit
            String withoutCheck = code.substring(0, code.length() - 1);
            int value = (int) (Long.parseLong(withoutCheck) % (100000000));
            if(value > 1000000)
            {
                // connect to the right wifi network - this codescheme allows for 100 wifis and any number of swings
                int wifiNum = (value - 1000000) % (1000);
                int swingID = (value - 1000000) / 1000;
                // send this wifi and swing ID to the service
                Intent setSwingIntent = new Intent();
                setSwingIntent.putExtra("WIFI_NUM", wifiNum);
                setSwingIntent.putExtra("SWING_ID", swingID);
                setSwingIntent.setClassName("com.mrl.simplegyroclient","com.mrl.simplegyroclient.GyroClientService");
                // start service will start the service if not running, otherwise will just set settings
                // based on this intent (startservice gets called each time the intent is fired)
                ctx.startService(setSwingIntent);
                return true;
            }
        } catch(NumberFormatException e)
        {
            e.printStackTrace();
        }
        return false;
    }

};
