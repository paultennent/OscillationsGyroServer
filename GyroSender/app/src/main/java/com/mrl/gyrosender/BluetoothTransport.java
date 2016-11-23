package com.mrl.gyrosender;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by jqm on 09/11/2016.
 */

public class BluetoothTransport extends Transport
{
    final static public UUID BLUETOOTH_UUID =
            UUID.fromString("ad91f8d4-e6ea-4f57-be70-4f9802ebc619");


    boolean sendBufferDirty=false;
    byte[] sendBuffer;
    byte[] sendCopyBuffer;


    byte[] recvBuffer;
    byte[] lastMsg;
    byte[] lastMsgCopy;
    String btAddr = "";
    boolean shuttingDown = false;

    protected BluetoothDevice mRemoteDevice;
    protected BluetoothSocket mSocket;
    protected BluetoothServerSocket mServerSocket;
    protected InputStream mIS;
    protected OutputStream mOS;

    @Override
    public void initSender(int packetSize, Context parentContext)
    {
        sendBuffer = new byte[packetSize];
        sendCopyBuffer = new byte[packetSize];
        if(mConnectionThread != null)
        {
            shutdown();
        }

        mConnectionThread = new HandlerThread("btsend")
        {
            public void onLooperPrepared()
            {
                mHandler = new Handler(getLooper());
                btSendThread();
            }
        };
        mConnectionThread.start();


    }

    Runnable btSendRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            btSendThread();
        }
    };

    private void btSendThread()
    {
        BluetoothAdapter mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if(mSocket == null || mSocket.isConnected() == false)
        {
            if(mServerSocket == null)
            {
                try
                {
                    mServerSocket = mBluetoothAdapter
                            .listenUsingInsecureRfcommWithServiceRecord("Gyro service",
                                                                        BLUETOOTH_UUID);
                } catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
            try
            {
                mSocket = mServerSocket.accept(100);
                mOS = mSocket.getOutputStream();
                mIS = mSocket.getInputStream();
                Log.d("send", "accepted");
                mServerSocket.close();
                mServerSocket = null;
            } catch(IOException e)
            {
//                e.printStackTrace();
            }
            // if we're waiting for connection, loop straight away because we wait for 100 above
            if(mHandler != null)
            {
                mHandler.postDelayed(btSendRunnable, 1);
            }

        } else
        {
            if(mCheckLatency && mSocket != null && mOS != null && mSocket.isConnected())
            {
                try
                {
                    mIS.read(sendCopyBuffer);
                    mOS.write(sendCopyBuffer);
                    mOS.flush();
                }catch(IOException e)
                {
                    Log.d("latency",e.getMessage());
                    closeSenderSocket();
                }
            }
            // we are connected, check in a while if we need to reconnect
            if(mSocket != null && mOS != null && mSocket.isConnected())
            {

                if(!mCheckLatency)
                {
                    if(sendBufferDirty)
                    {
                        synchronized(sendBuffer)
                        {
                            sendBufferDirty = false;
                            System.arraycopy(sendBuffer, 0, sendCopyBuffer, 0, sendBuffer.length);
                        }
                        ;

                        try
                        {
                            mOS.write(sendCopyBuffer);
                            mOS.flush();
                        } catch(IOException e)
                        {
                            closeSenderSocket();
                            ;
                        }
                    }
                }
            }
        }
        if(mHandler != null)
        {
            mHandler.post(btSendRunnable);
//            mHandler.postDelayed(btSendRunnable, 1);
        }
    }

    private void closeSenderSocket()
    {
        try
        {
            if(mOS != null) mOS.close();
            if(mIS != null) mIS.close();
            mSocket.close();
        } catch(IOException e1)
        {
        }
        mOS = null;
        mIS = null;
        mSocket = null;
    }

    @Override
    public void initReceiver(int packetSize, String sourceAddresses, Context parentContext)
    {
        if(mConnectionThread != null)
        {
            shutdown();
        }


        btAddr = "";
        Pattern p = Pattern.compile(
                "^bt:([A-F0-9a-f]+:[A-F0-9a-f]+:[A-F0-9a-f]+:[A-F0-9a-f]+:[A-F0-9a-f]+:[A-F0-9a-f]+)");
        Matcher m = p.matcher(sourceAddresses);
        if(m.find())
        {
            btAddr = m.group(1).toUpperCase(Locale.ENGLISH);
        }

        recvBuffer = new byte[packetSize];
        lastMsg = new byte[packetSize];
        lastMsgCopy = new byte[packetSize];

        BluetoothAdapter adp = BluetoothAdapter.getDefaultAdapter();
        mRemoteDevice = adp.getRemoteDevice("40:b8:37:6d:d3:1c".toUpperCase());
        mSocket = null;

        mConnectionThread = new HandlerThread("btrecv")
        {
            public void onLooperPrepared()
            {
                mHandler = new Handler(getLooper());
                btReceive();
            }
        };
        mConnectionThread.start();


    }

    Runnable btReceiveRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            btReceive();
        }
    };


    protected void btReceive()
    {
        if(mSocket == null || !mSocket.isConnected())
        {
            try
            {
                mSocket = mRemoteDevice
                        .createInsecureRfcommSocketToServiceRecord(BLUETOOTH_UUID);
                mSocket.connect();
                mIS = mSocket.getInputStream();
                mOS = mSocket.getOutputStream();
            } catch(IOException e)
            {

            }
        }
        if(mSocket != null && mSocket.isConnected())
        {
            if(mCheckLatency)
            {
                try
                {
                    int testVal = new Random().nextInt(64);
                    recvBuffer[0] = (byte) testVal;
                    long timeStart = System.nanoTime();
                    mOS.write(recvBuffer);
                    mOS.flush();
                    mIS.read(recvBuffer);
                    long timeEnd = System.nanoTime();
                    if(recvBuffer[0] == testVal)
                    {
                        float timeSeconds = 0.000000001f * (float) (timeEnd - timeStart);
                        addLatencyValue(timeSeconds);
                    }
                } catch(IOException e)
                {
                    try
                    {
                        mIS.close();
                        mOS.close();
                        mSocket.close();
                    } catch(IOException e1)
                    {
                    }
                    mSocket = null;
                }
            } else
            {

                try
                {
                    mIS.read(recvBuffer);
                    synchronized(lastMsg)
                    {
                        System.arraycopy(recvBuffer, 0, lastMsg, 0, lastMsg.length);
                    }
                } catch(IOException e)
                {
                    try
                    {
                        mIS.close();
                        mOS.close();
                        mSocket.close();
                    } catch(IOException e1)
                    {
                    }
                    mSocket = null;
                }
            }
        }

        if(mHandler != null)
        {
            mHandler.postDelayed(btReceiveRunnable, 1);
        }
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        if(mSocket != null)
        {
            try
            {
                mSocket.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            mSocket = null;
        }
        if(mServerSocket != null)
        {
            try
            {
                mServerSocket.close();
            } catch(IOException e)
            {
                e.printStackTrace();
            }
            mServerSocket = null;
        }
    }

    @Override
    public void sendData(byte[] data)
    {
        // copy into out buffer
        synchronized(sendBuffer)
        {
            System.arraycopy(data, 0, sendBuffer, 0, sendBuffer.length);
            sendBufferDirty=true;
        }
    }

    @Override
    public byte[] lastPacket()
    {
        synchronized(lastMsg)
        {
            System.arraycopy(lastMsg, 0, lastMsgCopy, 0, lastMsg.length);
        }
        return lastMsgCopy;
    }


}
