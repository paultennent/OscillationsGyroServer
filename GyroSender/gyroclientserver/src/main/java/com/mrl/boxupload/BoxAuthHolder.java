package com.mrl.boxupload;

import android.content.Context;
import android.util.Log;

import com.box.androidsdk.content.auth.BoxAuthentication;
import com.box.androidsdk.content.models.BoxEntity;
import com.box.androidsdk.content.utils.SdkUtils;
import com.eclipsesource.json.JsonObject;
import com.eclipsesource.json.JsonValue;

import java.io.*;
import java.nio.CharBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by jqm on 24/04/2017.
 */

public class BoxAuthHolder extends BoxAuthentication.AuthStorage
{
    File boxAuthMap = new File("/sdcard/boxauthmap.txt");
    File boxAuthName = new File("/sdcard/boxauthname.txt");

    String readFileString(File file) throws IOException
    {
        int length = (int) file.length();

        byte[] bytes = new byte[length];

        FileInputStream in = new FileInputStream(file);
        try {
            in.read(bytes);
        } finally {
            in.close();
        }

        String contents = new String(bytes);
        return contents;

    }

    protected void storeAuthInfoMap(Map<String, BoxAuthentication.BoxAuthenticationInfo> authInfo, Context context) {
        JsonObject jsonObject = new JsonObject();
        for (Map.Entry<String, BoxAuthentication.BoxAuthenticationInfo> entry : authInfo.entrySet()){
            jsonObject.add(entry.getKey(), entry.getValue().toJsonObject());
        }
        BoxEntity infoMapObj = new BoxEntity(jsonObject);
        try
        {
            OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(boxAuthMap));
            writer.write(infoMapObj.toJson());
            writer.close();
        } catch(IOException e)
        {
            Log.e("Box","Couldn't store box authentication map");
        }
    }

    /**
     * Removes auth info from storage.
     *
     * @param context context here is only used to load shared pref. In case you don't need shared pref, you can ignore this
     *                argument in your implementation.
     */
    protected void clearAuthInfoMap(Context context) {
        boxAuthMap.delete();
    }

    /**
     * Store out the last user id that the user authenticated as. This will be the one that is restored if no user is specified for a BoxSession.
     *
     * @param userId  user id of the last authenticated user. null if this data should be removed.
     * @param context context here is only used to load shared pref. In case you don't need shared pref, you can ignore this
     *                argument in your implementation.
     */
    protected void storeLastAuthenticatedUserId(String userId, Context context) {
        if (SdkUtils.isEmptyString(userId)) {
            boxAuthName.delete();
        } else {
            try
            {
                OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(boxAuthName));
                writer.write(userId);
                writer.close();
            } catch(IOException e)
            {
                Log.e("Box","Couldn't store box authentication userID");
            }
        }
    }

    /**
     * Return the last user id associated with the last authentication.
     *
     * @param context context here is only used to load shared pref. In case you don't need shared pref, you can ignore this
     *                argument in your implementation.
     * @return the user id of the last authenticated user or null if not stored or the user has since been logged out.
     */
    protected String getLastAuthentictedUserId(Context context) {
        try
        {
            return readFileString(boxAuthName);
        } catch(FileNotFoundException e)
        {
            e.printStackTrace();
        } catch(IOException e)
        {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Load auth info from storage.
     *
     * @param context context here is only used to load shared pref. In case you don't need shared pref, you can ignore this
     *                argument in your implementation.
     * @return a map of all known user authentication information with keys being userId.
     */
    protected ConcurrentHashMap<String, BoxAuthentication.BoxAuthenticationInfo> loadAuthInfoMap(Context context) {
        ConcurrentHashMap<String, BoxAuthentication.BoxAuthenticationInfo> map = new ConcurrentHashMap<String, BoxAuthentication.BoxAuthenticationInfo>();
        try
        {
            String json = readFileString(boxAuthMap);
            if (json.length() > 0) {
                BoxEntity obj = new BoxEntity();
                obj.createFromJson(json);
                for (String key: obj.getPropertiesKeySet()) {
                    JsonValue value = obj.getPropertyValue(key);
                    BoxAuthentication.BoxAuthenticationInfo info = null;
                    if (value.isString()) {
                        info = new BoxAuthentication.BoxAuthenticationInfo();
                        info.createFromJson(value.asString());
                    } else if (value.isObject()){
                        info = new BoxAuthentication.BoxAuthenticationInfo();
                        info.createFromJson(value.asObject());
                    }
                    map.put(key, info);
                }
            }
        } catch(FileNotFoundException e)
        {
            e.printStackTrace();
        } catch(IOException e)
        {
            e.printStackTrace();
        }

        return map;
    }
}

