package com.mrl.boxupload;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.app.Activity;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.TextView;

import com.box.androidsdk.content.BoxConfig;
import com.box.androidsdk.content.auth.BoxAuthentication;
import com.box.androidsdk.content.models.BoxSession;
import com.mrl.simplegyroclient.R;

import java.io.*;
import java.nio.CharBuffer;

public class BoxUploader extends Activity implements BoxConnector.Callback
{
    BoxConnector mConnector;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        mConnector=new BoxConnector(this,this);
        setContentView(R.layout.activity_box_uploader);

        if(ContextCompat.checkSelfPermission(this,
                                             Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this,
                                              new String[]{
                                                      Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                                      Manifest.permission.READ_EXTERNAL_STORAGE},
                                              1);
        }else
        {
            connectBox();
        }


    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults)
    {
        connectBox();
    }

    void connectBox()
    {
        mConnector.connectBox();
    }

    public void onClickUploadButton(View view)
    {
        mConnector.checkUploadFiles();
    }

    @Override
    public void onStatusChange()
    {
        TextView tv=(TextView)findViewById(R.id.upload_status);
        tv.setText(mConnector.getStatusMessages());
    }
}
