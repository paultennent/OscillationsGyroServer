package com.mrl.flashcamerasource;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.concurrent.locks.Lock;

/**
 * Created by jqm on 21/04/2017.
 */

public class MulticastConnector extends Thread
{
    private WifiManager.MulticastLock multicastLock=null;
    Thread t;
    boolean mQuitting=false;

    volatile boolean reloadSocket=false;

    String dataRecv=null;
    SocketAddress packetAddr=null;

    final int PORT=6789;

    public MulticastConnector(Context ctx,boolean receive)
    {
        WifiManager wm =
                (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        if(receive)
        {
            multicastLock = wm.createMulticastLock("gyroservermulticastlock");
            multicastLock.acquire();

            // launch receiver thread
            start();
        }
    }

    public void close()
    {
        if(multicastLock!=null)
        {
            multicastLock.release();
            multicastLock=null;
        }
        mQuitting=true;
        interrupt();
        try
        {
            join(100);
        } catch(InterruptedException e)
        {

        }
    }

    public void forceReloadSocket()
    {
        reloadSocket=true;
    }

    public void run()
    {
        MulticastSocket socket=null;
        byte[]dataBuf=new byte[1024];
        DatagramPacket pack=new DatagramPacket(dataBuf,dataBuf.length);

        InetAddress group = null;
        try
        {
            group = InetAddress.getByName("228.227.226.225");
        } catch(UnknownHostException e)
        {
        }

        while(!mQuitting)
        {
            if(socket==null)
            {
                try
                {
                    socket = new MulticastSocket(PORT);
                    socket.setSoTimeout(1000);
                    socket.joinGroup(group);
                    socket.setSoTimeout(50);
                    //socket.setTimeToLive(1);
                } catch(IOException e)
                {
                    e.printStackTrace();
                    socket = null;
                    try
                    {
                        Thread.sleep(500);
                    } catch(InterruptedException e1)
                    {
                        e1.printStackTrace();
                    }
                }
            }
            if(socket!=null)
            {
                try
                {
                    socket.receive(pack);
                    String packetData = new String(pack.getData(), 0, pack.getLength(), "UTF-8");
                    synchronized(this)
                    {
                        dataRecv = packetData;
                        packetAddr = pack.getSocketAddress();
                        Log.d("multicast", "got multicast:" + packetData);
                    }
                } catch(SocketTimeoutException e)
                {
                    if(reloadSocket)
                    {
                        socket.close();
                        socket=null;
                        reloadSocket=false;
                    }
                    // do nothing on timeout
                }catch(IOException e)
                {
                    e.printStackTrace();
                    if(socket!=null)
                    {
                        socket.close();
                    }
                    socket=null;
                }
            }
        }
        if(socket!=null)
        {
            socket.close();
        }
    }

    public void SendData(String data)
    {
        byte[] bytes = null;
        try
        {
            InetAddress group = null;
            try
            {
                group = InetAddress.getByName("228.227.226.225");
            } catch(UnknownHostException e)
            {
            }

            DatagramSocket sendSocket;
            sendSocket=new DatagramSocket();
            bytes = data.getBytes("UTF-8");
            DatagramPacket pack=new DatagramPacket(bytes,bytes.length);
            pack.setAddress(group);
            pack.setPort(PORT);
            if(sendSocket!=null)
            {
                sendSocket.send(pack);
                sendSocket.close();
            }
        } catch(IOException e)
        {
            Log.d("multicast", "couldn't send multicast:"+data);
        }
    }

    public Pair<String,SocketAddress> GetData()
    {
        String tmp=null;
        SocketAddress addr=null;
        synchronized(this)
        {
            tmp = dataRecv;
            dataRecv = null;
            addr=packetAddr;
            packetAddr=null;
        }
        return new Pair<String,SocketAddress>(tmp,addr);
    }
}
