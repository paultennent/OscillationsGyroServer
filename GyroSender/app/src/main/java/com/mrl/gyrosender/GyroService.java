package com.mrl.gyrosender;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * Created by jqm on 07/11/2016.
 */

public class GyroService extends Service implements SensorEventListener
{
    final static int PACKET_SIZE=16;
    static public boolean bRunning =false;

    float angleIntegrated=0;
    long lastTimestamp=0L;


    Handler mSensorEventHandler;
    HandlerThread mSensorThread;

    public GyroService() {
        super();
    }


    protected UDPTransport mUDP;
    protected BluetoothTransport mBT;
    protected BLETransport mBLE;

    public void	onAccuracyChanged(Sensor sensor, int accuracy)
    {
    }

    boolean firstGyro =true;

    byte []sendBuffer=new byte[PACKET_SIZE];
    ByteBuffer sendBB=ByteBuffer.wrap(sendBuffer);

    int debugCount=0;
    int debugAccelCount=0;
    boolean updateAccelAngle=false;
    float accelAngle=0;
    float correctionAmount=0;
    public void onAccel(SensorEvent evt)
    {
        if(updateAccelAngle)
        {
            float calcAngle = (float)Math.atan2(-evt.values[0], evt.values[2]);
            if(Math.abs(calcAngle)<1.57)
            {
                accelAngle = calcAngle;
                correctionAmount=accelAngle-angleIntegrated;
                debugAccelCount++;
            }
        }
    }

    public void onGyro(float sensorY, long timestamp)
    {
        if(firstGyro)
        {
            lastTimestamp=timestamp;
            firstGyro =false;
            return;
        }
        // send this somewhere
        if(Math.abs(sensorY)<0.01)
        {
            updateAccelAngle=true;
        }else
        {
            updateAccelAngle=false;
        }
        long dt=timestamp-lastTimestamp;
        lastTimestamp=timestamp;
        float fDt=0.000000001f*(float)dt;
        angleIntegrated+=fDt*sensorY;
        if(angleIntegrated>Math.PI)
        {
            angleIntegrated-=2.0f*Math.PI;
        }
        if(angleIntegrated<-Math.PI)
        {
            angleIntegrated+=2.0f*Math.PI;
        }
        angleIntegrated+=correctionAmount*fDt;
        correctionAmount-=correctionAmount*fDt;
        sendBB.rewind();
        sendBB.putFloat(angleIntegrated*57.296f);
//        sendBB.putInt(4,0);
        sendBB.putLong(timestamp);

        mUDP.sendData(sendBuffer);
        mBT.sendData(sendBuffer);
        mBLE.sendData(sendBuffer);
        if((debugCount&63)==0)
        {
            Log.d("woo","angle:"+angleIntegrated+" dt:"+fDt+" accel:"+correctionAmount);
            debugAccelCount=0;
        }
        debugCount++;
    }

    public void	onSensorChanged(SensorEvent event)
    {
        if(event.sensor.getType()==Sensor.TYPE_GYROSCOPE)
        {
            onGyro(event.values[1],event.timestamp);
        }else
        {
            onAccel(event);
        }

    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate()
    {
        Log.d("woo","create");
        mSensorThread =new HandlerThread("gyrohandler")
        {
            public void onLooperPrepared()
            {
                startSensors();
            }
        };
        mSensorThread.start();
    }

    @Override
    public void onDestroy()
    {
        SensorManager sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        sm.unregisterListener(this);
        mSensorEventHandler.removeCallbacksAndMessages(null);
        mSensorThread.quitSafely();
        mUDP.shutdown();
        mBT.shutdown();
        mBLE.shutdown();

        NotificationManager nm=(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(1);
        Log.d("woo","service stop");
        bRunning =false;
    }

    @Override
    public int  onStartCommand(Intent intent,int flags,int startID)
    {
        bRunning =true;
        return START_STICKY;
    }

    public void setNotification(String text)
    {
        Notification.Builder builder=new Notification.Builder(this);
        builder.setContentTitle("Gyro service");
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentText(text+"\nTap to disconnect");
        Intent stopIntent = new Intent(this, StopGyroActivity.class);
        PendingIntent pi =
                PendingIntent.getActivity(this,0,stopIntent,0);
        builder.setContentIntent(pi);


        NotificationManager nm=(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1,builder.build());

    }

    public void startSensors()
    {
        Log.d("woo","startsensors");
        setNotification("Running");

        // start listening to the gyro and accelerometer
        mSensorEventHandler = new Handler();
        SensorManager sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        Sensor sensor=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sm.registerListener(this, sensor, 10, mSensorEventHandler);
//        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, mSensorEventHandler);
        Sensor sensor2=sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sm.registerListener(this, sensor2, 10,
                            mSensorEventHandler);
//        sm.registerListener(this, sensor2, SensorManager.SENSOR_DELAY_FASTEST,
//                            mSensorEventHandler);

        mUDP=new UDPTransport();
        mUDP.initSender(PACKET_SIZE,this);
        mBT=new BluetoothTransport();
        mBT.initSender(PACKET_SIZE,this);
        mBLE=new BLETransport();
        mBLE.initSender(PACKET_SIZE,this);

    }

}
