package com.mrl.simplegyroserver;

import android.app.Notification;
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
import android.net.wifi.WifiManager;
import android.os.*;
import android.util.Log;

import com.mrl.flashcamerasource.MulticastConnector;
import com.mrl.flashcamerasource.ServiceWifiChecker;
import com.mrl.simplegyroclient.R;

import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.UUID;

public class GyroServerService extends Service implements SensorEventListener
{
    static final boolean USE_ROTATION_VECTOR = true;

    static final String PREFS_NAME = "launcherPrefs";
    public static String mBluetoothMAC;

    final static public int UDP_PORT = 2323;
    final static public int PACKET_SIZE = 28;
    final static public UUID BLUETOOTH_UUID =
            UUID.fromString("ad91f8d4-e6ea-4f57-be70-4f9802ebc619");

    public static int sSwingID = -1;
    public static int sWifiNum = -1;


    public static volatile boolean sRunning = false;
    public static volatile int sConnectionState = 0;
    public static float sAngleDebug = 0;
    public static float sCorrectionAmountDebug = 0;

    boolean mShuttingDown = false;

    HandlerThread mSensorThread;
    Handler mSensorEventHandler;

    volatile boolean mReadingSensors=false;

    byte[] mDataBytes = new byte[PACKET_SIZE];
    ByteBuffer dataByteBuffer = ByteBuffer.wrap(mDataBytes);
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    MulticastConnector mConnector;

    private WifiManager.MulticastLock multicastLock=null;


    public GyroServerService()
    {
    }

    public static String checkForcedID()
    {
        String path = Environment.getExternalStorageDirectory() + "/forceSwingID.txt";
        File file = new File(path);
        try
        {
            if (file.exists())
            {
                BufferedReader br = new BufferedReader(new FileReader(path));
                String forcedID = br.readLine();
                if(forcedID!=null)
                {
                    forcedID= forcedID.toUpperCase().trim();
                    if(forcedID.length()>=2)
                    {
                        char firstChar=forcedID.charAt(0);
                        sWifiNum=firstChar-'A';
                        sSwingID=Integer.parseInt(forcedID.substring(1));
                        return forcedID;
                    }
                }
            }
        } catch(NumberFormatException e)
        {
            Log.e("forceID","Bad format of force ID file");
        }catch(IOException e)
        {
            e.printStackTrace();
        }
        return null;
    }


    @Override
    public void onCreate()
    {
        if(checkForcedID()!=null)
        {
            setSwingId(this,sSwingID);
        }
        sConnectionState=0;
        mConnector = new MulticastConnector(this, false,99,99);
        Notification noti = new Notification.Builder(this)
                .setContentTitle("Gyro Server Service running")
                .setContentText("this phone should be under the swing")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(1, noti);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                            "serverWakelock");
        wakeLock.acquire();
        WifiManager wm =
                (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "gyroserverwifilock");
        wifiLock.acquire();

        multicastLock = wm.createMulticastLock("gyroservermulticastlock");
        multicastLock.acquire();


        sRunning = true;
        mSensorThread = new HandlerThread("gyrohandler")
        {
            public void onLooperPrepared()
            {
                mSensorEventHandler = new Handler(getLooper());
                initSensorThread();
            }
        };
//        mSensorThread.setPriority(Thread.MAX_PRIORITY);
        mSensorThread.start();
    }

    @Override
    public void onDestroy()
    {
        multicastLock.release();

        mConnector.close();
        wifiLock.release();
        wakeLock.release();
        mShuttingDown = true;
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        sm.unregisterListener(this);
        mSensorEventHandler.removeCallbacksAndMessages(null);
        mSensorEventHandler.post(new Runnable()
        {
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

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(1);
        Log.d("woo", "service stop");
        sRunning = false;
    }


    @Override
    public IBinder onBind(Intent intent)
    {
        throw new UnsupportedOperationException("Not yet implemented");
    }


    public void initSensorThread()
    {


        mFirstTime = true;
        sConnectionState=0;

        openBluetooth();
        Log.d("woo", "open BT");
        openUDP();
        Log.d("woo", "open UDP");
        disconnectedHandler();

    }

    public void connectSensors()
    {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor sensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor sensor2;
        if(USE_ROTATION_VECTOR)
        {
            sensor2 = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        } else
        {
            sensor2 = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }

        boolean success= sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST, mSensorEventHandler);
        success&= sm.registerListener(this, sensor2, SensorManager.SENSOR_DELAY_FASTEST, mSensorEventHandler);
        Log.d("woo", "connected sensors: "+success);
        if(success)
        {
            mReadingSensors=true;
            bJustConnected=true;
            mFirstTime=true;
        }else
        {
            mReadingSensors=false;
            sm.unregisterListener(this);
        }
    }

    public void disconnectSensors()
    {
        SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        sm.unregisterListener(this);
        mReadingSensors=false;
        sConnectionState=0;
        mSensorEventHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                disconnectedHandler();
            }
        }, 100);
    }

    //************** BLUETOOTH STUFF *****************/

    BluetoothAdapter mBTAdapter;
    ArrayList<BTConnection> mBTConnections=new ArrayList<BTConnection>();


    // just send messages at 50hz, last one we have
    class ThreadedBTSender extends Thread
    {
        byte[] internalBuf;
        void copyPacketBuffer(byte[] buffer)
        {
            synchronized (internalBuf) {
                System.arraycopy(buffer, 0, internalBuf, 0, PACKET_SIZE);
            }
        }

        OutputStream mStr;

        public ThreadedBTSender(OutputStream str)
        {
            super("BTOut");
            internalBuf=new byte[PACKET_SIZE];
            mStr = str;
            start();
        }

        public void run() {
            byte[] buf2 = new byte[PACKET_SIZE];
            try {
                while (!interrupted()) {
                    long startTime = System.nanoTime();

                    synchronized (internalBuf) {
                        System.arraycopy(internalBuf, 0, buf2, 0, PACKET_SIZE);
                    }
                    mStr.write(internalBuf, 0, PACKET_SIZE);
                    mStr.flush();

                    long elapsedNanos = System.nanoTime() - startTime;
                    long sleepNanos = 20000000L - elapsedNanos;
                    if (sleepNanos > 0) {
                        long sleepMillis = sleepNanos / 1000000L;
                        sleepNanos -= sleepMillis * 1000000L;
                        Thread.sleep(sleepMillis, (int) sleepNanos);
                    }
                }
            }
            catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            synchronized(this)
            {
                try
                {
                    mStr.close();
                } catch(IOException e1)
                {
                    e1.printStackTrace();
                }
            }
        }


        public boolean closed()
        {
            return (mStr == null);
        }

        public void close() throws IOException
        {
            interrupt();
            try
            {
                join(10);
            } catch(InterruptedException e)
            {
                e.printStackTrace();
            }
            synchronized(this)
            {
                try
                {
                    mStr.close();
                } catch(IOException e1)
                {
                    e1.printStackTrace();
                }
            }
        }

    }

    class BTConnection
    {
        public BluetoothSocket mSocket;
        public InputStream mIS;
        public ThreadedBTSender mOS;

        public BTConnection(BluetoothSocket sock)
        {
            try
            {
                mIS=sock.getInputStream();
                mOS = new ThreadedBTSender(sock.getOutputStream());
                mSocket=sock;
            } catch(IOException e)
            {
                mSocket=null;
                mIS=null;
                mOS=null;
            }
        }

        public void close()
        {
            try
            {
                if(mIS!=null)
                {
                    mIS.close();
                }
            } catch(IOException e)
            {
            }
            mIS=null;
            try
            {
                if(mOS!=null)
                {
                    mOS.close();
                }
            } catch(IOException e)
            {
            }
            mOS=null;
            try
            {
                if(mSocket!=null)
                {
                    mSocket.close();
                }
            } catch(IOException e)
            {
            }
            mSocket=null;
        }

        boolean isConnected()
        {
            if(mSocket!=null && mSocket.isConnected()==false)
            {
                try
                {
                    mSocket.close();
                    mSocket=null;
                } catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
            if(mSocket==null)
            {
                return false;
            }
            return true;
        }

        void write( byte[] bytes)
        {
            mOS.copyPacketBuffer(bytes);
        }
    }

    class BTServerConnector extends Thread
    {
        public BluetoothSocket mConnectedSocket = null;
        private BluetoothServerSocket mBTServerSocket;

        public BTServerConnector()
        {
            super("BTServerConnector");
        }

        public void run()
        {
            try
            {
                // sleep before we do anything
                Thread.sleep(100);
                mBTServerSocket = mBTAdapter
                        .listenUsingInsecureRfcommWithServiceRecord("Gyro service", BLUETOOTH_UUID);
                mConnectedSocket = mBTServerSocket.accept();
                Log.e("bt:","bt: accept "+mConnectedSocket.getRemoteDevice().getAddress());

                closeServerSocket();
                // pause to make sure we're connected
                try
                {
                    boolean connected=false;
                    for(int c=0;c<50;c++)
                    {
                        Thread.sleep(10);
                       if(mConnectedSocket.isConnected())
                       {
                           connected=true;
                           break;
                       }
                    }
                    if(!connected)
                    {
                        Log.e("bt:","couldn't connect");
                    }
                } catch(InterruptedException e)
                {
                    e.printStackTrace();
                }
            } catch(IOException e)
            {
                e.printStackTrace();
                closeServerSocket();
            } catch(InterruptedException e)
            {
                e.printStackTrace();
            }
            Log.e("bt:","bt: endthread");
        }

        void closeServerSocket()
        {
            BluetoothServerSocket sock=null;
            synchronized(this)
            {
                sock = mBTServerSocket;
                mBTServerSocket = null;
            }
            if(sock != null)
            {
                try
                {
                    sock.close();
                } catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }

        public void shutdownAcceptThread()
        {
            closeServerSocket();
            if(Thread.currentThread() != BTServerConnector.this && isAlive())
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

    BTServerConnector mBTServerConnector = null;

    void openBluetooth()
    {
        // open adapter - everything else happens in listen
        BluetoothManager mg = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        mBTAdapter = mg.getAdapter();
    }

    void closeBluetooth()
    {
        if(mBTServerConnector != null)
        {
            mBTServerConnector.shutdownAcceptThread();
            try
            {
                mBTServerConnector.join(100);
            } catch(InterruptedException e)
            {
            }
        }
        mBTAdapter = null;
        for(BTConnection c:mBTConnections)
        {
            c.close();
        }
        mBTConnections.clear();
    }

    void listenBluetooth()
    {
        if(mBTConnections.size()>0)
        {
            sConnectionState |= 0x2;
        } else
        {
            sConnectionState &= 0xfd;
        }

        // close any disconnected connections
        boolean deleted=true;
        while(deleted)
        {
            deleted=false;
            for(int c = 0; c < mBTConnections.size(); c++)
            {
                if(!mBTConnections.get(c).isConnected())
                {
                    Log.e("bt:","Removing connection");
                    mBTConnections.remove(c);
                    deleted=true;
                    break;
                }
            }
        }

        // listen for new connections
        if(mBTServerConnector == null)
        {
            mBTServerConnector = new BTServerConnector();
            mBTServerConnector.start();
            Log.d("bt","listen");
        } else
        {
            if(!mBTServerConnector.isAlive())
            {
                BluetoothSocket sock = mBTServerConnector.mConnectedSocket;
                if(sock!=null)
                {
                    BTConnection conn=new BTConnection(sock);
                    Log.d("bt", "connected");
                    mBTConnections.add(conn);
                }
                mBTServerConnector = null;
            }
        }
    }

    void sendBluetooth()
    {
        for(int c = 0; c < mBTConnections.size(); c++)
        {
            mBTConnections.get(c).write(mDataBytes);
        }
    }

    /*****************UDP STUFF *********************************/
    DatagramChannel mUDPConnection;
    ArrayList<SocketAddress> mUDPTargets=new ArrayList<SocketAddress>();
    ByteBuffer dataRead = ByteBuffer.allocateDirect(4);
    ArrayList<Long> mUDPLastPingTimes=new ArrayList<Long>();
    ArrayList<ThreadedUDPSender> mUDPSenders=new ArrayList<ThreadedUDPSender>();
    boolean bJustConnected=true;

    class ThreadedUDPSender extends Thread
    {
        public boolean killed=false;
        SocketAddress mAddr;

        byte[] internalBuf;

        public ThreadedUDPSender(SocketAddress addr)
        {
            super("UDPSender:"+addr);
            setPriority(Thread.MAX_PRIORITY);
            mAddr=addr;
            internalBuf=new byte[PACKET_SIZE];
            start();
        }

        public void run()
        {
            // send once per 100th of a second
            byte[] buf2=new byte[PACKET_SIZE];
            try {
                DatagramSocket sock=new DatagramSocket();
                sock.setReceiveBufferSize(1);
                sock.setSendBufferSize(PACKET_SIZE*8);
                DatagramPacket pack=new DatagramPacket(buf2,PACKET_SIZE,mAddr);
                while(true) {
                    long startTime=System.nanoTime();
                    synchronized (internalBuf) {
                        System.arraycopy(internalBuf,0, buf2, 0, PACKET_SIZE);
                    }
                    sock.send(pack);
                    long elapsedNanos=System.nanoTime()-startTime;
                    long sleepNanos=10000000L-elapsedNanos;
                    if(sleepNanos>0) {
                        long sleepMillis = sleepNanos / 1000000L;
                        sleepNanos -= sleepMillis * 1000000L;
                        Thread.sleep(sleepMillis, (int)sleepNanos);
                    }
                }
            } catch (InterruptedException e) {
                // interrupt this thread to kill it
            }
            catch (IOException e) {
                e.printStackTrace();
            }
            killed=true;
        }

        void copyPacketBuffer(byte[] buffer)
        {
            synchronized (internalBuf) {
                System.arraycopy(buffer, 0, internalBuf, 0, PACKET_SIZE);
            }
        }

        void close()
        {
            interrupt();
            try {
                join(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    void openUDP()
    {
        // open udp non-blocking listener
        try
        {
            mUDPConnection = DatagramChannel.open();
            mUDPConnection.configureBlocking(false);
            mUDPConnection.socket().bind(new InetSocketAddress(UDP_PORT));
            mUDPConnection.socket().setSendBufferSize(PACKET_SIZE*4);
            while(true)
            {
                // read any buffered data until timeout
                SocketAddress addr = mUDPConnection.receive(dataRead);
                if(addr==null)
                {
                  break;
                }
            }
        } catch(IOException e)
        {
            e.printStackTrace();
        }
    }

    void closeUDP()
    {
        if(mUDPConnection != null)
        {
            try
            {
                mUDPConnection.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
        mUDPConnection = null;
        for(ThreadedUDPSender snd:mUDPSenders)
        {
            snd.close();
        }
        mUDPSenders.clear();
    }

    void listenUDP()
    {
        if(sSwingID == -1)
        {
            getSwingID(this);
        }
        try
        {
            dataRead.clear();
            SocketAddress addr = mUDPConnection.receive(dataRead);
            if (addr != null && dataRead.position() >= 2) {
                dataRead.flip();
                // 'Hi' = send to this address
                if (dataRead.get(0) == 72 && dataRead.get(1) == 105) {
                    if (mUDPTargets.size() == 0) {
                        bJustConnected = true;
                    }
                    int lastIndex = mUDPTargets.indexOf(addr);
                    if (lastIndex == -1) {
                        mUDPTargets.add(addr);
                        mUDPLastPingTimes.add(System.currentTimeMillis());
                        mUDPSenders.add(new ThreadedUDPSender(addr));
                        //Log.d("Ping:", "*" + addr);
                    } else {
                        mUDPLastPingTimes.set(lastIndex, System.currentTimeMillis());
                        //Log.d("Ping:", addr + ":" + mUDPTargets.get(lastIndex));
                    }
                    // if it is a 3+ byte message, this is a command to do something
                    //
                    if(dataRead.limit()>=3)
                    {
                        switch(dataRead.get(2))
                        {
                            case 1:
                                // set clock timestamp to zero and pause it
                                mTimestampPaused=true;
                                // paused at start of game state
                                mGameState=1;
                                break;
                            case 2:
                                // start clock counting up - in game state
                                // i.e. unpause
                                mTimestampPaused=false;
                                mGameState=2;
                                break;
                            case 3:
                                // stopped at end of game state
                                mGameState=3;
                                break;
                        }
                    }


                }
                // 'Qnn' = query swing id n
                if (dataRead.get(0) == 81 && dataRead.get(1) == 48 + (sSwingID / 10) &&
                        dataRead.get(2) == 48 + (sSwingID % 10)) {
                    // clear existing target so we don't flood the world with messages
                    //mUDPTarget = null;
                    // send a response to give our IP address and bluetooth ID
                    // send getSettingsString(this) to them
                    String queryResponse = getSettingsString(this);
                    byte[] bytes = queryResponse.getBytes("UTF-8");
                    mUDPConnection.send(ByteBuffer.wrap(bytes), addr);
                    Log.e("query", "send response:" + queryResponse);
                }
            }
        } catch(IOException e)
        {
            // timeout, ignore
        }
        // stop sending UDP if >10s of no pings (saves having a 'bye' packet
        long curTime=System.currentTimeMillis();
        for(int c =0;c<mUDPLastPingTimes.size();c++)
        {
            if( curTime-mUDPLastPingTimes.get(c) > 10000L)
            {
                mUDPLastPingTimes.set(c,-1L);
            }
        }
        boolean deleted=true;
        while(deleted)
        {
            deleted=false;
            for(int c=0;c<mUDPLastPingTimes.size();c++)
            {
                if(mUDPLastPingTimes.get(c)==-1L)
                {
                    mUDPLastPingTimes.remove(c);
                    mUDPTargets.remove(c);
                    mUDPSenders.get(c).close();
                    mUDPSenders.remove(c);
                }
            }
        }

        if(mUDPTargets.size() == 0)
        {
            sConnectionState &= 0xfe;
        } else
        {
            sConnectionState |= 0x1;
        }

    }

    void sendUDP()
    {
        int count=mUDPSenders.size();
        if(count!=0)
        {
            for(int c=0;c<count;c++)
            {
                mUDPSenders.get(c).copyPacketBuffer(mDataBytes);
            }
        }
//        if(mUDPTargets.size()!=0)
//        {
//            for(SocketAddress addr:mUDPTargets)
//            {
//                try
//                {
//                    int dataLen = mUDPConnection.send(dataByteBuffer, addr);
//                    dataByteBuffer.rewind();
//                } catch(IOException e)
//                {
//                    e.printStackTrace();
//                }
//            }
//        }
    }

    /******************Sensor processing here (calls to network code above)***********************/

    int mGameState=0;
    boolean mFirstTime = true;
    boolean mTimestampPaused=false;
    long mTimestampOffset=0;
    long mTimestamp;
    long mLastTimestamp;
    long mLastSendTimestamp;
    long mLastListenTimestamp;
    long mLastPollTimestamp;
    long mLastGyroTimestamp;
    long mLastBatteryTimestamp;

    float mAngle = 0;
    float mAngularVelocity = 0;
    float mBattery = 0.5f;
    float mMagDirection = 0;
    float mSwingTip=0;
    boolean mUpdateAccelAngle = false;
    float mAccelCorrectionAmount = 0;

    float[] mAccelLast = new float[3];
    float[] mRotationMatrix = new float[9];
    float[] mOrientation = new float[3];
    float mAccelMagnitudeLast = 0f;

    public void onGyroData(SensorEvent event)
    {
        float sensorY = event.values[0];
        mAngularVelocity = sensorY;
        // send this somewhere
        if(Math.abs(sensorY) < 0.01)
        {
            mUpdateAccelAngle = true;
        } else
        {
            mUpdateAccelAngle = false;
        }
        long dt = mTimestamp - mLastGyroTimestamp;
        float fDt = 0.000000001f * (float) dt;
        mAngle += fDt * sensorY;
        if(mAngle > Math.PI)
        {
            mAngle -= 2.0f * Math.PI;
        }
        if(mAngle < -Math.PI)
        {
            mAngle += 2.0f * Math.PI;
        }
        if(Math.abs(mAccelCorrectionAmount)>1.0)
        {
            mAngle+=mAccelCorrectionAmount;
            mAccelCorrectionAmount=0;
        }
        if(mAccelCorrectionAmount > 0)
        {
            float correctAmt = Math.min(0.1f * fDt, mAccelCorrectionAmount);
            mAngle += correctAmt;
            mAccelCorrectionAmount -= correctAmt;
        } else if(mAccelCorrectionAmount < 0)
        {
            float correctAmt = Math.max(-0.1f * fDt, mAccelCorrectionAmount);
            mAngle += correctAmt;
            mAccelCorrectionAmount -= correctAmt;
        }
        mLastGyroTimestamp = mTimestamp;
        sAngleDebug = mAngle;
        //sCorrectionAmountDebug=mAccelCorrectionAmount;
    }


    int mAccelDirection = 0;


    float[] mMagnitudeBuffer = new float[1024];
    float[] mMagnitudeAngleBuffer = new float[1024];
    int mMagnitudeBufferPos = 0;


    float mAccelMax = 0;
    float mAccelMin = 0;
    float mAngleAtMax = 0;

    public void onRotationVectorData(SensorEvent event)
    {
        SensorManager.getRotationMatrixFromVector(mRotationMatrix, event.values);
        SensorManager.getOrientation(mRotationMatrix, mOrientation);
        // TODO maybe get mag from here?
        float roll = mOrientation[1];
        if(bJustConnected)
        {
            mAngle=roll;
            bJustConnected=false;
        }else
        {
     //       Log.e("acc",mAccelCorrectionAmount+":"+mAngle+":"+roll);

            mAccelCorrectionAmount = roll - mAngle;
            sCorrectionAmountDebug = mAccelCorrectionAmount;
        }
        //mAngle=0.998f*mAngle+0.002f*roll;
        if(Math.abs(roll)<1.0)
        {
            mMagDirection = mOrientation[0] * 57.2957795131f;
            if(mMagDirection<0)
            {
                mMagDirection+=360f;
            }
        }
        mSwingTip=((mOrientation[2]* 57.2957795131f+360f)%360f) - 180f;
    }

    public void onAccelData(SensorEvent event)
    {
        float magnitude = (float) Math
                .sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] +
                              event.values[2] * event.values[2]);
        /*if(mMagnitudeBufferPos == 0)
        {
            mAccelMax = magnitude;
            mAccelMin = magnitude;
            mAngleAtMax = 0;
        }
        mMagnitudeBuffer[mMagnitudeBufferPos] = magnitude;
        mMagnitudeAngleBuffer[mMagnitudeBufferPos] = mAngle;
        mMagnitudeBufferPos++;
        if(mMagnitudeBufferPos == 1024)
        {
            if(mAccelMax - mAccelMin > 0.5)
            {
                // we're swinging!
                Log.d("found max", mAngleAtMax * 57.296 + ":" + mAccelMax + ":" + mAccelMin);
                mAccelCorrectionAmount = -mAngleAtMax;
            } else
            {
                Log.d("no max", mAngleAtMax * 57.296 + ":" + mAccelMax + ":" + mAccelMin);
            }
            mMagnitudeBufferPos = 0;
        }
        if(magnitude > mAccelMax)
        {
            mAccelMax = magnitude;
            mAngleAtMax = mAngle;
        }
        if(magnitude < mAccelMin)
        {
            mAccelMin = magnitude;
        }


        // update angle based on the accelerometer if it is not rotating much and not falling
        if(mUpdateAccelAngle)
        {
            if(Math.abs(magnitude - 9.81f) < .5)
            {
                float calcAngle = (float) Math.atan2(-event.values[0], event.values[2]);
                if(Math.abs(calcAngle) < 1.57)
                {
                    mAccelCorrectionAmount = calcAngle - mAngle;
                }
            }
        }*/
        mAccelLast[0] = event.values[0];
        mAccelLast[1] = event.values[1];
        mAccelLast[2] = event.values[2];
        mAccelMagnitudeLast = magnitude;
    }

    public void checkBattery()
    {
        IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = registerReceiver(null, ifilter);
        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

        mBattery = level / (float) scale;

    }

    public void sendSwingInfo()
    {
        mConnector.setSwingID(sSwingID,sWifiNum);
        mConnector.SendData(getSettingsString(this));
    }

    public void constructBuffer()
    {
        if(mTimestampPaused)
        {
            // hold timestamp to zero until we are released
            mTimestampOffset=-mTimestamp;
        }
        dataByteBuffer.rewind();
        dataByteBuffer.putFloat(mAngle * 57.296f);
        dataByteBuffer.putInt(mGameState);
        dataByteBuffer.putFloat(mMagDirection);
        dataByteBuffer.putFloat(mBattery);
        dataByteBuffer.putLong(mTimestamp+mTimestampOffset);
        dataByteBuffer.putFloat(mSwingTip);
        dataByteBuffer.flip();
    }

    @Override
    public void onSensorChanged(SensorEvent event)
    {
        mTimestamp = event.timestamp;
        //if(true)return;
        if(mShuttingDown) return;
        if(mFirstTime)
        {
            mLastGyroTimestamp = mTimestamp;
            mLastTimestamp = mTimestamp;
            mLastSendTimestamp = mTimestamp;
            mLastListenTimestamp = mTimestamp;
            mLastPollTimestamp=mTimestamp;
            mLastBatteryTimestamp = mTimestamp - 10000000000L;
            mFirstTime = false;
            return;
        }

        switch(event.sensor.getType())
        {
            case Sensor.TYPE_ACCELEROMETER:
                onAccelData(event);
                break;
            case Sensor.TYPE_GYROSCOPE:
                onGyroData(event);
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                onRotationVectorData(event);
                break;
        }

        // if time since last network write > 0.01 seconds then send data
        if(mTimestamp - mLastSendTimestamp > 10000000L)
        {
//            if(mTimestamp - mLastTimestamp > 100000000L)
//            {
//                // if we're >10 messages behind, then reset
//                mLastSendTimestamp = mTimestamp;
//            } else
//            {
//                mLastSendTimestamp += 10000000L;
//            }
            mLastSendTimestamp=mTimestamp;
            constructBuffer();
            sendBluetooth();
            sendUDP();
        }
        // check for new connections every .1s
        if(mTimestamp - mLastListenTimestamp > 100000000L)
        {
            mLastListenTimestamp = mTimestamp;
            listenBluetooth();
            listenUDP();
        }
        // send swing info every 1 second
        if(mTimestamp - mLastPollTimestamp> 1000000000L)
        {
            if(sWifiNum != -1)
            {
                ServiceWifiChecker.checkWifi(this, sWifiNum);
            }
            sendSwingInfo();
            mLastPollTimestamp=mTimestamp;
        }
        // get battery every 10 seconds
        if(mTimestamp - mLastBatteryTimestamp > 10000000000L)
        {
            mLastBatteryTimestamp = mTimestamp;
            checkBattery();
        }
        // disconnect sensors if no one is listening
        if((sConnectionState&0x3)==0)disconnectSensors();
        mLastTimestamp = mTimestamp;
    }

    int mDisconnectedCounter=0;
    // if we're disconnected we don't read sensors, just wait for connections and send our info out
    // this code runs always just in case the state variable is broken for some reason
    public void disconnectedHandler()
    {
        if((mDisconnectedCounter & 15) == 0)
        {
            sendSwingInfo();
        }
        if((mDisconnectedCounter & 127) == 0)
        {
            checkBattery();
        }
        listenBluetooth();
        listenUDP();
        if(sWifiNum != -1)
        {
            ServiceWifiChecker.checkWifi(this, sWifiNum);
        }

        if((sConnectionState&3)!=0 && !mReadingSensors)
        {
            connectSensors();
        }else {
            mDisconnectedCounter++;
            mSensorEventHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    disconnectedHandler();
                }
            }, 100);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy)
    {

    }

    public static String getBluetoothMac(Context ctx)
    {
        if(mBluetoothMAC == null)
        {
            SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
            mBluetoothMAC = settings.getString("BluetoothMAC", null);
        }
        if(mBluetoothMAC == null)
        {
            String path = Environment.getExternalStorageDirectory() + "/btmac.txt";
            File file = new File(path);
            try
            {
                if(file.exists())
                {
                    BufferedReader br = new BufferedReader(new FileReader(path));
                    String macAddr = null;
                    macAddr = br.readLine();
                    mBluetoothMAC = macAddr.trim();
                    setBluetoothMac(ctx, mBluetoothMAC);
                }
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
        return mBluetoothMAC;
    }

    public static void setBluetoothMac(Context ctx, String mac)
    {
        mBluetoothMAC = mac;
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor edit = settings.edit();
        edit.putString("BluetoothMAC", mBluetoothMAC);
        edit.commit();
    }

    public static int getSwingID(Context ctx)
    {
        if(sSwingID == -1)
        {
            SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
            sSwingID = settings.getInt("SwingID", -1);
        }
        return sSwingID;
    }

    public static void setSwingId(Context ctx, int id)
    {
        id = id % 100;
        sSwingID = id;
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor edit = settings.edit();
        edit.putInt("SwingID", id);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        long curTime = cal.getTimeInMillis();
        edit.putLong("CodeScanTime", curTime);// we use the current time to make sure that the
        //last scanned device on the swing seat has priority
        edit.commit();
    }

    public static void setWifiNum(Context ctx, int num)
    {
        sWifiNum = num;
    }

    public static int getWifiNum(Context ctx)
    {
        return sWifiNum;
    }

    public static long getCodeScanTime(Context ctx)
    {
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        return settings.getLong("CodeScanTime", 0);

    }

    public static String getSettingsString(Context ctx)
    {
        return ServiceWifiChecker.wifiIPAddress(ctx) + "," + UDP_PORT + "," + getBluetoothMac(ctx) +
                "," + getSwingID(ctx) + "," + getCodeScanTime(ctx);
    }


}
