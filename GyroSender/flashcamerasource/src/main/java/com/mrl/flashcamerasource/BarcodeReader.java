package com.mrl.flashcamerasource;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;
import com.mrl.flashcamerasource.CameraSource;

import java.io.IOException;


/**
 * Created by jqm on 03/03/2017.
 */

public class BarcodeReader implements Detector.Processor<Barcode>
{
    BarcodeDetector mDetector;
    CameraSource mCodeCam;

    volatile String mCapturedCode = null;

    public boolean startReading(Activity owner)
    {
        BarcodeDetector.Builder codeBuilder = new BarcodeDetector.Builder(owner);
        codeBuilder.setBarcodeFormats(64|32);
        mDetector = codeBuilder.build();
        mDetector.setProcessor(this);

        CameraSource.Builder camBuilder = new CameraSource.Builder(owner,mDetector)
            .setFacing(CameraSource.CAMERA_FACING_BACK)
            .setRequestedFps(15f)
            .setFlashMode(Camera.Parameters.FLASH_MODE_TORCH)
            .setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            //.setRequestedPreviewSize(1600,1024);
        mCodeCam = camBuilder.build();
        try
        {
            if(ActivityCompat.checkSelfPermission(owner, Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED)
            {
                mCodeCam.release();
                mCodeCam=null;
                String[] permissions={Manifest.permission.CAMERA};
                ActivityCompat.requestPermissions(owner,permissions,0);
                return false;
            }
            mCodeCam.start();
            return true;
        } catch(IOException e)
        {
            mCodeCam=null;
            e.printStackTrace();
        }
        return false;
    }

    public void stopReading()
    {
        if(mCodeCam != null)
        {
            mCapturedCode="";
            mCodeCam.release();
            mCodeCam = null;
        }
    }

    public String getDetectedCode()
    {
        if(mCapturedCode != null && mCapturedCode != "")
        {
            Log.d("code", mCapturedCode);
            return mCapturedCode;
        } else
        {
            return null;
        }
    }

    public boolean isReading()
    {
        return mCodeCam!=null;
    }


    @Override
    public void release()
    {

    }

    @Override
    public void receiveDetections(Detector.Detections<Barcode> detections)
    {
        SparseArray<Barcode> items=detections.getDetectedItems();
        for(int c=0;c<items.size();c++)
        {
            Barcode code=items.valueAt(c);
            if(code!=null)
            {
                mCapturedCode=code.rawValue;
            }
        }
    }
}