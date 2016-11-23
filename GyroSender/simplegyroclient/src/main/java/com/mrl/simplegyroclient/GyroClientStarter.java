package com.mrl.simplegyroclient;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.nfc.NdefMessage;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

public class GyroClientStarter extends Activity implements NfcAdapter.ReaderCallback
{
    Handler m_Handler;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

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
                        GyroClientService.setSettingsFromText(this,targetAddress);
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

    @Override
    public void onDestroy()
    {
        m_Handler.removeCallbacksAndMessages(null);
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
}
