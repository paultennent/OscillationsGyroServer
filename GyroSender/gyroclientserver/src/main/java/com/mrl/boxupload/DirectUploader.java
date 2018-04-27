package com.mrl.boxupload;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.RequestParams;
import com.loopj.android.http.ResponseHandlerInterface;
import com.loopj.android.http.SyncHttpClient;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.Header;

/**
 * Created by jqm on 26/04/2018.
 *
 * Class to upload direct to server (rather than to box)
 */

public class DirectUploader
{
    private final Handler mHandler;

    public interface Callback
    {
        void onStatusChange();
    };


    File configFile = new File("/sdcard/uploadconfig.txt");
    File sourceFolder = new File("/sdcard/vrplayground-logs");
    String lastFileMessage = "";
    String targetURL = "";
    SyncHttpClient client;
    Context mContext;
    Callback mCallback;

    DirectUploader(Context context,Callback callback)
    {
        mHandler = new Handler();        
        mCallback=callback;
        mContext = context;
        client = new SyncHttpClient();
        try
        {
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            targetURL = reader.readLine().trim();
        } catch (IOException e)
        {
            lastFileMessage = "No uploadconfig.txt";
            e.printStackTrace();
        }


    }

    void checkUploadFiles()
    {
       new Thread()
        {
            @Override
            public void run()
            {
                checkUploadFilesOnThisThread();
            }
        }.start();
    }

    void checkUploadFilesOnThisThread()
    {
        File[] files = sourceFolder.listFiles();
        if (files != null)
        {
            for (int i = 0; i < files.length; i++)
            {
                String curName = files[i].getAbsolutePath();
                File uploadTag = new File(curName + ".upload");
                if (!curName.endsWith(".upload") && !uploadTag.exists())
                {
                    tryUploadFile(files[i]);
                }
            }
        }
    }

    void tryUploadFile(final File curFile)
    {
        Pattern p = Pattern.compile("(\\d\\d\\d\\d\\d\\d\\d\\d).*");
        final String curName = curFile.getAbsolutePath();

        try
        {

            Matcher datematch = p.matcher(curFile.getName());
            if (datematch.matches())
            {
                String folderName = datematch.group(1);
                String filename = curFile.getName();
                final ResponseHandlerInterface ri = new AsyncHttpResponseHandler()
                {
                    @Override
                    public void onSuccess(int statusCode, Header[] headers, byte[] responseBody)
                    {
                        Log.e("WOO","WOO");
                        File uploadMarker = new File(curName + ".upload");
                        try
                        {
                            uploadMarker.createNewFile();
                            lastFileMessage = "Uploaded:" + curFile.getName();
                            onStatusChange();
                        } catch (IOException e)
                        {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, byte[] responseBody, Throwable error)
                    {
                        Log.e("boo","boo");
                        lastFileMessage = "Upload failed("+statusCode+"):" + curFile.getName();
                        onStatusChange();

                    }
                };
                RequestParams params = new RequestParams();
                params.put("filename", filename);
                params.put("foldername", folderName);
                params.put("uploaddata", curFile);
                client.post(mContext, targetURL, params, ri);
            }
        } catch (IOException e)
        {

        }
    }
    
    private void onStatusChange()
    {
        mHandler.post(new Runnable(){public void run(){ mCallback.onStatusChange();}});
    }


    public String getStatusMessages()
    {
        // count files uploaded
        File[] files = sourceFolder.listFiles();
        int numFiles=0;
        int uploadedFiles=0;
        if(files != null)
        {
            for(int i = 0; i < files.length; i++)
            {
                String curName = files[i].getAbsolutePath();
                File uploadTag = new File(curName + ".upload");
                if(!curName.endsWith(".upload"))
                {
                    numFiles+=1;
                    if(uploadTag.exists())
                    {
                        uploadedFiles+=1;
                    }
                }
            }
        }
        String retVal=String.format("%s\n%d files (%d left to upload)\n",lastFileMessage,numFiles,(numFiles-uploadedFiles));
        return retVal;
    }

}
