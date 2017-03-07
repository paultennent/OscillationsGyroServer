package com.mrl.simplegyroserver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import com.mrl.flashcamerasource.BarcodeReader;
import com.mrl.flashcamerasource.ServiceWifiChecker;

import java.nio.charset.StandardCharsets;

public class GyroServerStarter extends Activity
{

    BarcodeReader m_CodeReader=new BarcodeReader();

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

        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED)
        {
            String[] permissions={Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.CAMERA};
            ActivityCompat.requestPermissions(this,permissions,0);
        }else
        {
            afterCheckedPermissions();
        }
    }

    public void afterCheckedPermissions()
    {
        String bluetoothMAC=GyroServerService.getBluetoothMac(this);
        if(bluetoothMAC==null || bluetoothMAC.length()==0)
        {
            setBluetoothMAC();
        }

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

        m_CodeReader.startReading(this);

        checkServiceStatus();

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults)
    {
        boolean requestAgain=false;
        for(int c=0;c<grantResults.length;c++)
        {
            if(grantResults[c]!=PackageManager.PERMISSION_GRANTED)
            {
                requestAgain=true;
            }
        }
        if(requestAgain)
        {
            String[] newPermissions={Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.CAMERA};
            ActivityCompat.requestPermissions(this,newPermissions,0);
        }else
        {
            afterCheckedPermissions();
        }

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
        if(m_CodeReader.isReading())
        {
            String code=m_CodeReader.getDetectedCode();
            if(code!=null)
            {
                // check if it is a valid code
                try
                {
                    String withoutCheck=code.substring(0,code.length()-1);
                    int value = (int)(Long.parseLong(withoutCheck) % (10000000));
                    if(value>1000000)
                    {
                        // connect to the right wifi network - this codescheme allows for 100 wifis and any number of swings
                        int wifiNum=(value-1000000)%(1000);
                        int swingID=(value-1000000)/1000;
                        GyroServerService.setSwingId(this,swingID);
                        GyroServerService.setWifiNum(this,wifiNum);
                        m_CodeReader.stopReading();
                    }
                }catch(NumberFormatException e)
                {
                    e.printStackTrace();
                }
            }
            findViewById(R.id.barcode).setVisibility(View.VISIBLE);
            findViewById(R.id.launch_button).setVisibility(View.INVISIBLE);
            findViewById(R.id.status_text).setVisibility(View.INVISIBLE);
            findViewById(R.id.info_text).setVisibility(View.INVISIBLE);
        }else
        {
            findViewById(R.id.barcode).setVisibility(View.INVISIBLE);
            findViewById(R.id.launch_button).setVisibility(View.VISIBLE);
            findViewById(R.id.status_text).setVisibility(View.VISIBLE);
            findViewById(R.id.info_text).setVisibility(View.VISIBLE);
        }
        TextView tv_inf=(TextView)findViewById(R.id.info_text);
        final String ipAddr= ServiceWifiChecker.wifiIPAddress(this);
        tv_inf.setText("Address: "+ipAddr+":"+ GyroServerService.UDP_PORT+"\n"+"BT:"+GyroServerService.getBluetoothMac(this)+"\n"+GyroServerService.getSettingsString(this )
        +"\nWifi number:"+GyroServerService.getWifiNum(this)+"\n"+"swing num:"+GyroServerService.getSwingID(this));


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
        m_CodeReader.stopReading();
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
