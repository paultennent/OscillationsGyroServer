package com.mrl.gyrosender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;

public class ServiceLauncher extends Activity
{
    final String PREFS_NAME="launcherPrefs";
    private String bluetoothMAC;

    protected String wifiIpAddress() {
        WifiManager wifiManager = (WifiManager) getSystemService(WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

        // Convert little-endian to big-endianif needed
        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
        } catch (UnknownHostException ex) {
            Log.e("WIFIIP", "Unable to get host address.");
            // check if we're an access point
            ipAddressString="192.168.43.1";
            try
            {
                for(Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                    en.hasMoreElements(); )
                {
                    NetworkInterface ni=en.nextElement();
                    if(!ni.isLoopback() && !ni.isPointToPoint() && ni.isUp() && !ni.isVirtual())
                    {
                        for (Enumeration<InetAddress> enumIpAddr = ni.getInetAddresses();
                             enumIpAddr.hasMoreElements(); )
                        {
                            InetAddress ad=enumIpAddr.nextElement();
                            if(ad.getHostAddress()!="" && ad.getAddress().length==4)
                            {
                                ipAddressString=ad.getHostAddress();
                            }
                        }
                    }
                }
            } catch(SocketException e)
            {
                e.printStackTrace();
            }
        }

        return ipAddressString;
    }

    Handler m_Handler;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        m_Handler=new Handler();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service_launcher);


        Intent intent= new Intent(getBaseContext(), GyroService.class);
        startService(intent);


        SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
        bluetoothMAC=settings.getString("BluetoothMAC",null);
        if(bluetoothMAC==null)
        {
            setBluetoothMAC();
        }

        TextView tv=(TextView)findViewById(R.id.info_text);
        final String ipAddr=wifiIpAddress();
        tv.setText("Address: "+ipAddr+":"+UDPTransport.SERVICE_PORT+"\n"+"BT:"+bluetoothMAC);


        // set NDEF record
        NfcAdapter nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter != null)
        {
            nfcAdapter.setNdefPushMessageCallback(new NfcAdapter.CreateNdefMessageCallback()
                                                  {
                                                      @Override
                                                      public NdefMessage createNdefMessage(NfcEvent event)
                                                      {
                                                          NdefRecord extRecord = NdefRecord
                                                                  .createMime("application/com.mrl.gyroservice",
                                                                              ("udp:"+ipAddr+":"+UDPTransport.SERVICE_PORT+"\n"+"bt:"+bluetoothMAC+"\n").getBytes(StandardCharsets.UTF_8));
                                                          NdefRecord appRecord = NdefRecord.createApplicationRecord(
                                                                  "com.mrl.gyrosender");
                                                          NdefMessage msg = new NdefMessage(
                                                                  extRecord,
                                                                  appRecord);  // use the AAR as the *last* record in your NDEF message
                                                          Log.d("woo","push NFC message"+msg.describeContents());
                                                          return msg;
                                                      }
                                                  },this);
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
                SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
                bluetoothMAC= editText.getText().toString();
                SharedPreferences.Editor edit = settings.edit();
                edit.putString("BluetoothMAC",bluetoothMAC);
                edit.commit();
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
        if(GyroService.bRunning)
        {
            tv.setText("Service running");
            b.setEnabled(false);
        }else
        {
            tv.setText("Service not running");
            b.setEnabled(true);
        }
        m_Handler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                checkServiceStatus();
            }
        }, 1000);

    }

    @Override
    public void onDestroy()
    {
        m_Handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    public void onClickLaunchButton(View view)
    {
        if(!GyroService.bRunning)
        {
            Intent intent= new Intent(getBaseContext(), GyroService.class);
            startService(intent);
        }
    }
}
