package com.mrl.boxupload;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import com.box.androidsdk.content.*;
import com.box.androidsdk.content.auth.BoxAuthentication;
import com.box.androidsdk.content.models.*;
import com.box.androidsdk.content.requests.BoxRequestsFile;

import java.io.*;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by jqm on 24/04/2017.
 */

public class BoxConnector implements BoxAuthentication.AuthListener
{

    public interface Callback
    {
        void onStatusChange();
    };

    File boxConfigFile = new File("/sdcard/boxconfig.txt");
    File sourceFolder = new File("/sdcard/vrplayground-logs");
    String uploadTarget = "VRPlayground-Data";
    String topFolderID = null;

    BoxSession mSession = null;
    BoxApiFolder mFolderApi;
    BoxApiFile mFileApi;

    Context mContext;
    Handler mHandler;
    Callback mCallback;

    String lastFileMessage="";

    public BoxConnector(Context context,Callback callback)
    {
        mCallback=callback;
        mContext = context;
        mHandler = new Handler();
    }


    void connectBox()
    {
        lastFileMessage = "Connecting to box";
        onStatusChange();

        BoxAuthentication.getInstance().setAuthStorage(new BoxAuthHolder());
        CharBuffer tmp = CharBuffer.allocate(2048);
        BufferedReader reader = null;
        try
        {
            reader = new BufferedReader(new FileReader(boxConfigFile));
            // get box config from file (rather than leave it on github)
            BoxConfig.CLIENT_ID = reader.readLine();
            BoxConfig.CLIENT_SECRET = reader.readLine();
// must match the redirect_uri set in your developer account if one has been set. Redirect uri should not be of type file:// or content://.
            BoxConfig.REDIRECT_URL = reader.readLine();
            BoxConfig.AUTOLOGIN_USER=reader.readLine();
            BoxConfig.AUTOLOGIN_PASS=reader.readLine();
            reader.close();

            mSession = new BoxSession(mContext);
            mSession.setSessionAuthListener(this);
            mSession.authenticate(mContext);
        } catch(FileNotFoundException e)
        {
            e.printStackTrace();
        } catch(IOException e)
        {
            e.printStackTrace();
        }
        onStatusChange();
    }

    @Override
    public void onRefreshed(BoxAuthentication.BoxAuthenticationInfo info)
    {

    }

    @Override
    public void onAuthCreated(BoxAuthentication.BoxAuthenticationInfo info)
    {
        lastFileMessage = "Authenticated box";
        onStatusChange();
        //Init file, and folder apis; and use them to fetch the root folder
        mFolderApi = new BoxApiFolder(mSession);
        mFileApi = new BoxApiFile(mSession);
        findUploadFolder();
    }

    private void onStatusChange()
    {
        mHandler.post(new Runnable(){public void run(){ mCallback.onStatusChange();}});
    }

    @Override
    public void onAuthFailure(BoxAuthentication.BoxAuthenticationInfo info, Exception ex)
    {
        mSession=null;
    }

    @Override
    public void onLoggedOut(BoxAuthentication.BoxAuthenticationInfo info, Exception ex)
    {
        mSession=null;
    }


    void findUploadFolder()
    {
        try
        {
            final BoxIteratorItems folderItems =
                    mFolderApi.getItemsRequest(BoxConstants.ROOT_FOLDER_ID).send();
            for(BoxItem boxItem : folderItems)
            {
                if(boxItem.getName().endsWith(uploadTarget))
                {
                    topFolderID = boxItem.getId();
                }
                Log.d("box", "Folder:" + boxItem.getName());
            }
            Log.d("box", "ID:" + topFolderID);
            checkUploadFiles();
        } catch(BoxException e)
        {
            e.printStackTrace();
        }
        if(topFolderID==null)
        {
            mSession=null;
            lastFileMessage = "No box connection";
        }
    }

    void checkUploadFiles()
    {
        if(mSession == null)
        {
            connectBox();
            return;
        }
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
        if(files != null)
        {
            for(int i = 0; i < files.length; i++)
            {
                String curName = files[i].getAbsolutePath();
                File uploadTag = new File(curName + ".upload");
                if(!curName.endsWith(".upload") && !uploadTag.exists())
                {
                    tryUploadFile(files[i]);
                }
            }
        }
    }

    String getFolderIDForDate(String datestr)
    {
        if(mSession != null && topFolderID !=null)
        {
            try
            {
                BoxFolder folder = mFolderApi.getFolderWithAllItems(topFolderID).send();
                BoxIteratorItems folderItems = folder.getItemCollection();
                for(BoxItem boxItem : folderItems)
                {
                    if(boxItem instanceof BoxFolder && boxItem.getName().equals(datestr))
                    {
                        Log.d("box", "Found date folder:" + boxItem.getName());
                        return boxItem.getId();
                    }
                }
                BoxFolder newFolder = mFolderApi.getCreateRequest(topFolderID, datestr).send();
                return newFolder.getId();
            } catch(BoxException e)
            {
                Log.d("box", "failed:" + e.toString());
                e.printStackTrace();
            }
        }
        return null;
    }

    void tryUploadFile(final File curFile)
    {
        String folderID=topFolderID;
        Pattern p = Pattern.compile("(\\d\\d\\d\\d\\d\\d\\d\\d).*");
        if(mSession != null && topFolderID !=null)
        {
            final String curName = curFile.getAbsolutePath();

            try
            {

                Matcher datematch=p.matcher(curFile.getName());
                if(datematch.matches())
                {
                    folderID=getFolderIDForDate(datematch.group(1));
                }
                BoxApiFile fileApi = new BoxApiFile(mSession);
                BoxRequestsFile.UploadFile request = fileApi.getUploadRequest(curFile, folderID);

                final BoxFile uploadFileInfo = request.send();
                File uploadMarker=new File(curName+".upload");
                uploadMarker.createNewFile();
                lastFileMessage = "Uploaded:"+curFile.getName();
            } catch(BoxException e)
            {
                e.printStackTrace();
                BoxError error = e.getAsBoxError();
                    if (error != null && error.getStatus() == 409 && error.getContextInfo()!=null /*HttpStatus.SC_CONFLICT*/) {
                        ArrayList<BoxEntity> conflicts = error.getContextInfo().getConflicts();
                        if (conflicts != null && conflicts.size() == 1 && conflicts.get(0) instanceof BoxFile) {
                            uploadNewVersion((BoxFile) conflicts.get(0),curFile);
                            return;
                        }
                    }
                lastFileMessage = "Upload failed:"+curFile.getName();
            } catch(IOException e)
            {
                lastFileMessage = "Upload failed:"+curFile.getName();
                e.printStackTrace();
            }
        } else
        {
            lastFileMessage = "No box connection";
        }
        onStatusChange();
    }

    private void uploadNewVersion(BoxFile boxFile, File curFile)
    {
        final String curName = curFile.getAbsolutePath();
        BoxRequestsFile.UploadNewVersion request = mFileApi.getUploadNewVersionRequest(curFile, boxFile.getId());
        try
        {
            final BoxFile uploadFileVersionInfo = request.send();
            File uploadMarker=new File(curName+".upload");
            uploadMarker.createNewFile();
            lastFileMessage = "Uploaded:"+curFile.getName();
        } catch(BoxException e)
        {
            lastFileMessage="Couldn't upload new version:"+curFile.getName();
            e.printStackTrace();
        } catch(IOException e)
        {
            lastFileMessage="Couldn't upload new version:"+curFile.getName();
            e.printStackTrace();
        }
        onStatusChange();
    }

    private void runOnUiThread(Runnable r)
    {
        mHandler.post(r);
    }

    private void showToast(final String text)
    {
        runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Toast.makeText(mContext, text, Toast.LENGTH_LONG).show();
            }
        });
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
