package com.mrl.simplegyroserver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class GyroServerStarter extends Activity
{

    Handler m_Handler;

    NfcAdapter.CreateNdefMessageCallback mNDEFCallback=new NfcAdapter.CreateNdefMessageCallback()
    {
        @Override
        public NdefMessage createNdefMessage(NfcEvent event)
        {
            NdefRecord extRecord = NdefRecord
                    .createMime("application/com.mrl.simplegyroclient",
                                GyroServerService.getSettingsString(GyroServerStarter.this).getBytes(
                                        StandardCharsets.UTF_8));
            NdefRecord appRecord = NdefRecord.createApplicationRecord(
                    "com.mrl.simplegyroclient");
            NdefMessage msg = new NdefMessage(
                    extRecord,
                    appRecord);  // use the AAR as the *last* record in your NDEF message
            Log.d("woo","push NFC message"+msg.describeContents());
            return msg;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        m_Handler=new Handler();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gyro_starter);



        String bluetoothMAC=GyroServerService.getBluetoothMac(this);
        if(bluetoothMAC==null || bluetoothMAC.length()==0)
        {
            setBluetoothMAC();
        }

        TextView tv=(TextView)findViewById(R.id.info_text);
        final String ipAddr=GyroServerService.wifiIpAddress(this);
        tv.setText("Address: "+ipAddr+":"+ GyroServerService.UDP_PORT+"\n"+"BT:"+bluetoothMAC);

        // set NDEF record
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null)
        {
            nfcAdapter.setNdefPushMessageCallback(mNDEFCallback,this);
        }

        if(!GyroServerService.sRunning)
        {
            Intent intent = new Intent(getBaseContext(), GyroServerService.class);
            startService(intent);
        }

        checkServiceStatus();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.launcher_actions, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if(item.getItemId()==R.id.set_bluetooth_mac)
        {
            setBluetoothMAC();
            return true;
        }else
        {
            return super.onOptionsItemSelected(item);
        }
    }

    private void setBluetoothMAC()
    {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);
        final EditText editText = new EditText(this);
        alert.setMessage("You can get it from 'settings, about phone, status, and long press to copy it', you only have to do this once.");
        alert.setTitle("Please enter bluetooth Address");
        alert.setView(editText);

        alert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int whichButton) {
                String bluetoothMAC= editText.getText().toString();
                GyroServerService.setBluetoothMac(GyroServerStarter.this,bluetoothMAC);
            }
        });
        alert.show();

    }

    public void checkServiceStatus()
    {
        TextView tv=(TextView)findViewById(R.id.status_text);
        Button b=(Button)findViewById(R.id.launch_button);
        if(b==null || tv==null)
        {
            return;
        }
        if(GyroServerService.sRunning)
        {
            tv.setText("Service running:"+GyroServerService.sConnectionState+":"+(GyroServerService.sAngleDebug*57.296f)+":"+(GyroServerService.sCorrectionAmountDebug*57.296f));
            b.setText("Stop service");
        }else
        {
            tv.setText("Service not running");
            b.setText("Start service");
        }
        m_Handler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                checkServiceStatus();
            }
        }, 100);

    }

    @Override
    public void onDestroy()
    {
        m_Handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public void onClickLaunchButton(View view)
    {
        if(!GyroServerService.sRunning)
        {
            Intent intent= new Intent(getBaseContext(), GyroServerService.class);
            startService(intent);
        }else
        {
            Intent intent= new Intent(getBaseContext(), GyroServerService.class);
            stopService(intent);
        }
    }

}
/*
// stuff to auto-enable wifi AP

<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.WRITE_SETTINGS" />

public void setWiFiApMode(boolean mode) {
        if (mContext == null) return;
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager == null) return;
        try {
            Method setWifiApEnabled = WifiManager.class.getMethod("setWifiApEnabled", WifiConfiguration.class, boolean.class);
            setWifiApEnabled.invoke(wifiManager, null, mode);
        } catch (Exception e) {
        }
    }

*/