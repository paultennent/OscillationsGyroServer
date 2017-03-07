package com.mrl.simplegyroclient;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.mrl.flashcamerasource.BarcodeReader;
import com.mrl.flashcamerasource.ClientWifiSelector;

import java.io.UnsupportedEncodingException;
import java.util.Locale;

public class GyroClientStarter extends Activity implements NfcAdapter.ReaderCallback
{
    Handler m_Handler;
    BarcodeReader m_CodeReader=new BarcodeReader();

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        if(ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED )
        {
            String[] perms={Manifest.permission.READ_EXTERNAL_STORAGE};
            ActivityCompat.requestPermissions(this,perms,0);
        }


        m_Handler=new Handler();
        setContentView(R.layout.activity_gyro_reader);

        // get the target BT address and things from settings / store them if we have an NDEF intent
        Intent launchIntent = getIntent();
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
                        String targetAddress = new String(nmsgs[i].getRecords()[0].getPayload(), "UTF8");
                        GyroClientService.setSettingsFromText(this,targetAddress,false);
                    } catch(UnsupportedEncodingException e)
                    {
                        Log.e("utf decode", e.getMessage());
                    }
                }
            }
        }

        checkServiceStatus();

    }

    public void checkServiceStatus()
    {
        if(m_CodeReader.isReading())
        {
            if(m_CodeReader.getDetectedCode()!=null)
            {
                ClientWifiSelector sel=new ClientWifiSelector();
                sel.SelectNetworkForBarcode(this,m_CodeReader.getDetectedCode());
                m_CodeReader.stopReading();
            }
        }
        TextView tv=(TextView)findViewById(R.id.status_text);
        Button b=(Button)findViewById(R.id.launch_button);
        if(b==null || tv==null)
        {
            return;
        }
        if(GyroClientService.sRunning)
        {
            tv.setText(String.format(Locale.ENGLISH,"Receiver service running:%2d:%2.2f (%2.2f mps)\n%s",GyroClientService.sConnectionState,GyroClientService.mAngleDebug
            ,GyroClientService.sMessagesPerSecond,GyroClientService.sTargetAddr));

            b.setText("Stop Receive");
        }else
        {
            tv.setText("Receiver not running:");
            b.setText("Start receive");
        }
        m_Handler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                checkServiceStatus();
            }
        }, 10);

    }

    public void startBarcodeScanning()
    {
//        ClientWifiSelector sel=new ClientWifiSelector();
//        sel.SelectNetworkForBarcode(this,"020379056892");
        m_CodeReader.startReading(this);
    }

    @Override
    public void onDestroy()
    {
        m_Handler.removeCallbacksAndMessages(null);
        m_CodeReader.stopReading();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        NfcAdapter.getDefaultAdapter(this).enableReaderMode(this, this, NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK| NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        NfcAdapter.getDefaultAdapter(this).disableReaderMode(this);
    }


    public void onClickLaunchButton(View view)
    {
        if(!GyroClientService.sRunning)
        {
            Intent intent= new Intent(getBaseContext(), GyroClientService.class);
            startService(intent);
        }else
        {
            Intent intent= new Intent(getBaseContext(), GyroClientService.class);
            stopService(intent);
        }
    }

    @Override
    public void onTagDiscovered(Tag tag)
    {
        Log.d("tag","found a tag!!!!");
        if(GyroClientService.setSettingsFromTag(this,tag))
        {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME );
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD);
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
        if(item.getItemId()==R.id.barcode_test)
        {
            startBarcodeScanning();
            return true;
        }else
        {
            return super.onOptionsItemSelected(item);
        }
    }

}
