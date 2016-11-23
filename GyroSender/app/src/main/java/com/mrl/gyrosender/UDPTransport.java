package com.mrl.gyrosender;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by jqm on 09/11/2016.
 */

public class UDPTransport extends Transport
{
    final static public int SERVICE_PORT=2323;
    DatagramSocket mSocket;

    byte []lastMsg;
    byte[]lastMsgCopy;


    byte []recvBuffer;
    DatagramPacket recvPacket;

    byte[]sendBuffer;
    DatagramPacket sendPacket;
    byte []sendCopyBuffer;


    class Target
    {
        InetAddress addr;
        long lastTimeSeen;
        int port;
    };

    HashMap<InetAddress,Target> mTargets=new HashMap<InetAddress, Target>();


    Target mServer= new Target();

    @Override
    public void initSender(int packetSize,Context parentContext)
    {
        if(mConnectionThread!=null)
        {
            shutdown();
        }
        recvBuffer=new byte[packetSize];
        lastMsg=new byte[packetSize];
        lastMsgCopy=new byte[packetSize];
        recvPacket=new DatagramPacket(recvBuffer,packetSize);
        sendBuffer=new byte[packetSize];
        sendCopyBuffer=new byte[packetSize];
        sendPacket=new DatagramPacket(sendCopyBuffer,packetSize);
        mConnectionThread=new HandlerThread("udpsend")
        {
            public void onLooperPrepared()
            {
                mHandler=new Handler(getLooper());
                sendLoop();
            }
        };
        mConnectionThread.start();
    }


    Runnable sendLoopRunnable=new Runnable()
    {
        @Override
        public void run()
        {
            sendLoop();
        }
    };
    // for UDP, any incoming messages are a connection (we just send back to where we got it from)
    void sendLoop()
    {
        try
        {
            if(mSocket==null)
            {
                mSocket = new DatagramSocket(SERVICE_PORT);
                mSocket.setSoTimeout(20);
            }
            if(mSocket!=null)
            {
                mSocket.receive(recvPacket);
                if(mCheckLatency)
                {
                    // bounce it back
                    mSocket.send(recvPacket);
                }
                InetAddress addr = recvPacket.getAddress();
                long tm = System.currentTimeMillis();
                Target t = new Target();
                t.addr = addr;
                t.lastTimeSeen = tm;
                t.port=recvPacket.getPort();
                mTargets.put(addr, t);
                mConnectedClients=mTargets.size();
    //            setNotification(mTargets.size()+" clients connected");
            }
        }catch(SocketTimeoutException te)
        {
        }catch(IOException ie)
        {
        }
        // send the current data buffer
        if(mSocket!=null && !mCheckLatency)
        {
            synchronized(sendBuffer)
            {
                System.arraycopy(sendBuffer, 0, sendCopyBuffer, 0, sendBuffer.length);
            }
            for(Target t : mTargets.values())
            {
                sendPacket.setAddress(t.addr);
                sendPacket.setPort(t.port);

                try
                {
                    mSocket.send(sendPacket);
                } catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }


        if(mHandler!=null)
        {
            mHandler.postDelayed(sendLoopRunnable,1);
        }
    }

    @Override
    public void initReceiver(int packetSize,String sourceAddresses,Context parentContext)
    {
        if(mConnectionThread!=null)
        {
            shutdown();
        }
        recvBuffer=new byte[packetSize];
        lastMsg=new byte[packetSize];
        lastMsgCopy=new byte[packetSize];
        recvPacket=new DatagramPacket(recvBuffer,packetSize);
        sendBuffer=new byte[packetSize];
        sendPacket=new DatagramPacket(sendBuffer,packetSize);


        Pattern p=Pattern.compile("udp:([^:*]+):([0-9]+)");
        Matcher m=p.matcher(sourceAddresses);
        if(m.find())
        {
            try
            {
                mServer.addr = InetAddress.getByName(m.group(1));
            } catch(UnknownHostException e)
            {
                e.printStackTrace();
            }
            Log.d("udp", "server addr:" + m.group(0) + "*" + m.group(1) + "*" + m.group(2));
            try
            {
                mServer.port = Integer.parseInt(m.group(2));
            } catch(NumberFormatException e)
            {
                e.printStackTrace();
                mServer.port=2323;
            }
        }

        try
        {
            mSocket = new DatagramSocket();
            mSocket.setSoTimeout(100);
        } catch(SocketException e)
        {
            e.printStackTrace();
        }
        mConnectionThread=new HandlerThread("udprecv")
        {
            public void onLooperPrepared()
            {
                mHandler=new Handler(getLooper());
                receiveLoop();
            }
        };
        mConnectionThread.start();
    }

    Runnable receiveLoopRunnable=new Runnable()
    {
        @Override
        public void run()
        {
            receiveLoop();
        }
    };

    int receiveCounter=0;
    void receiveLoop()
    {
        if((receiveCounter&127)==0)
        {
            // send keep alive so server knows to send us data
            sendBuffer[0] = 72;//H
            sendBuffer[1] = 105;//i
            sendBuffer[2] = 0;
            sendBuffer[3] = 0;
            sendPacket.setAddress(mServer.addr);
            sendPacket.setPort(mServer.port);
            try
            {
                if(mCheckLatency)
                {
                    long timeStart=System.nanoTime();
                    // just ping and response
                    try
                    {
                        int testVal = new Random().nextInt(64);
                        sendBuffer[4]=(byte)testVal;
                        mSocket.send(sendPacket);
                        mSocket.receive(recvPacket);
                        long timeEnd = System.nanoTime();
                        if(recvBuffer[4]==testVal)
                        {
                            float timeSeconds = 0.000000001f * (float) (timeEnd - timeStart);
                            addLatencyValue(timeSeconds);
                        }
                    }catch(IOException e)
                    {
                        Log.d("latency err:",e.toString());
                    }
                }else
                {
                    mSocket.send(sendPacket);
                }
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
        if(!mCheckLatency)
        {
            try
            {
                for(int c = 0; c < 10; c++)
                {
                    mSocket.receive(recvPacket);
                    synchronized(lastMsg)
                    {
                        System.arraycopy(recvBuffer, 0, lastMsg, 0, lastMsg.length);
                    }
                }
            } catch(IOException e)
            {
            }
            if(mHandler!=null)
            {
                mHandler.postDelayed(receiveLoopRunnable,1);
            }
        }else
        {
            if(mHandler != null)
            {
                mHandler.postDelayed(receiveLoopRunnable,100);
            }
        }
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        if(mSocket!=null)
        {
            mSocket.close();
            mSocket=null;
        }
    }

    @Override
    public void sendData(byte[] data)
    {
        synchronized(sendBuffer)
        {
            System.arraycopy(data, 0, sendBuffer, 0, sendBuffer.length);
        }
    }

    // this code assumes lastmsgcopy is only looked at from one thread
    @Override
    public byte[] lastPacket()
    {
        synchronized(lastMsg)
        {
            System.arraycopy(lastMsg,0,lastMsgCopy,0,lastMsg.length);
        }
        return lastMsgCopy;
    }
}
