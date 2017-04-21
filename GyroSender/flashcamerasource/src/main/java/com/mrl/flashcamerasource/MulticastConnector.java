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

    String dataRecv=null;
    SocketAddress packetAddr=null;
    InetAddress group;

    final int PORT=6789;

    MulticastSocket socket;
    DatagramSocket sendSocket;
    public MulticastConnector(Context ctx,boolean receive)
    {
        WifiManager wm =
                (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        group = null;
        try
        {
            group = InetAddress.getByName("228.227.226.225");
        } catch(UnknownHostException e)
        {
        }
        if(receive)
        {
            multicastLock = wm.createMulticastLock("gyroservermulticastlock");
            multicastLock.acquire();

            try
            {
                socket = new MulticastSocket(PORT);
                socket.joinGroup(group);
                //socket.setTimeToLive(1);
            } catch(IOException e)
            {
                e.printStackTrace();
                socket = null;
            }
            // launch receiver thread
            start();
        }else
        {
            try
            {
                sendSocket=new DatagramSocket();
            } catch(SocketException e)
            {
                e.printStackTrace();
            }
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
            join(10);
        } catch(InterruptedException e)
        {

        }
        if(socket!=null)
        {
            socket.close();
            socket=null;
        }
        if(sendSocket!=null)
        {
            sendSocket.close();
            sendSocket=null;
        }
    }

    public void run()
    {
        byte[]dataBuf=new byte[1024];
        DatagramPacket pack=new DatagramPacket(dataBuf,dataBuf.length);
        try
        {
            socket.setSoTimeout(500);
        } catch(SocketException e)
        {
        }
        while(!mQuitting)
        {
            try
            {
                socket.receive(pack);
                String packetData = new String(pack.getData(), 0, pack.getLength(),"UTF-8");
                synchronized(this)
                {
                    dataRecv=packetData;
                    packetAddr=pack.getSocketAddress();
                    Log.d("multicast", "go multicast:"+packetData);
                }
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }
    }

    public void SendData(String data)
    {
        byte[] bytes = null;
        try
        {
            bytes = data.getBytes("UTF-8");
            DatagramPacket pack=new DatagramPacket(bytes,bytes.length);
            pack.setAddress(group);
            pack.setPort(PORT);
            sendSocket.send(pack);
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
