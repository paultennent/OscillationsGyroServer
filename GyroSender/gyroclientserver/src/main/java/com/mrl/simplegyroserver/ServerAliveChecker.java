package com.mrl.simplegyroserver;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;

import com.mrl.simplegyroclient.GyroClientService;

public class ServerAliveChecker extends BroadcastReceiver
{

    @Override
    public void onReceive(Context context, Intent intent)
    {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "restartwakelock");
        wl.acquire();

        if(GyroServerService.sRunning && (GyroServerService.sConnectionState & 3) == 0)
        {
            Intent serviceIntent = new Intent(context, GyroServerService.class);
            context.stopService(serviceIntent);
            Intent serviceIntent2 = new Intent(context, GyroServerService.class);
            context.startService(serviceIntent2);
        }
        // run us again
        start(context);
        wl.release();
    }

    public static void start(Context context)
    {
        AlarmManager am =( AlarmManager)context.getSystemService(Context.ALARM_SERVICE);
        Intent i1= new Intent(context.getApplicationContext(), ServerAliveChecker.class);
        PendingIntent pi1 = PendingIntent.getBroadcast(context.getApplicationContext(), 0, i1, 0);

        Intent i2= new Intent(context.getApplicationContext(), ServerAliveChecker.class);
        PendingIntent pi2 = PendingIntent.getBroadcast(context.getApplicationContext(), 0, i2, 0);

//        am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+6,  pi); // Millisec * Second * Minute
        am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()+10000,  pi1); // Millisec * Second * Minute
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
        {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,System.currentTimeMillis()+15L*60L*1000L,pi2);
        }
    }
}
