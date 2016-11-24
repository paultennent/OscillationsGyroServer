package com.mrl.simplegyroserver;

import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.Enumeration;
import java.util.UUID;

public class GyroServerService extends Service implements SensorEventListener
{
    static final boolean USE_ROTATION_VECTOR=true;

    static final String PREFS_NAME="launcherPrefs";
    public static String mBluetoothMAC;

    final static public int UDP_PORT=2323;
    final static public int PACKET_SIZE=24;
    final static public UUID BLUETOOTH_UUID =
            UUID.fromString("ad91f8d4-e6ea-4f57-be70-4f9802ebc619");
    public static boolean sRunning=false;
    public static int sConnectionState=0;
    public static float sAngleDebug=0;
    public static float sCorrectionAmountDebug=0;

    boolean mShuttingDown=false;

    HandlerThread mSensorThread;
    Handler mSensorEventHandler;

    byte[] mDataBytes=new byte[PACKET_SIZE];
    ByteBuffer dataByteBuffer =ByteBuffer.wrap(mDataBytes);

    public GyroServerService()
    {
    }

    @Override
    public void onCreate()
    {
        sRunning=true;
        Log.d("woo", "create");
        mSensorThread =new HandlerThread("gyrohandler")
        {
            public void onLooperPrepared()
            {
                mSensorEventHandler=new Handler(getLooper());
                initSensorThread();
            }
        };
        mSensorThread.start();
    }

    @Override
    public void onDestroy()
    {
        mShuttingDown=true;
        SensorManager sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        sm.unregisterListener(this);
        mSensorEventHandler.removeCallbacksAndMessages(null);
        mSensorEventHandler.post(new Runnable(){
            public void run()
            {
                // close UDP and bluetooth on sensor thread
                closeUDP();
                closeBluetooth();
            }
        });
        mSensorThread.quitSafely();
        try
        {
            mSensorThread.join(200);
        } catch(InterruptedException e)
        {
            e.printStackTrace();
        }

        NotificationManager nm=(NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(1);
        Log.d("woo","service stop");
        sRunning=false;
    }


    @Override
    public IBinder onBind(Intent intent)
    {
        throw new UnsupportedOperationException("Not yet implemented");
    }


    public void initSensorThread()
    {

        // connect to sensors
        SensorManager sm=(SensorManager)getSystemService(SENSOR_SERVICE);
        Sensor sensor=sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor sensor2;
        if(USE_ROTATION_VECTOR)
        {
            sensor2 = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }else
        {
            sensor2 = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        Sensor sensor3=sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);

        sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, mSensorEventHandler);
        sm.registerListener(this, sensor2, SensorManager.SENSOR_DELAY_FASTEST, mSensorEventHandler);
        sm.registerListener(this, sensor3, SensorManager.SENSOR_DELAY_UI, mSensorEventHandler);
        Log.d("woo","connected sensors");

        mFirstTime=true;

        openBluetooth();
        Log.d("woo","open BT");
        openUDP();
        Log.d("woo","open UDP");


    }

    //************** BLUETOOTH STUFF *****************/

    BluetoothAdapter mBTAdapter;
    BluetoothSocket mBTSocket;
    InputStream mBTIn;
    OutputStream mBTOut;

    class BTServerConnector extends Thread
    {
        public BluetoothSocket mConnectedSocket=null;
        private BluetoothServerSocket mBTServerSocket;

        public void run()
        {
            try
            {
                mBTServerSocket = mBTAdapter.listenUsingInsecureRfcommWithServiceRecord("Gyro service",BLUETOOTH_UUID);
                mConnectedSocket=mBTServerSocket.accept();
                if(mBTServerSocket!=null)
                {
                    mBTServerSocket.close();
                }
            } catch(IOException e)
            {
                e.printStackTrace();
                try
                {
                    if(mBTServerSocket!=null)
                    {
                        mBTServerSocket.close();
                    }
                } catch(IOException e1)
                {
                    e1.printStackTrace();
                }
                mBTServerSocket=null;
            }
        }

        public void shutdownAcceptThread()
        {
            BluetoothServerSocket sock=mBTServerSocket;
            mBTServerSocket=null;
            if(sock!=null)
            {
                try
                {
                    sock.close();
                } catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
            if(Thread.currentThread()!=BTServerConnector.this && isAlive())
            {
                try
                {
                    join(100);
                } catch(InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    BTServerConnector mBTServerConnector=null;

    void openBluetooth()
    {
        // open adapter - everything else happens in listen
        BluetoothManager mg= (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBTAdapter=mg.getAdapter();
    }

    void closeBluetooth()
    {
        if(mBTServerConnector!=null)
        {
            mBTServerConnector.shutdownAcceptThread();
            try
            {
                mBTServerConnector.join(100);
            } catch(InterruptedException e)
            {
            }
        }
        mBTAdapter=null;
        if(mBTIn!=null)
        {
            try
            {
                mBTIn.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            mBTIn=null;
        }
        if(mBTOut!=null)
        {
            try
            {
                mBTOut.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            mBTOut=null;
        }
        if(mBTSocket!=null)
        {
            try
            {
                mBTSocket.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            mBTSocket=null;
        }
    }

    void listenBluetooth()
    {
        if(mBTSocket!=null && mBTSocket.isConnected())
        {
            sConnectionState |= 0x2;
        }else
        {
            sConnectionState&=0xfd;
        }

        // if we've become disconnected, then close the connection
        if(mBTSocket!=null && !mBTSocket.isConnected())
        {
            try
            {
                mBTSocket.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            mBTSocket=null;
        }
        // if we're not connected, then listen for new connections
        if(mBTSocket==null)
        {
            if(mBTServerConnector == null)
            {
                mBTServerConnector = new BTServerConnector();
                mBTServerConnector.start();
            } else
            {
                if(!mBTServerConnector.isAlive())
                {
                    mBTSocket = mBTServerConnector.mConnectedSocket;
                    if(mBTSocket != null)
                    {
                        try
                        {
                            mBTOut = mBTSocket.getOutputStream();
                            mBTIn = mBTSocket.getInputStream();
                            Log.d("bt", "connected");
                        } catch(IOException e)
                        {
                            e.printStackTrace();
                        }
                    }
                    mBTServerConnector = null;
                }
            }
        }
    }

    void sendBluetooth()
    {
        if(mBTSocket!=null && mBTSocket.isConnected())
        {
            try
            {
                mBTOut.write(mDataBytes);
            } catch(IOException e)
            {
                try
                {
                    mBTOut.close();
                } catch(IOException e1)
                {
                    e1.printStackTrace();
                }
                try
                {
                    mBTSocket.close();
                } catch(IOException e1)
                {
                    e1.printStackTrace();
                }
                mBTSocket=null;
                mBTOut=null;
                Log.d("bt","error sending");
            }
        }
    }

    /*****************UDP STUFF *********************************/
    DatagramChannel mUDPConnection;
    SocketAddress mUDPTarget;
    ByteBuffer dataRead=ByteBuffer.allocateDirect(4);
    long mUDPLastPingTime=0;

    void openUDP()
    {
        // open udp non-blocking listener
        try
        {
            mUDPConnection=DatagramChannel.open();
            mUDPConnection.configureBlocking(false);
            mUDPConnection.socket().bind(new InetSocketAddress(UDP_PORT));
        } catch(IOException e)
        {
            e.printStackTrace();
        }
    }

    void closeUDP()
    {
        if(mUDPConnection!=null)
        {
            try
            {
                mUDPConnection.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
        mUDPConnection=null;
    }

    void listenUDP()
    {
        try
        {
            SocketAddress addr=mUDPConnection.receive(dataRead);
            if(dataRead.get(0)==72 && dataRead.get(1)==105)
            {
                mUDPTarget = addr;
                mUDPLastPingTime = System.currentTimeMillis();
            }
        } catch(IOException e)
        {
            // timeout, ignore
        }
        // stop sending UDP if >10s of no pings (saves having a 'bye' packet
        if(mUDPTarget!=null && System.currentTimeMillis()-mUDPLastPingTime>10000L)
        {
            mUDPTarget=null;
        }
        if(mUDPTarget==null)
        {
            sConnectionState&=0xfe;
        }else
        {
            sConnectionState|=0x1;
        }

    }

    void sendUDP()
    {
        if(mUDPTarget!=null)
        {
            try
            {
                int dataLen=mUDPConnection.send(dataByteBuffer, mUDPTarget);
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    /******************Sensor processing here (calls to network code above)***********************/

    boolean mFirstTime=true;
    long mTimestamp;
    long mLastTimestamp;
    long mLastSendTimestamp;
    long mLastListenTimestamp;
    long mLastGyroTimestamp;
    long mLastBatteryTimestamp;

    float mAngle=0;
    float mAngularVelocity=0;
    float mBattery=0.5f;
    float mMagDirection=0;
    boolean mUpdateAccelAngle =false;
    float mAccelCorrectionAmount =0;

    float[] mAccelLast=new float[3];
    float[] mRotationMatrix=new float[9];
    float[] mOrientation=new float[3];
    float mAccelMagnitudeLast=0f;

    public void onGyroData(SensorEvent event)
    {
        float sensorY=event.values[0];
        mAngularVelocity=sensorY;
        // send this somewhere
        if(Math.abs(sensorY)<0.01)
        {
            mUpdateAccelAngle=true;
        }else
        {
            mUpdateAccelAngle=false;
        }
        long dt=mTimestamp-mLastGyroTimestamp;
        float fDt=0.000000001f*(float)dt;
        mAngle+=fDt*sensorY;
        if(mAngle>Math.PI)
        {
            mAngle-=2.0f*Math.PI;
        }
        if(mAngle<-Math.PI)
        {
            mAngle+=2.0f*Math.PI;
        }
        if(mAccelCorrectionAmount>0)
        {
            float correctAmt=Math.min(0.04f*fDt,mAccelCorrectionAmount);
            mAngle+=correctAmt;
            mAccelCorrectionAmount-=correctAmt;
        }else if(mAccelCorrectionAmount<0)
        {
            float correctAmt=Math.max(-0.04f*fDt,mAccelCorrectionAmount);
            mAngle+=correctAmt;
            mAccelCorrectionAmount-=correctAmt;
        }
        mLastGyroTimestamp=mTimestamp;
        sAngleDebug=mAngle;
        //sCorrectionAmountDebug=mAccelCorrectionAmount;
    }

    public void onMagData(SensorEvent event)
    {
        SensorManager.getRotationMatrix(mRotationMatrix,null,mAccelLast,event.values);
        SensorManager.getOrientation(mRotationMatrix,mOrientation);
        mMagDirection=mOrientation[0]*57.296f;
    }

    int mAccelDirection=0;


    float []mMagnitudeBuffer=new float[1024];
    float []mMagnitudeAngleBuffer=new float[1024];
    int mMagnitudeBufferPos=0;


    float mAccelMax=0;
    float mAccelMin=0;
    float mAngleAtMax =0;

    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME );

    public void onRotationVectorData(SensorEvent event)
    {
        SensorManager.getRotationMatrixFromVector(mRotationMatrix,event.values);
        SensorManager.getOrientation(mRotationMatrix,mOrientation);
        // TODO maybe get mag from here?
        float roll=-mOrientation[1];
        mAccelCorrectionAmount=roll-mAngle;
        sCorrectionAmountDebug=mAccelCorrectionAmount;
        //mAngle=0.998f*mAngle+0.002f*roll;
    }

    public void onAccelData(SensorEvent event)
    {
        float magnitude=(float)Math.sqrt(event.values[0]*event.values[0]+event.values[1]*event.values[1]+event.values[2]*event.values[2]);
        if(mMagnitudeBufferPos==0)
        {
            mAccelMax=magnitude;
            mAccelMin=magnitude;
            mAngleAtMax=0;
        }
        mMagnitudeBuffer[mMagnitudeBufferPos]=magnitude;
        mMagnitudeAngleBuffer[mMagnitudeBufferPos]=mAngle;
        mMagnitudeBufferPos++;
        if(mMagnitudeBufferPos==1024)
        {
            if(mAccelMax-mAccelMin>0.5)
            {
                // we're swinging!
                Log.d("found max",mAngleAtMax*57.296+":"+mAccelMax+":"+mAccelMin);
                mAccelCorrectionAmount=-mAngleAtMax;
            }else
            {
                Log.d("no max",mAngleAtMax*57.296+":"+mAccelMax+":"+mAccelMin);
            }
            mMagnitudeBufferPos=0;
        }
        if(magnitude>mAccelMax)
        {
            mAccelMax=magnitude;
            mAngleAtMax=mAngle;
        }
        if(magnitude<mAccelMin)
        {
            mAccelMin=magnitude;
        }


        // update angle based on the accelerometer if it is not rotating much and not falling
        if(mUpdateAccelAngle)
        {
            if(Math.abs(magnitude-9.81f)<.5)
            {
                float calcAngle = (float) Math.atan2(-event.values[0], event.values[2]);
                if(Math.abs(calcAngle) < 1.57)
                {
                    mAccelCorrectionAmount = calcAngle - mAngle;
                }
            }
        }
        mAccelLast[0]=event.values[0];
        mAccelLast[1]=event.values[1];
        mAccelLast[2]=event.values[2];
        mAccelMagnitudeLast=magnitude;
    }

    public void checkBattery()
    {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        mBattery = level / (float)scale;

    }

    public void constructBuffer()
    {
        dataByteBuffer.rewind();
        dataByteBuffer.putFloat(mAngle*57.296f);
        dataByteBuffer.putFloat(mAngularVelocity*57.296f);
        dataByteBuffer.putFloat(mMagDirection);
        dataByteBuffer.putFloat(mBattery);
        dataByteBuffer.putLong(mTimestamp);
        dataByteBuffer.flip();
    }

    @Override
    public void onSensorChanged(SensorEvent event)
    {
        mTimestamp=event.timestamp;
        if(mShuttingDown)return;
        if(mFirstTime)
        {
            mLastGyroTimestamp=mTimestamp;
            mLastTimestamp=mTimestamp;
            mLastSendTimestamp=mTimestamp;
            mLastListenTimestamp=mTimestamp;
            mLastBatteryTimestamp=mTimestamp-10000000000L;
            mFirstTime=false;
            return;
        }

        switch(event.sensor.getType())
        {
            case Sensor.TYPE_ACCELEROMETER:
                onAccelData(event);
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                onMagData(event);
                break;
            case Sensor.TYPE_GYROSCOPE:
                onGyroData(event);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                onRotationVectorData(event);
                break;
        }

        // if time since last network write > 0.01 seconds then send data
        if(mTimestamp-mLastSendTimestamp> 10000000L)
        {
            if(mTimestamp-mLastTimestamp>100000000L)
            {
                // if we're >10 messages behind, then reset
                mLastSendTimestamp=mTimestamp;
            }else
            {
                mLastSendTimestamp += 10000000L;
            }
//            mLastSendTimestamp=mTimestamp;
            constructBuffer();
            sendBluetooth();
            sendUDP();
        }
        // check for new connections every 0.1s
        if(mTimestamp-mLastListenTimestamp>1000000000L)
        {
            mLastListenTimestamp=mTimestamp;
            listenBluetooth();
            listenUDP();
            Log.d("gyro","sensor:"+mAngle+":"+mAngularVelocity+":"+mAccelCorrectionAmount);
        }
        // get battery every 10 seconds
        if(mTimestamp-mLastBatteryTimestamp>10000000000L)
        {
            mLastBatteryTimestamp=mTimestamp;
            checkBattery();
        }
        mLastTimestamp=mTimestamp;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy)
    {

    }

    public static String wifiIpAddress(Context ctx) {
        WifiManager wifiManager = (WifiManager) ctx.getSystemService(WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

        // Convert little-endian to big-endianif needed
        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
        } catch (UnknownHostException ex) {
            Log.e("WIFIIP", "Unable to get host address.");
            // check if we're an access point
            ipAddressString="192.168.43.1";
            try
            {
                for(Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                    en.hasMoreElements(); )
                {
                    NetworkInterface ni=en.nextElement();
                    if(!ni.isLoopback() && !ni.isPointToPoint() && ni.isUp() && !ni.isVirtual())
                    {
                        for (Enumeration<InetAddress> enumIpAddr = ni.getInetAddresses();
                             enumIpAddr.hasMoreElements(); )
                        {
                            InetAddress ad=enumIpAddr.nextElement();
                            if(ad.getHostAddress()!="" && ad.getAddress().length==4)
                            {
                                ipAddressString=ad.getHostAddress();
                            }
                        }
                    }
                }
            } catch(SocketException e)
            {
                e.printStackTrace();
            }
        }

        return ipAddressString;
    }

    public static String getBluetoothMac(Context ctx)
    {
        if(mBluetoothMAC==null)
        {
            SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
            mBluetoothMAC=settings.getString("BluetoothMAC",null);
        }
        return mBluetoothMAC;
    }

    public static void setBluetoothMac(Context ctx, String mac)
    {
        mBluetoothMAC=mac;
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor edit=settings.edit();
        edit.putString("BluetoothMAC",mBluetoothMAC);
        edit.commit();
    }

    public static String getSettingsString(Context ctx)
    {
        return wifiIpAddress(ctx)+","+UDP_PORT+","+getBluetoothMac(ctx);
    }


}
