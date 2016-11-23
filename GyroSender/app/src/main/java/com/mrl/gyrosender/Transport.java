package com.mrl.gyrosender;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.util.Locale;

/**
 * Created by jqm on 09/11/2016.
 */

public abstract class Transport
{
    protected HandlerThread mConnectionThread;
    protected Handler mHandler;

    boolean mCheckLatency=false;


    protected int mConnectedClients=0;

    public int connectedClients()
    {
        return mConnectedClients;
    }

    interface ReceiveCallback
    {
        public void onReceive(byte[]data);
    };

    public abstract void initSender(int packetSize,Context parentContext);
    public abstract void initReceiver(int packetSize,String sourceAddresses,Context parentContext);

    public void shutdown()
    {
        if(mHandler!=null)
        {
            Handler h = mHandler;
            mHandler = null;
            h.removeCallbacksAndMessages(null);
        }
        if(mConnectionThread!=null)
        {
            mConnectionThread.quit();
            try
            {
                mConnectionThread.join();
            } catch(InterruptedException e)
            {
                e.printStackTrace();
            }
            mConnectionThread=null;
        }
    }

    public void initLatencyReceive(int packetSize,String sourceAddresses,Context parentContext)
    {
        mCheckLatency=true;
        initReceiver(packetSize,sourceAddresses,parentContext);
        mMeanLatencySum=0;
        mMeanLatencyCount=0;
        mMeanLatency=0;
        mMaxLatency=0;
        mMinLatency=0;
    }
    public void initLatencySend(int packetSize,Context parentContext)
    {
        mCheckLatency=true;
        initSender(packetSize,parentContext);
    }

    float mLatency=1.0f;
    float mMeanLatencySum =0;
    float mMeanLatencyCount =0;
    float mMeanLatency =0;
    float mMaxLatency =0;
    float mMinLatency =0;


    public float currentLatency()
    {
        return mLatency;
    }

    public String latencyInfo()
    {
        return String.format(Locale.ENGLISH,"cur: %.4f min:%.4f max:%.4f mean:%.4f", mLatency, mMinLatency,
                             mMaxLatency,
                             mMeanLatency);
    }

    protected void addLatencyValue(float latency)
    {
        mLatency=latency;
        if(mMeanLatencyCount ==0)
        {
            mMaxLatency =latency;
            mMinLatency =latency;
        }else
        {
            if(mMaxLatency <latency)
            {
                mMaxLatency =latency;
            }
            if(mMinLatency >latency)
            {
                mMinLatency =latency;
            }
        }
        mMeanLatencyCount +=1;
        mMeanLatencySum +=latency;
        mMeanLatency = mMeanLatencySum / mMeanLatencyCount;

    }

    public abstract void sendData(byte[] data);

    public abstract byte[] lastPacket();
}
