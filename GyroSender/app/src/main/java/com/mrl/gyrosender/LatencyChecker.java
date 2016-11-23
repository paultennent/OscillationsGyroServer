package com.mrl.gyrosender;

import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.TextView;


public class LatencyChecker extends Activity
{
    Handler mHandler;

    BluetoothTransport mTransport2=new BluetoothTransport();
    UDPTransport mTransport=new UDPTransport();
    //BLETransport mTransport=new BLETransport();

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_latency_checker);
        mHandler=new Handler();
        mHandler.post(new Runnable(){
            @Override
            public void run()
            {
                onUpdateTimer();
            }
        });

    }

    public void onUpdateTimer()
    {
        TextView tv= (TextView) findViewById(R.id.latency_results_text);
        tv.setText(mTransport.getClass().getSimpleName()+":"+mTransport.latencyInfo());
        TextView tv2= (TextView) findViewById(R.id.latency_results_text2);
        tv2.setText(mTransport2.getClass().getSimpleName()+":"+mTransport2.latencyInfo());
        mHandler.postDelayed(new Runnable(){
            @Override
            public void run()
            {
                onUpdateTimer();
            }
        },100);
    }


    public void onShutdownTransports(View view)
    {
        mTransport.shutdown();
        mTransport2.shutdown();
    }


    public void onSendUDP(View view)
    {
        mTransport.initLatencySend(GyroService.PACKET_SIZE,this);
    }

    public void onSendBT(View view)
    {
        mTransport2.initLatencySend(GyroService.PACKET_SIZE,this);
    }

    public void onRecvUDP(View view)
    {
        mTransport.initLatencyReceive(GyroService.PACKET_SIZE,"bt:40:b8:37:6d:d3:1c\nudp:192.168.43.1:2323\nble:gyrosender",this);
//        mTransport.initLatencyReceive(GyroService.PACKET_SIZE,"bt:40:b8:37:7e:51:cd\nudp:10.154.133.21:2323\nble:gyrosender",this);
    }

    public void onRecvBT(View view)
    {
        mTransport2.initLatencyReceive(GyroService.PACKET_SIZE,"bt:40:b8:37:7e:51:cd\nudp:192.168.43.1:2323\nble:gyrosender",this);
    }


    @Override
    protected void onDestroy()
    {
        mTransport.shutdown();
        mTransport2.shutdown();
        super.onDestroy();
    }

}
