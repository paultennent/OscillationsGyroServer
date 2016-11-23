package com.mrl.simplegyroclient;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.IsoDep;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;

public class NFCDiscoveredActivity extends Activity
{

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Intent nDefIntent=getIntent();
        Tag tag=(Tag)nDefIntent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if(tag!=null)
        {
            if(GyroClientService.setSettingsFromTag(this,tag))
            {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, ToneGenerator.MAX_VOLUME );
                tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD);
            }

        }
        // leave the activity
        finish();
    }
}
