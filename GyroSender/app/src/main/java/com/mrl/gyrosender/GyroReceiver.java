package com.mrl.gyrosender;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Parcelable;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

public class GyroReceiver extends Activity
{
    static UDPTransport mUDP;
    static BluetoothTransport mBT;
    static BLETransport mBLE;

    Handler mUpdateHandler;

    @Override
    protected void onDestroy()
    {
        if(mUDP != null)
        {
            mUDP.shutdown();
            mUDP = null;
        }
        if(mBT!= null)
        {
            mBT.shutdown();
            mBT = null;
        }
        if(mBLE != null)
        {
            mBLE.shutdown();
            mBLE= null;
        }
        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Intent launchIntent = getIntent();

        String targetAddress = "udp:192.168.1.101:2323\nbt:58:6D:A0:F3:A8:C7\nble:gyrosender\n";
//        String targetAddress = "udp:192.168.1.105:2323\nbt:40:b8:37:c3:4a:0a\n";
//        String targetAddress = "udp:192.168.1.105:2323\nbt:40:b8:37:6d:d3:1c\n";

        if(launchIntent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES))
        {
            Parcelable[] msgs =
                    launchIntent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES);
            NdefMessage[] nmsgs = new NdefMessage[msgs.length];
            for(int i = 0; i < msgs.length; i++)
            {
                nmsgs[i] = (NdefMessage) msgs[i];
                Log.d("nmsg", nmsgs[i].toString());
                if(nmsgs[i].getRecords().length > 0)
                {
                    try
                    {
                        targetAddress = new String(nmsgs[i].getRecords()[0].getPayload(), "UTF8");
                        Log.d("Payload", new String(nmsgs[i].getRecords()[0].getPayload(), "UTF8"));
                    } catch(UnsupportedEncodingException e)
                    {
                        Log.e("utf decode", e.getMessage());
                    }
                }

            }
        }
        setContentView(R.layout.activity_gyro_receiver);
        if(mUDP == null)
        {
            mUDP = new UDPTransport();
        }
/*        if(mBT == null)
        {
            mBT = new BluetoothTransport();
        }*/
        if(mBLE == null)
        {
            mBLE = new BLETransport();
        }
        mUDP.initReceiver(16,targetAddress,this);
//        mBT.initReceiver(16, targetAddress,this);
        mBLE.initReceiver(16,targetAddress,this);
        mUpdateHandler = new Handler();
        mUpdateHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                checkData();
            }
        }, 100);
    }

    void checkData()
    {
        byte[] data=mUDP.lastPacket();
        ByteBuffer dbb=ByteBuffer.wrap(data);
        float udpVal=dbb.getFloat();
        long udpTime=dbb.getLong();

        byte[] data2 = mBLE.lastPacket();
        ByteBuffer dbb2 = ByteBuffer.wrap(data2);
        float bleVal=dbb2.getFloat();
        long bleTime=dbb2.getLong();

        byte[] data3 = mBLE.lastPacket();
        ByteBuffer dbb3 = ByteBuffer.wrap(data3);
        float btVal=dbb3.getFloat();
        long btTime=dbb3.getLong();

//        Log.d("val","btval:"+btVal);
        Log.d("diff","u:"+udpVal+" bt:"+btVal+" dt:"+(udpTime-btTime));

        mUpdateHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                checkData();
            }
        }, 1000);
    }

}

