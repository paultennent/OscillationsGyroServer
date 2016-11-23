package com.mrl.gyrosender;

import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.os.Handler;
import android.util.Log;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by jqm on 09/11/2016.
 */

public class BLETransport extends Transport
{
    final static boolean LOG_DEBUG=false;
    
    final static public UUID BLE_UUID=
            UUID.fromString("ca58a7c7-25ed-41ca-8173-cf9b4f5584f8");
    final static public UUID BLE_CHARACTERISTIC=
            UUID.fromString("86c9b27f-e75a-4332-866d-c872b2dfaffb");
    byte []sendBuffer;
    byte []sendCopyBuffer;
    byte []lastMsg;
    byte[]lastMsgCopy;


    BluetoothGattServer mGattServer;
    BluetoothLeScanner mScanner;
    String btName ="";
    BluetoothLeAdvertiser advertiser;
    AdvertiseCallback advertiseCallback;
    BluetoothGattCharacteristic mReadCharacteristic;
    BluetoothGatt mGatt;


    HashMap<BluetoothDevice,Integer> mConnectedDevices=new HashMap<BluetoothDevice,Integer>();

    BluetoothGattServerCallback serverCallback =new BluetoothGattServerCallback()
    {

        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState)
        {
            if(LOG_DEBUG)Log.d("ble","connection state changed:"+newState);
            if(newState==BluetoothGattServer.STATE_CONNECTED)
            {
                mConnectedDevices.put(device,newState);
            }else
            {
                mConnectedDevices.remove(device);
            }
            super.onConnectionStateChange(device, status, newState);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic)
        {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            synchronized(sendBuffer)
            {
                System.arraycopy(sendBuffer, 0, sendCopyBuffer, 0, sendBuffer.length);
            };
            Log.d("ble", "onCharacteristicReadRequest ");
            if(LOG_DEBUG)Log.d("ble", "onCharacteristicReadRequest ");
            mGattServer.sendResponse(device,
                                     requestId,
                                     BluetoothGatt.GATT_SUCCESS,
                                     0,
                                     sendCopyBuffer);
        }
    };

    private BluetoothGattService mService;
    private BluetoothAdapter mAdapter;
    private BluetoothDevice mDev;
    private BluetoothGattCharacteristic mSendCharacteristic;

    public BluetoothGattCharacteristic buildCharacteristic()
    {
        BluetoothGattCharacteristic r=new BluetoothGattCharacteristic(BLE_CHARACTERISTIC,BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_NOTIFY,BluetoothGattCharacteristic.PERMISSION_READ);
        return r;
    }

    public BluetoothGattService buildService()
    {
        BluetoothGattService r=new BluetoothGattService(BLE_UUID,BluetoothGattService.SERVICE_TYPE_PRIMARY);
        mSendCharacteristic=buildCharacteristic();
        r.addCharacteristic(mSendCharacteristic);
        return r;
    }


    Context mContext;

    @Override
    public void initSender(int packetSize,Context context)
    {
        mContext=context;
        sendBuffer=new byte[packetSize];
        sendCopyBuffer=new byte[packetSize];

        BluetoothManager mg= (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter=mg.getAdapter();

        mGattServer=mg.openGattServer(context, serverCallback);
        mService=buildService();
        BluetoothGattService mOldService=mGattServer.getService(mService.getUuid());
        if(mOldService!=null)
        {
            mGattServer.removeService(mOldService);
        }
        boolean serviceAdded=mGattServer.addService(mService);
        if(LOG_DEBUG)Log.d("gatt","service added:"+serviceAdded);
        BluetoothLeAdvertiser  advertiser=mAdapter.getBluetoothLeAdvertiser();
        mAdapter.setName("gyrosender");
        AdvertiseData ad = new AdvertiseData.Builder().setIncludeDeviceName(true).build();
        AdvertiseSettings as=new AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).build();
        advertiseCallback=new AdvertiseCallback(){
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect)
            {
                super.onStartSuccess(settingsInEffect);
            }

            @Override
            public void onStartFailure(int errorCode)
            {
                super.onStartFailure(errorCode);
            }
        };
        advertiser.startAdvertising(as,ad,advertiseCallback);
    }

    long mReadRequestTime=0;

    Runnable mLatencyPollRunnable=new Runnable()
    {
        @Override
        public void run()
        {
            BluetoothManager mg= (BluetoothManager) mContext.getSystemService(Context.BLUETOOTH_SERVICE);
            Log.d("poll",""+mg.getConnectionState(mDev, BluetoothProfile.GATT));

            mReadRequestTime= System.nanoTime();
            mGatt.readCharacteristic(mReadCharacteristic);
            mHandler.postDelayed(this,100);
        }
    };

    BluetoothGattCallback mCallback=new BluetoothGattCallback()
    {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState)
        {
            if(LOG_DEBUG)Log.d("ble","gatt connectionstatus:"+newState);
            super.onConnectionStateChange(gatt, status, newState);
            if(newState==BluetoothGatt.STATE_CONNECTED)
            {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            super.onServicesDiscovered(gatt, status);
            if(LOG_DEBUG)Log.d("ble","gatt services discovered");
            BluetoothGattService service=gatt.getService(BLE_UUID);
            if(service!=null)
            {
                mReadCharacteristic=service.getCharacteristic(BLE_CHARACTERISTIC);

                if(!mCheckLatency)
                {
                    gatt.setCharacteristicNotification(mReadCharacteristic, true);
                }else
                {
                    mHandler.postDelayed(mLatencyPollRunnable,100);
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status)
        {
            super.onCharacteristicRead(gatt, characteristic, status);
            if(mCheckLatency)
            {
                long responseTime = System.nanoTime();
                float timeSeconds=0.000000001f*(float)(responseTime-mReadRequestTime);
                addLatencyValue(timeSeconds);
            }
            if(LOG_DEBUG)Log.d("ble","got characteristic");
            synchronized(lastMsg)
            {
                System.arraycopy(characteristic.getValue(), 0, lastMsg, 0, lastMsg.length);
            };
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic)
        {
            super.onCharacteristicChanged(gatt, characteristic);
            //if(LOG_DEBUG)Log.d("ble","characteristic notification");
            synchronized(lastMsg)
            {
                System.arraycopy(characteristic.getValue(), 0, lastMsg, 0, lastMsg.length);
            };
        }
    };


    ScanCallback mScanCallback=new ScanCallback(){
        @Override
        public void onScanResult(int callbackType, ScanResult result)
        {
            if(LOG_DEBUG)Log.d("single result","gatt:"+result.toString());
            mDev=result.getDevice();
            mGatt=mDev.connectGatt(mContext, false, mCallback);
            mScanner.stopScan(this);
            super.onScanResult(callbackType, result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results)
        {
            if(LOG_DEBUG)Log.d("batch","gatt:"+results.toString());
            super.onBatchScanResults(results);
        }

        @Override
        public void onScanFailed(int errorCode)
        {
            super.onScanFailed(errorCode);
        }
    };

    @Override
    public void initReceiver(int packetSize, String sourceAddresses,Context context)
    {
        mContext=context;
        if(mCheckLatency)
        {
            mHandler=new Handler();
        }
        lastMsg=new byte[packetSize];
        lastMsgCopy=new byte[packetSize];
        Pattern
                p=Pattern.compile("ble:([A-Za-z0-9]+)");
        Matcher m=p.matcher(sourceAddresses);
        if(m.find())
        {
            btName =m.group(1);
        }

        BluetoothManager mg= (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mAdapter=mg.getAdapter();

        mScanner =mAdapter.getBluetoothLeScanner();
        ScanFilter.Builder builder=new ScanFilter.Builder();
        builder.setDeviceName(btName);
        List<ScanFilter> l= new ArrayList<ScanFilter>();
        l.add(builder.build());
        mScanner.startScan(l, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), mScanCallback);
    }



    @Override
    public void sendData(byte[] data)
    {
// copy into out buffer
        synchronized(sendBuffer)
        {
            System.arraycopy(data, 0, sendBuffer, 0, sendBuffer.length);
            System.arraycopy(sendBuffer, 0, sendCopyBuffer, 0, sendBuffer.length);
            mSendCharacteristic.setValue(sendCopyBuffer);
        }
        if(!mCheckLatency)
        {
            for(BluetoothDevice dev : mConnectedDevices.keySet())
            {
                mGattServer.notifyCharacteristicChanged(dev, mSendCharacteristic, false);
            }
        }
    }

    @Override
    public byte[] lastPacket()
    {
        synchronized(lastMsg)
        {
            System.arraycopy(lastMsg,0,lastMsgCopy,0,lastMsg.length);
        }
        return lastMsgCopy;
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        if(mScanner!=null)
        {
            mScanner.stopScan(mScanCallback);
        }
        if(mGatt!=null)
        {
            mGatt.close();
            mGatt=null;
        }
        if(mGattServer!=null)
        {
            mGattServer.close();
            mGattServer=null;
        }
    }


}
