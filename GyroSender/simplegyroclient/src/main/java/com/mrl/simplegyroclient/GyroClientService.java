
package com.mrl.simplegyroclient;

import android.app.Service;
import android.bluetooth.*;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.UUID;

public class GyroClientService extends Service
{
    final static public UUID BLUETOOTH_UUID =
            UUID.fromString("ad91f8d4-e6ea-4f57-be70-4f9802ebc619");
    public static final String PREFS_NAME="ServicePrefs";
    public static boolean sRunning=false;
    public static boolean sSettingsDirty=false;
    public static String sTargetAddr="";

    public static int sConnectionState =0;

    public static final int LOCAL_PORT=2424;
    public static volatile float mAngleDebug=0;

    boolean mQuitting=false;



    Thread mServerThread;


    Handler mMainThreadHandler;

    public GyroClientService()
    {
    }

    @Override
    public void onCreate()
    {
        mMainThreadHandler=new Handler();
        sRunning = true;
        Log.d("woo", "create");
        mServerThread=new Thread()
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
        mMainThreadHandler.removeCallbacksAndMessages(null);
        mMainThreadHandler=null;
        Log.d("woo","service stop");
        mQuitting=true;
        try
        {
            mServerThread.interrupt();
            mServerThread.join(100);
        } catch(InterruptedException e)
        {
        }
        sRunning=false;
    }


        @Override
    public IBinder onBind(Intent intent)
    {
        // TODO: Return the communication channel to the service.
        throw new UnsupportedOperationException("Not yet implemented");
    }

    class BTClientConnector extends Thread
    {
        BluetoothDevice device;
        BluetoothSocket connection;
        InputStream inputStream;
        BluetoothAdapter adp;
        String addr;

        public BTClientConnector(BluetoothAdapter adaptor,String addr)
        {
            super();
            connection=null;
            adp=adaptor;
            this.addr=addr;
        }

        public void run()
        {

            try
            {
                adp.cancelDiscovery();
                device = adp.getRemoteDevice(addr.toUpperCase());
                connection=
                        device.createInsecureRfcommSocketToServiceRecord(BLUETOOTH_UUID);
//                connection = mSingletonSocket;
                connection.connect();
                inputStream = connection.getInputStream();
            } catch(IOException e)
            {
                 e.printStackTrace();
                if(connection != null && connection.isConnected())
                {
                    try
                    {
                        connection.close();
                    } catch(IOException e1)
                    {
                        e1.printStackTrace();
                    }
                    try
                    {
                        inputStream.close();
                    } catch(IOException e1)
                    {
                        e1.printStackTrace();
                    }
                    connection = null;
                    inputStream = null;
                }
            }

        }

        public void shutdownConnectThread()
        {
            interrupt();
            if(Thread.currentThread()!=BTClientConnector.this && isAlive())
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


    int mPort;
    String mIPAddr;
    String mBTAddr;

    public void serverThread()
    {
        BTClientConnector btConnector = null;


        DatagramChannel remoteConnection= null;
        DatagramChannel localConnection=null;
        try
        {
            remoteConnection = DatagramChannel.open();
            remoteConnection.socket().setSoTimeout(1);
            remoteConnection.configureBlocking(false);

            localConnection=DatagramChannel.open();
            localConnection.connect(new InetSocketAddress("127.0.0.1",LOCAL_PORT));
//            localConnection.configureBlocking(false);
//            localConnection.socket().bind(new InetSocketAddress(LOCAL_PORT));
        } catch(IOException e)
        {
            e.printStackTrace();
        }


        // timeouts for:
        // UDP receive

        boolean hasUDP=false;
        boolean hasBT=false;
        sSettingsDirty=true;

        long udpLastTime=System.currentTimeMillis();
        long udpPollTime=udpLastTime-1000;


        int sendErrorCounter=0;

        BluetoothManager mg= (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adp = mg.getAdapter();
        ByteBuffer dataPacket=ByteBuffer.allocate(28);


        ByteBuffer pollPacket=ByteBuffer.allocate(4);
        pollPacket.put((byte)72);
        pollPacket.put((byte)105);
        InetSocketAddress remoteAddr= null;

        BluetoothSocket btConnection=null;
        InputStream btInputStream=null;

        long lastTimestampBT=0;
        long lastTimestampUDP=0;

        long loopsSinceLastBT=0;

        boolean tryBTConnection=true;

        while(!mQuitting && !mServerThread.isInterrupted())
        {
            long curTime=System.currentTimeMillis();

            int connectionState=0;
            if(curTime-udpLastTime<500)connectionState|=1;
            if(btConnection!=null && btConnection.isConnected() && loopsSinceLastBT<1000)connectionState|=2;
            if(sendErrorCounter<50)connectionState|=4;
            if(btConnector!=null)connectionState|=8;
            sConnectionState =connectionState;

            if(sSettingsDirty)
            {
                sSettingsDirty=false;
                readSettings();
                try
                {
                    remoteAddr = new InetSocketAddress(InetAddress.getByName(mIPAddr), mPort);
                } catch(UnknownHostException e)
                {
                    e.printStackTrace();
                }
                // new settings - try bluetooth reconnect
                tryBTConnection=true;
                if(btConnection!=null)
                {
                    try
                    {
                        btConnection.close();
                    } catch(IOException e)
                    {
                    }
                }
            }

            // we would try bt connection any time it is dropped, but it causes jitters if we do
            // so only do on startup or settings change
            if(tryBTConnection && (btConnection==null || btConnection.isConnected()==false))
            {
                // connector object tries to do BT connection (this takes time so is in thread)
                if(btConnector==null)
                {
                    btConnector=new BTClientConnector(adp,mBTAddr.toUpperCase());
                    btConnector.start();
                }else
                {
                    // when the connection thread dies, we may or may not have a good connection
                    // just copy across whatever we have
                    if(!btConnector.isAlive())
                    {
                        loopsSinceLastBT=0;
                        btConnection=btConnector.connection;
                        btInputStream=btConnector.inputStream;
                        btConnector=null;
                        tryBTConnection=false;
                    }
                }
            }
            if(curTime-udpPollTime>=1000)
            {
                udpPollTime=curTime;
                try
                {
                    pollPacket.rewind();
                    remoteConnection.send(pollPacket,remoteAddr);
                } catch(IOException e)
                {
                    Log.d("poll","failed");
                    // poll failed
//                    e.printStackTrace();
                }
            }
            try
            {


                try
                {
                    Thread.sleep(1);
                } catch(InterruptedException e)
                {
                }
                if(btConnection!=null && btConnection.isConnected() && btInputStream!=null && btInputStream.available()>=24)
                {
                    dataPacket.rewind();
                    btInputStream.read(dataPacket.array(),0,24);
                    dataPacket.rewind();
                    lastTimestampBT=dataPacket.getLong(16);
                    if(lastTimestampBT>lastTimestampUDP)
                    {
                        if(dispatchPacket(localConnection,dataPacket))
                        {
                            sendErrorCounter-=1;
                            if(sendErrorCounter<0)sendErrorCounter=0;
                        }else
                        {
                            sendErrorCounter+=2;
                        }
                    }
                    loopsSinceLastBT=0;
                }else
                {
                    if(btConnection!=null)loopsSinceLastBT+=1;
                }
/*                if(loopsSinceLastBT>1000 && btConnection!=null)
                {
                    loopsSinceLastBT=0;
                    try
                    {
                        if(btInputStream!=null)
                        {
                            btInputStream.close();
                        }
                        if(btConnection!=null)
                        {
                            btConnection.close();
                        }
                    }catch(IOException e2)
                    {
                        e2.printStackTrace();
                    }
                    btInputStream=null;
                    btConnection=null;
                }*/
                dataPacket.rewind();
                SocketAddress serverAddr = remoteConnection.receive(dataPacket);
                if(serverAddr!=null)
                {
//                    Log.d("rcv",""+dataPacket);
                    udpLastTime = curTime;
                    dataPacket.rewind();
                    lastTimestampUDP=dataPacket.getLong(16);
                    if(lastTimestampUDP>lastTimestampBT)
                    {
                        if(dispatchPacket(localConnection,dataPacket))
                        {
                            sendErrorCounter-=1;
                            if(sendErrorCounter<0)sendErrorCounter=0;
                        }else
                        {
                            sendErrorCounter+=2;
                        }
                    }
                }
                if(sendErrorCounter > 1000 && !mQuitting)
                {
                    Log.d("snd", "failed for 1000 messages , shutting down");
                    mQuitting=true;
                    // stop service (on main thread) if we haven't been able to send to anyone for 10 seconds
                    mMainThreadHandler.post(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            stopSelf();
                        }
                    });
                    sendErrorCounter=0;
                }
            } catch(IOException e)
            {
                if(e instanceof java.nio.channels.ClosedByInterruptException)
                {
                    // ignore - thread is being closed
                }else
                {
                    e.printStackTrace();
                }
            }

        }
        if(btConnection!=null)
        {
            try
            {
                if(btInputStream!=null)
                {
                    btInputStream.close();
                }
                btConnection.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
        if(btConnector!=null)
        {
            btConnector.shutdownConnectThread();
        }
    }

    long lastBatteryTime=0;
    float batteryPercent=0;

    int messageCount=0;
    long timeStart=0;
    static float sMessagesPerSecond=0f;

    // dispatch packet with an extra 4 bytes battery status on the end
    boolean dispatchPacket(DatagramChannel localConnection,ByteBuffer dataPacket)
    {
        long curTime=System.currentTimeMillis();
        if(messageCount==0)
        {
            timeStart=curTime;

        }else
        {
            float timeDiff=(float)((curTime-timeStart));
            sMessagesPerSecond=(1000.0f*(float)messageCount)/timeDiff;
        }
        messageCount+=1;
        mAngleDebug = dataPacket.getFloat(0);

        if(lastBatteryTime==0 || (curTime-lastBatteryTime)>10000)
        {
            lastBatteryTime = System.currentTimeMillis();
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = registerReceiver(null, ifilter);
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);

            batteryPercent = level / (float) scale;
        }

        dataPacket.putFloat(24,batteryPercent);
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
        mBTAddr = settings.getString("BTADDR","40:b8:37:6d:d3:1c");
        mIPAddr =settings.getString("UDPADDR","192.168.43.1");
        mPort = settings.getInt("PORT",2311);
        sTargetAddr=mIPAddr+":"+mPort+":"+mBTAddr;
    }

    static void setSettings(Context ctx,String ipAddr,String btAddr,int port)
    {
        SharedPreferences settings = ctx.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor e=settings.edit();
        e.putString("UDPADDR",ipAddr);
        e.putInt("PORT",port);
        e.putString("BTADDR",btAddr);
        e.apply();
        sSettingsDirty=true;
    }

    static void setSettingsFromText(Context ctx, String settingsText)
    {
        String[] addresses=settingsText.split(",");
        if(addresses.length>=3)
        {
            String  ipAddr=addresses[0];
            int port=Integer.parseInt(addresses[1]);
            String btAddr=addresses[2].toUpperCase();
            setSettings(ctx,ipAddr,btAddr,port);
        }
        if(addresses.length>=4)
        {
            // get wifi direct name
//            String wifiDirectName=
        }

    }

    static boolean setSettingsFromTag(Context ctx,Tag tag)
    {
        Log.d("tag","found a tag!!!!");
        IsoDep dp=IsoDep.get(tag);
        if(dp!=null)
        {
            try
            {
                dp.connect();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            byte[] queryData= {0x00, (byte) 0xa4,0x04,0x00,0x08, (byte) 0xF3,0x23,0x23,0x23,0x23,0x23,0x23,0x23};
            try
            {
                byte[]response=dp.transceive(queryData);
                if(response[response.length-2]==(byte)0x90 && response[response.length-1]==0x00)
                {
                    byte[] truncated = Arrays.copyOf(response, response.length - 2);
                    String targetAddress = new String(truncated, "UTF8");

                    Log.d("tag", "response:" + targetAddress);
                    setSettingsFromText(ctx,targetAddress);
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