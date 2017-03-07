package com.mrl.flashcamerasource;

import android.content.Context;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Created by jqm on 06/03/2017.
 */

public class ServiceWifiChecker
{
    static long lastCheckTime=System.currentTimeMillis();
    static int lastNum=-1;
    static List<String> wifiPoints = new ArrayList<String>();
    static List<String> wifiPasswords = new ArrayList<String>();
    static String lastAddress="";
    static InetAddress broadcastIP;
    static InetAddress ipAddress;
    static long lastAddressTime=System.currentTimeMillis();

    public static boolean checkWifi(Context ctx, int wifiNum)
    {

        if(wifiPoints.size()==0)
        {
            String path = Environment.getExternalStorageDirectory() + "/networks.txt";
            File file = new File(path);
            try
            {
                if(file.exists())
                {
                    BufferedReader br = new BufferedReader(new FileReader(path));
                    while(true)
                    {
                        String line = br.readLine();
                        if(line == null)
                        {
                            break;
                        } else
                        {
                            String[] parts = line.split(":");
                            if(parts.length == 2)
                            {
                                wifiPoints.add(parts[0]);
                                wifiPasswords.add(parts[1]);
                            }
                        }
                    }
                }
            } catch(IOException e)
            {
                e.printStackTrace();
            }
        }

        if(wifiNum>=wifiPoints.size())
        {
            wifiNum=wifiPoints.size()-1;
        }

        // let it take 5 seconds to connect otherwise we go crazy with refreshing which
        // makes the wifimanager go bad
        if(System.currentTimeMillis()-lastCheckTime<5000 && wifiNum==lastNum)
        {
            return false;
        }
        lastCheckTime=System.currentTimeMillis();
        lastNum=wifiNum;

        String ssid = wifiPoints.get(wifiNum);
        String key = wifiPasswords.get(wifiNum);

        String quotedSSID=String.format("\"%s\"", ssid);

        WifiManager wifiManager = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info=wifiManager.getConnectionInfo();
        String connectedSSID=info.getSSID();
        SupplicantState stat=info.getSupplicantState();

        // if we are already connected to this network, return true
        if(connectedSSID.compareTo(quotedSSID)==0 && (stat==
                SupplicantState.ASSOCIATED || stat==SupplicantState.COMPLETED))
        {
            // already on the right wifi and connected, do nothing
            return true;
        }


        if(connectedSSID.length()>0 && connectedSSID.compareTo(quotedSSID)!=0)
        {
            // make sure we are disconnected okay from any different SSID
            wifiManager.disconnect();
        }

        boolean changedConfig=false;
        WifiConfiguration existingNetworkConfig=null;
        for(int c = 0; c < wifiPoints.size(); c++)
        {
            String compareSSID = String.format("\"%s\"", wifiPoints.get(c));
            List<WifiConfiguration> currentNetworks = wifiManager.getConfiguredNetworks();
            if(currentNetworks!=null)
            {
                for(WifiConfiguration config : currentNetworks)
                {
                    String savedNetSsid = config.SSID;
                    if(savedNetSsid.compareToIgnoreCase(compareSSID) == 0)
                    {
                        if(compareSSID.equals(quotedSSID))
                        {
                            // this is the network we want and it is configured already
                            existingNetworkConfig = config;
                            wifiManager.enableNetwork(config.networkId, true);
                        } else
                        {
                            // this is our other network - get rid of it
                            boolean retVal = wifiManager.removeNetwork(config.networkId);
                            if(retVal == false)
                            {
                                // WHAT?
                                //Toast.makeText(ctx, "Can't remove old network", Toast.LENGTH_SHORT);
                            }
                            changedConfig=true;
                        }
                        //                    wifiManager.saveConfiguration();
                    }
                }
            }
        }
        if(changedConfig)
        {
            wifiManager.saveConfiguration();
        }
        if(existingNetworkConfig==null)
        {
            // then add in this one with high priority
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = String.format("\"%s\"", ssid);
            wifiConfig.preSharedKey = String.format("\"%s\"", key);
            wifiConfig.priority = 99999;
            int netID = wifiManager.addNetwork(wifiConfig);
            //Toast.makeText(ctx, "Connecting to " + ssid, Toast.LENGTH_SHORT);
            wifiManager.saveConfiguration();
            wifiManager.enableNetwork(netID, true);
        }
        wifiManager.reconnect();
        // always return false here because we are still connecting to the new wifi
        // we'll check again in the receiver loop
        return false;
    }


    public static String wifiIPAddress(Context ctx) {
        if(System.currentTimeMillis()-lastAddressTime<5000 )
        {
            return lastAddress;
        }
        lastAddressTime=System.currentTimeMillis();


/*        WifiManager wifiManager = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

        // Convert little-endian to big-endianif needed
        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
        } catch (UnknownHostException ex)
        {
            //Log.e("WIFIIP", "Unable to get host address.");
            // check if we're an access point
//            ipAddressString = "192.168.43.1";
        }*/
        String ipAddressString="192.168.43.1";
        try
        {
            byte[] bytes={(byte)192,(byte)168,(byte)43,(byte)255};
            broadcastIP=InetAddress.getByAddress(bytes);
        } catch(UnknownHostException e)
        {
            e.printStackTrace();
        }
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
                            ipAddress=ad;
                        }
                    }
                    for(InterfaceAddress interfaceAddress : ni.getInterfaceAddresses())
                    {
                        if(interfaceAddress.getBroadcast()!=null)
                        {
                            broadcastIP = interfaceAddress.getBroadcast();
                        }
                    }
              }
            }
        } catch(SocketException e)
        {
            e.printStackTrace();
        }
        lastAddress=ipAddressString;
        return ipAddressString;
    }

    public static InetAddress wifiInetAddress(Context ctx)
    {
        wifiIPAddress(ctx);
        return ipAddress;
    }


    public static InetAddress wifiBroadcastAddress(Context ctx)
    {
        if(broadcastIP==null)
        {
            // broadcast is got in this function
            wifiIPAddress(ctx);
        }
        return broadcastIP;
    }
}
