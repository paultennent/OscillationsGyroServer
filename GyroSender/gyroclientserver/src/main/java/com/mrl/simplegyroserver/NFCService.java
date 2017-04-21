package com.mrl.simplegyroserver;

import android.app.Service;
import android.content.Intent;
import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class NFCService extends HostApduService
{

    private static final byte[] UNKNOWN_CMD_SW={0x00,0x00};
    // data plus 0x90,0x00 (SELECT_OK_SW)

    byte[] byteData=getBytesForString("192.168.43.1,2323,e8:e8:e8:e8:e8:e8");
    private static final byte[] SELECT_APDU= {0x00, (byte) 0xa4,0x04,0x00,0x08, (byte) 0xF3,0x23,0x23,0x23,0x23,0x23,0x23,0x23};

    @Override
    public void onCreate()
    {
        super.onCreate();

    }

    @Override
    public byte[] processCommandApdu(byte[] commandApdu, Bundle extras)
    {
        Log.d("tag", "tag command");
        if(Arrays.equals(commandApdu,SELECT_APDU))
        {
            String settingsString=GyroServerService.getSettingsString(this);
            Log.d("tag","Select APDU command");
            return getBytesForString(settingsString);
        }else
        {
            return UNKNOWN_CMD_SW;
        }
    }

    @Override
    public void onDeactivated(int reason)
    {

    }

    public static byte[]getBytesForString(String value)
    {
        byte[]utfBytes=value.getBytes(StandardCharsets.UTF_8);
        byte[]response=Arrays.copyOf(utfBytes,utfBytes.length+2);
        response[response.length-2]=(byte)0x90;
        response[response.length-1]=(byte)0x00;
        return response;
    }
}
