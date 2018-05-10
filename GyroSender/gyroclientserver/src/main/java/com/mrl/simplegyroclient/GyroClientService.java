
package com.mrl.simplegyroclient;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.BatteryManager;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Pair;

import com.mrl.flashcamerasource.MulticastConnector;
import com.mrl.flashcamerasource.ServiceWifiChecker;
import com.mrl.simplegyroserver.GyroServerService;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.*;

public class GyroClientService extends Service
{

    final static public UUID BLUETOOTH_UUID =
            UUID.fromString("ad91f8d4-e6ea-4f57-be70-4f9802ebc619");
    public static final String PREFS_NAME = "ServicePrefs";
    public static boolean sRunning = false;
    public static boolean sSettingsDirty = false;
    public static String sTargetAddr = "";

    public static int sWifiNum=-1;
    public static int sSwingNum=-1;

    public static int sConnectionState = 0;

    public static final int LOCAL_PORT = 2424;
    public static volatile float mAngleDebug = 0;

    boolean mQuitting = false;

    Thread mServerThread;

    MulticastConnector mConnector;


    Handler mMainThreadHandler;

    public GyroClientService()
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
                        sSwingNum=Integer.parseInt(forcedID.substring(1));
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
            setWifiConnection(this,sWifiNum,sSwingNum);
        }
        mConnector=new MulticastConnector(this,true,99,99);
        Notification noti = new Notification.Builder(this)
                .setContentTitle("Gyro Client Service running")
                .setContentText("this phone should be in a headset")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build();
        startForeground(1,noti);
        // check if we have access to the file system
        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED )
        {
            // launch starter app to get this permission
            Intent starterIntent=new Intent(this,GyroClientStarter.class);
            starterIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(starterIntent);
        }

        mMainThreadHandler = new Handler();
        sRunning = true;
        Log.d("woo", "create");
        mServerThread = new Thread("ServerThread")
        {
            @Override
            public void run()
            {
                serverThread();
            }
        };
        mServerThread.start();
    }

    @Override
    public void onDestroy()
    {
        mConnector.close();
        mMainThreadHandler.removeCallbacksAndMessages(null);
        mMainThreadHandler = null;
        Log.d("woo", "service stop");
        mQuitting = true;
        try
        {
            mServerThread.interrupt();
            mServerThread.join(100);
        } catch(InterruptedException e)
        {
        }
        sRunning = false;
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if(intent!=null && intent.hasExtra("WIFI_NUM"))
        {
            int wifiNum= intent.getIntExtra("WIFI_NUM",-1);
            int swingID=intent.getIntExtra("SWING_ID",-1);
            if(wifiNum>=0 && wifiNum<10 && swingID>=0 && swingID<20)
            {
                // launch a thread to go to this wifi and set the swing id once the wifi is loaded
                setWifiConnection(this, wifiNum, swingID);
            }
        }
        // We want this service to continue running until it is explicitly
        // stopped, so return sticky.
        return START_STICKY;
    }


    class BTReceiver extends Thread
    {

        final byte[] currentPacket;
        volatile long lastTime;

        BluetoothDevice device;
        BluetoothSocket connection;
        InputStream inputStream;
        BluetoothAdapter adp;
        String mAddr;
        boolean hadData=false;
        boolean hasMsg=false;


        public BTReceiver(BluetoothAdapter adaptor, String addr)
        {
            super("bt:"+addr);
            currentPacket=new byte[GyroServerService.PACKET_SIZE];
            lastTime=System.nanoTime()-100000000L;
            connection = null;
            adp = adaptor;
            mAddr = addr;
            start();
        }

        public void run()
        {

            try
            {
                adp.cancelDiscovery();
                device = adp.getRemoteDevice(mAddr.toUpperCase());
                connection =
                        device.createInsecureRfcommSocketToServiceRecord(BLUETOOTH_UUID);
//                connection = mSingletonSocket;
                connection.connect();
                inputStream = connection.getInputStream();
                byte[]recvBytes=new byte[GyroServerService.PACKET_SIZE];
                while(!interrupted())
                {
                    if(!hasMsg)
                    {
                        // read into our buffer
                        inputStream.read(recvBytes, 0, GyroServerService.PACKET_SIZE);
                        hadData = true;
                        lastTime = System.nanoTime();
                        synchronized (currentPacket)
                        {
                            System.arraycopy(recvBytes, 0, currentPacket, 0, recvBytes.length);
                            hasMsg = true;
                        }
                    }else
                    {
                        try
                        {
                            Thread.sleep(1);
                        } catch (InterruptedException e)
                        {
                        }
                    }
                }
            } catch(IOException e) {
                Log.d("bt", "can't connect");
            }
            close();
        }

        public void close()
        {
            if(Thread.currentThread() != BTReceiver.this && isAlive())
            {
                interrupt();
                try
                {
                    join(100);
                } catch(InterruptedException e)
                {
                    e.printStackTrace();
                }
                // if we close during connection attempt need to cancel it
                if(isAlive() && connection!=null)
                {
                    try {
                        connection.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                try
                {
                    join(100);
                } catch(InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            // close all the sockets and streams
            synchronized (BTReceiver.this) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException e1) {
                        e1.printStackTrace();
                    }
                }
            }
        }

        public void getCurrentBytes(byte[] buffer)
        {
            synchronized (currentPacket)
            {
                System.arraycopy(currentPacket,0,buffer,0,currentPacket.length);
                hasMsg=false;
            }
        }

        public long timeSinceLast()
        {
            return System.nanoTime()-lastTime;
        }

        public boolean receivedAnyPackets()
        {
            return hadData;
        }


    }


    int mPort;
    String mIPAddr;
    String mBTAddr;
    
    class UDPReceiver extends Thread
    {
        private SocketAddress mAddr;
        final byte[] currentPacket;
        volatile long lastTime;
        volatile int serverMessage;
        volatile boolean versionMismatch=false;

        boolean hasMsg=false;

        public UDPReceiver(SocketAddress addr)
        {
            super("udp:"+addr);
            mAddr=addr;
            currentPacket=new byte[GyroServerService.PACKET_SIZE];
            lastTime=System.nanoTime();
            start();
        }

        public long timeSinceLast()
        {
            return System.nanoTime()-lastTime;
        }

        public void close()
        {
            interrupt();
            try {
                join(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        public void run()
        {
            long firstTime=0;
            float frameCount=0;
            byte[] msgBytes={(byte)72,(byte)105,(byte)0,(byte)0};
            byte[] pollBytes={(byte)72,(byte)105};
            byte[]recvBytes=new byte[GyroServerService.PACKET_SIZE];
            try {
                DatagramPacket msgPacket=new DatagramPacket(msgBytes,4,mAddr);
                DatagramPacket pollPacket=new DatagramPacket(pollBytes,2,mAddr);
                DatagramPacket recvPacket=new DatagramPacket(recvBytes,recvBytes.length);
                long nextPollTime=System.nanoTime();
                DatagramSocket sock=new DatagramSocket();
                sock.setSoTimeout(10);
                while(!interrupted())
                {
                    long curTime=System.nanoTime();
                    if(curTime>nextPollTime)
                    {
                        sock.send(pollPacket);
                        nextPollTime=curTime+1000000000L;
                    }
                    // send a messsge back to the server
                    // e.g. to reset the clock
                    if(serverMessage !=0)
                    {
                        ByteBuffer bb=ByteBuffer.wrap(msgBytes);
                        bb.putInt(serverMessage);
                        sock.send(msgPacket);
                        serverMessage=0;
                    }
                    try {
                        if(!hasMsg)
                        {
                            sock.receive(recvPacket);
                            if(recvPacket.getLength()!=GyroServerService.PACKET_SIZE)
                            {
                                versionMismatch=true;
                            }
                            lastTime = System.nanoTime();
                            if (firstTime == 0)
                            {
                                firstTime = System.nanoTime();
                            } else
                            {
                                frameCount += 1f;
                            }
                            // got a packet - set this as the current packet
                            synchronized (currentPacket)
                            {
                                hasMsg = true;
                                System.arraycopy(recvBytes, 0, currentPacket, 0, recvBytes.length);
                            }
                        }else
                        {
                            try
                            {
                                Thread.sleep(1);
                            } catch (InterruptedException e)
                            {
                            }
                        }
                    }catch(SocketTimeoutException e) {
                        // timeout - do nothing
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void getCurrentBytes(byte[] buffer)
        {
            synchronized (currentPacket)
            {
                System.arraycopy(currentPacket,0,buffer,0,currentPacket.length);
                hasMsg=false;
            }
        }

        public void setServerMessage(int msg)
        {
            serverMessage=msg;
        }

        public boolean seenWrongVersion(){return versionMismatch;}
    }

    public void serverThread()
    {
        BTReceiver btConnector = null;


        UDPReceiver remoteUDPConnection=null;
        BTReceiver remoteBTConnection=null;
//        DatagramChannel remoteConnection = null;
        DatagramChannel localConnection = null;
        try
        {
//            remoteConnection = DatagramChannel.open();
//            remoteConnection.socket().setSoTimeout(1);
//            remoteConnection.configureBlocking(false);

            localConnection = DatagramChannel.open();
            localConnection.connect(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
            localConnection.configureBlocking(false);
//            localConnection.socket().bind(new InetSocketAddress(LOCAL_PORT));
        } catch(IOException e)
        {
            e.printStackTrace();
        }

        sSettingsDirty = true;

        // timeouts for
        // UDP receive
        long udpLastTime = System.currentTimeMillis();
        long udpPollTime = udpLastTime - 1000;

        int sendErrorCounter = 0;

        BluetoothManager mg = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adp = mg.getAdapter();
        ByteBuffer localMessage= ByteBuffer.allocateDirect(4);

        InetSocketAddress remoteAddr;


        long lastTimestampBT = 0;
        long lastTimestampUDP = 0;
        long lastTimestampSent=0;


        boolean needsWifiCheck =false;

        byte[] udpPacket=new byte[GyroServerService.PACKET_SIZE+8];
        byte[] btPacket=new byte[GyroServerService.PACKET_SIZE+8];

        long lastSendTime=System.nanoTime();

        int connectionState=0;
        while(!mQuitting && !mServerThread.isInterrupted())
        {
            long startTime=System.nanoTime();
            sConnectionState = connectionState;
            // settings changed - re connect to swing
            if(sSettingsDirty)
            {
                needsWifiCheck = true;
                readSettings();
                sSettingsDirty=false;
                if(remoteUDPConnection!=null) {
                    remoteUDPConnection.close();
                    remoteUDPConnection=null;
                }
                if(remoteBTConnection!=null)
                {
                    remoteBTConnection.close();
                    remoteBTConnection=null;
                }
            }

            // if we haven't heard from currently connected swing for several seconds
            // then query everything to check it is okay
            // if we need a wifi check
            if(needsWifiCheck || mIPAddr==null || mIPAddr.compareToIgnoreCase("1.1.1.1")==0 || (connectionState & 3) == 0)
            {
                if( sWifiNum!=-1)
                {
                    if(ServiceWifiChecker.checkWifi(this, sWifiNum))
                    {
                        // on the right wifi, now find the swing settings
                        if(sSwingNum != -1)
                        {
                            // need to check wifi again if we don't get a response, otherwise we've found the right swing
                            if(connectToSwing(sSwingNum))
                            {
                                needsWifiCheck=false;
                                if(remoteUDPConnection!=null) {
                                    remoteUDPConnection.close();
                                    remoteUDPConnection=null;
                                }
                                if(remoteBTConnection!=null)
                                {
                                    remoteBTConnection.close();
                                    remoteBTConnection=null;
                                }
                            }
                            // poll only in 2 seconds
//                            udpPollTime=curTime+1000;
                        }else
                        {
                            needsWifiCheck=false;
                        }
                    }
                }else
                {
                    needsWifiCheck=false;
                }
            }

            if(remoteUDPConnection==null && !needsWifiCheck && !sSettingsDirty)
            {
                try {
                    remoteAddr = new InetSocketAddress(InetAddress.getByName(mIPAddr), mPort);
                    remoteUDPConnection=new UDPReceiver(remoteAddr);
                } catch (UnknownHostException e) {
                    e.printStackTrace();
                }
            }
            if(remoteBTConnection==null && !needsWifiCheck && !sSettingsDirty)
            {
                remoteBTConnection=new BTReceiver(adp, mBTAddr.toUpperCase());
            }
            if(remoteBTConnection!=null)
            {
                if(!remoteBTConnection.isAlive())
                {
                    connectionState&=0xfd;
                    remoteBTConnection=null;
                }else
                {
                    if(remoteBTConnection.timeSinceLast()>1000000000L || remoteBTConnection.receivedAnyPackets()==false)
                    {
                        connectionState&=0xfd;
                    }else {
                        connectionState |= 0x02;
                    }
                    remoteBTConnection.getCurrentBytes(btPacket);
                }
            }
            if(remoteUDPConnection!=null)
            {
                if(remoteUDPConnection.timeSinceLast()>1000000000L)
                {
                    connectionState&=0xfe;
                }else
                {
                    connectionState|=1;
                }
                if(remoteUDPConnection.seenWrongVersion())
                {
                    NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    Notification noti = new Notification.Builder(this)
                            .setContentTitle("WARNING")
                            .setContentText("OLD VERSION OF SIMPLEGYROSERVER")
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .build();
                    mNotificationManager.notify(1, noti);
                }
                remoteUDPConnection.getCurrentBytes(udpPacket);
            }
            if((connectionState&3)!=0)
            {
                lastTimestampBT = ByteBuffer.wrap(btPacket).getLong(16);
                lastTimestampUDP = ByteBuffer.wrap(udpPacket).getLong(16);

                boolean useUDP=true;
                if(connectionState==2 || (connectionState==3 && lastTimestampBT > lastTimestampUDP))
                {
                    useUDP=false;
                }
                if(useUDP)
                {
                    if(lastTimestampUDP==0 || lastTimestampUDP!=lastTimestampSent)
                    {
                        dispatchPacket(localConnection, ByteBuffer.wrap(udpPacket));
                    }
                    lastTimestampSent=lastTimestampUDP;
                }else
                {
                    if(lastTimestampBT==0 || lastTimestampBT!=lastTimestampSent)
                    {
                        dispatchPacket(localConnection, ByteBuffer.wrap(btPacket));
                    }
                    lastTimestampSent=lastTimestampBT;
                }
            }

            // if there is anything sent back to us on the local connection
            // then ping it back to the server as 2 bytes on to the ping message
            try
            {
                localMessage.clear();
                localConnection.receive(localMessage);
                // first 2 bytes
                if(localMessage.position()>=4)
                {
                    localMessage.flip();
                    int msg = localMessage.getInt(0);
                    remoteUDPConnection.setServerMessage(msg);
                }
            }catch(SocketTimeoutException e)
            {
                // no message
            }
            catch (IOException e) {
//                e.printStackTrace();
            }

            // sleep for 400th second
            long elapsedNanos = System.nanoTime() - startTime;
            long sleepNanos =    2500000L - elapsedNanos;
//            long sleepNanos = 10000000L - elapsedNanos;
            if (sleepNanos > 0) {
                long sleepMillis = sleepNanos / 1000000L;
                sleepNanos -= sleepMillis * 1000000L;
                try {
                    Thread.sleep(sleepMillis, (int) sleepNanos);
                } catch (InterruptedException e) {
                }
            }
        }
        if(remoteUDPConnection!=null) {
            remoteUDPConnection.close();
            remoteUDPConnection = null;
        }
        if(remoteBTConnection!=null) {
            remoteBTConnection.close();
            remoteBTConnection= null;
        }

    }

    long lastBatteryTime = 0;
    float batteryPercent = 0;

    int messageCount = 0;
    long timeStart = 0;
    static float sMessagesPerSecond = 0f;

    // dispatch packet with an extra 4 bytes battery status on the end
    boolean dispatchPacket(DatagramChannel localConnection, ByteBuffer dataPacket)
    {
        long curTime = System.currentTimeMillis();
        if(messageCount == 0 || (sConnectionState & 3) == 0)
        {
            timeStart = curTime;
            messageCount = 0;

        } else
        {
            float timeDiff = (float) ((curTime - timeStart));
            sMessagesPerSecond = (1000.0f * (float) messageCount) / timeDiff;
            if(timeDiff>2000f)
            {
                timeStart=curTime;
                messageCount=0;
            }
        }
        messageCount += 1;
        mAngleDebug = dataPacket.getFloat(0);

        if(lastBatteryTime == 0 || (curTime - lastBatteryTime) > 10000)
        {
            lastBatteryTime = System.currentTimeMillis();
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            batteryPercent = level / (float) scale;
        }

        dataPacket.putFloat(GyroServerService.PACKET_SIZE, batteryPercent);
        dataPacket.putInt(GyroServerService.PACKET_SIZE+4, sConnectionState);
        try
        {
            dataPacket.rewind();
            // send to the server on port 2424
            localConnection.write(dataPacket);
            return true;
        } catch(IOException e2)
        {
            return false;
        }

    }


    void readSettings()
    {
        SharedPreferences settings = getSharedPreferences(GyroClientService.PREFS_NAME, 0);
        mBTAddr = settings.getString("BTADDR", "40:b8:37:6d:d3:1c");
        mIPAddr = settings.getString("UDPADDR", "192.168.43.1");
        mPort = settings.getInt("PORT", 2323);
        sTargetAddr = mIPAddr + ":" + mPort + ":" + mBTAddr;
        sWifiNum=settings.getInt("WIFI_NUM",-1);
        sSwingNum=settings.getInt("SWING_NUM",-1);
    }

    // returns true if it found this swing ID
    boolean connectToSwing(int swingNum)
    {
        mConnector.setSwingID(swingNum,sWifiNum);
        Pair<String,SocketAddress> connectorData=mConnector.GetData();
        if(connectorData.first!=null && connectorData.first.length()>0)
        {
            setSettingsFromText(this,connectorData.first,true);
            readSettings();
            sSettingsDirty=false;
            return true;
        }
        return false;
    }

    static void setWifiConnection(Context ctx, int wifiNum,int swingNum)
    {
        sSwingNum=swingNum;
        sWifiNum=wifiNum;
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor e = settings.edit();
        e.putInt("WIFI_NUM",wifiNum);
        e.putInt("SWING_NUM",swingNum);
        e.putString("BTADDR", "00:00:00:00:00:00");
        e.putString("UDPADDR", "1.1.1.1");
        e.commit();
        sSettingsDirty = true;
    }

    static void setSettings(Context ctx, String ipAddr, String btAddr, int port,long settingsTime,boolean onlyNewer)
    {
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        if(onlyNewer)
        {
            long curTime=settings.getLong("SETTINGS_TIME",-1);
            if(curTime>settingsTime)
            {
                return;
            }
        }
        SharedPreferences.Editor e = settings.edit();
        e.putString("UDPADDR", ipAddr);
        e.putInt("PORT", port);
        e.putString("BTADDR", btAddr);
        e.putLong("SETTINGS_TIME",settingsTime);
        e.apply();
        sSettingsDirty = true;
    }

    static boolean setSettingsFromText(Context ctx, String settingsText,boolean onlyNewer)
    {
        String[] addresses = settingsText.split(",");
        if(addresses.length >= 3)
        {
            long settingsTime=0;
            String ipAddr = addresses[0];
            int port = Integer.parseInt(addresses[1]);
            String btAddr = addresses[2].toUpperCase();
            if(addresses.length >= 5)
            {
                // swing ID
                // scan time
                settingsTime=Long.parseLong(addresses[4]);
            }
            setSettings(ctx, ipAddr, btAddr, port,settingsTime,onlyNewer);
            return true;
        }else
        {
            return false;
        }
    }

    static boolean setSettingsFromTag(Context ctx, Tag tag)
    {
        Log.d("tag", "found a tag!!!!");
        IsoDep dp = IsoDep.get(tag);
        if(dp != null)
        {
            try
            {
                dp.connect();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            byte[] queryData =
                    {0x00, (byte) 0xa4, 0x04, 0x00, 0x08, (byte) 0xF3, 0x23, 0x23, 0x23, 0x23, 0x23,
                            0x23, 0x23};
            try
            {
                byte[] response = dp.transceive(queryData);
                if(response[response.length - 2] == (byte) 0x90 &&
                        response[response.length - 1] == 0x00)
                {
                    byte[] truncated = Arrays.copyOf(response, response.length - 2);
                    String targetAddress = new String(truncated, "UTF8");

                    Log.d("tag", "response:" + targetAddress);
                    setSettingsFromText(ctx, targetAddress,false);
                    return true;
                }
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
        return false;

    }

}
