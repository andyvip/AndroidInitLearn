/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jrdcom.android.gallery3d.ui;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Handler;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu.OnMenuItemClickListener;
import android.widget.ShareActionProvider;
import android.widget.ShareActionProvider.OnShareTargetSelectedListener;
import android.widget.TextView;
import android.widget.Toast;
import com.jrdcom.android.gallery3d.app.AlbumSetPage;
import com.jrdcom.android.gallery3d.ui.Log;
import com.jrdcom.android.gallery3d.R;
import com.jrdcom.android.gallery3d.app.AbstractGalleryActivity;
import com.jrdcom.android.gallery3d.common.ApiHelper;
import com.jrdcom.android.gallery3d.common.Utils;
import com.jrdcom.android.gallery3d.data.DataManager;
import com.jrdcom.android.gallery3d.data.MediaObject;
import com.jrdcom.android.gallery3d.data.MediaObject.PanoramaSupportCallback;
import com.jrdcom.android.gallery3d.data.Path;
import com.jrdcom.android.gallery3d.ui.MenuExecutor.ProgressListener;
import com.jrdcom.android.gallery3d.util.Future;
import com.jrdcom.android.gallery3d.util.GalleryUtils;
import com.jrdcom.android.gallery3d.util.ThreadPool.Job;
import com.jrdcom.android.gallery3d.util.ThreadPool.JobContext;
import com.jrdcom.android.gallery3d.ui.JrdShareActionProvider;
import com.jrdcom.android.gallery3d.ui.JrdShareActionProvider.OnJrdShareTargetSelectedListener;
import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;

import com.jrdcom.android.gallery3d.data.MediaItem;
import com.jrdcom.android.gallery3d.app.ActivityState;

import com.mediatek.common.featureoption.FeatureOption;
import com.jrdcom.mediatek.gallery3d.stereo.StereoConvertor;
import com.jrdcom.mediatek.gallery3d.stereo.StereoHelper;
import com.jrdcom.mediatek.gallery3d.util.MediatekFeature;
import com.jrdcom.mediatek.gallery3d.util.MtkLog;
public class ActionModeHandler implements Callback, PopupList.OnPopupItemClickListener {

    @SuppressWarnings("unused")
    private static final String TAG = "ActionModeHandler";
    //PR-540789,541989 added by xiaowei.xu begin
    private static final String INTENT_NOT_READY = "intent not ready";
    private final int SHARE_URI_SIZE_LIMITATION = 30000; 
    //PR-540789,541989 added by xiaowei.xu end 

    private static final boolean mIsStereoDisplaySupported = 
                                          MediatekFeature.isStereoDisplaySupported();

    private static final int SUPPORT_MULTIPLE_MASK = MediaObject.SUPPORT_DELETE
            | MediaObject.SUPPORT_ROTATE | MediaObject.SUPPORT_SHARE
            | MediaObject.SUPPORT_CACHE | MediaObject.SUPPORT_IMPORT;

    public interface ActionModeListener {
        public boolean onActionItemClicked(MenuItem item);
    }

    private final AbstractGalleryActivity mActivity;
    private final MenuExecutor mMenuExecutor;
    private final SelectionManager mSelectionManager;
    private final NfcAdapter mNfcAdapter;
    private Menu mMenu;
    private MenuItem mSharePanoramaMenuItem;
    private MenuItem mShareMenuItem;
    private ShareActionProvider mSharePanoramaActionProvider;
    private JrdShareActionProvider mShareActionProvider;
    private SelectionMenu mSelectionMenu;
    private TextView mSelectCount;
    private ActionModeListener mListener;
    private Future<?> mMenuTask;
    private final Handler mMainHandler;
    private ActionMode mActionMode;

    private ProgressDialog mProgressDialog;
    private Future<?> mConvertIntentTask;

    //PR-540789,541989 added by xiaowei.xu begin
       // private volatile boolean mIsUpdateSupportedOperationRunning = false;
       //M: When share intent is not ready, after click share icon, show 'wait' toast
       private Toast mWaitToast = null;
    //PR-540789,541989 added by xiaowei.xu end

    private static class GetAllPanoramaSupports implements PanoramaSupportCallback {
        private int mNumInfoRequired;
        private JobContext mJobContext;
        public boolean mAllPanoramas = true;
        public boolean mAllPanorama360 = true;
        public boolean mHasPanorama360 = false;
        private Object mLock = new Object();

        public GetAllPanoramaSupports(ArrayList<MediaObject> mediaObjects, JobContext jc) {
            mJobContext = jc;
            mNumInfoRequired = mediaObjects.size();
            for (MediaObject mediaObject : mediaObjects) {
                mediaObject.getPanoramaSupport(this);
            }
        }

        @Override
        public void panoramaInfoAvailable(MediaObject mediaObject, boolean isPanorama,
                boolean isPanorama360) {
            synchronized (mLock) {
                mNumInfoRequired--;
                mAllPanoramas = isPanorama && mAllPanoramas;
                mAllPanorama360 = isPanorama360 && mAllPanorama360;
                mHasPanorama360 = mHasPanorama360 || isPanorama360;
                if (mNumInfoRequired == 0 || mJobContext.isCancelled()) {
                    mLock.notifyAll();
                }
            }
        }

        public void waitForPanoramaSupport() {
            synchronized (mLock) {
                while (mNumInfoRequired != 0 && !mJobContext.isCancelled()) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException e) {
                        // May be a cancelled job context
                    }
                }
            }
        }
    }

    public ActionModeHandler(
            AbstractGalleryActivity activity, SelectionManager selectionManager) {
        mActivity = Utils.checkNotNull(activity);
        mSelectionManager = Utils.checkNotNull(selectionManager);
        mMenuExecutor = new MenuExecutor(activity, selectionManager);
        mMainHandler = new Handler(activity.getMainLooper());
        mNfcAdapter = NfcAdapter.getDefaultAdapter(mActivity.getAndroidContext());
    }

    public void startActionMode() {
        Activity a = mActivity;
        mActionMode = a.startActionMode(this);
        View customView = LayoutInflater.from(a).inflate(
                R.layout.action_mode, null);
        mActionMode.setCustomView(customView);
        //modified by qjz for Gallery NewUI 20130423 begin
        //mSelectCount = (TextView) customView.findViewById(R.id.select_count);
        mSelectionMenu = new SelectionMenu(a,
                (Button) customView.findViewById(R.id.selection_menu), this);
        //modified by qjz for Gallery NewUI 20130423 end
		updateSelectionMenu();
    }

    public void finishActionMode() {
        mActionMode.finish();
        //PR-540789,541989 added by xiaowei.xu begin
         /// M: cancel menutask if action mode finish @{
          if (mMenuTask != null) {
                mMenuTask.cancel();
                mMenuTask = null;
            }
         ///@}
        //PR-540789,541989 added by xiaowei.xu end 
    }

    public void setTitle(String title) {
        //modified by qjz for Gallery NewUI 20130423 begin
        mSelectionMenu.setTitle(title);
        //mSelectCount.setText(title);
        //modified by qjz for Gallery NewUI 20130423 end
    }

    public void setActionModeListener(ActionModeListener listener) {
        mListener = listener;
    }

    private WakeLockHoldingProgressListener mDeleteProgressListener;

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        GLRoot root = mActivity.getGLRoot();
        root.lockRenderThread();
        try {
            boolean result;
            // Give listener a chance to process this command before it's routed to
            // ActionModeHandler, which handles command only based on the action id.
            // Sometimes the listener may have more background information to handle
            // an action command.
            if (mListener != null) {
                result = mListener.onActionItemClicked(item);
                if (result) {
                    mSelectionManager.leaveSelectionMode();
                    return result;
                }
            }
            ProgressListener listener = null;
            String confirmMsg = null;
            int action = item.getItemId();
            if (action == R.id.action_import) {
                listener = new ImportCompleteListener(mActivity);
            } else if (action == R.id.action_delete) {
                confirmMsg = mActivity.getResources().getQuantityString(
                        R.plurals.delete_selection, mSelectionManager.getSelectedCount());
                if (mDeleteProgressListener == null) {
                    mDeleteProgressListener = new WakeLockHoldingProgressListener(mActivity,
                            "Gallery Delete Progress Listener");
                }
                listener = mDeleteProgressListener;
            }
            mMenuExecutor.onMenuClicked(item, confirmMsg, listener);
        } finally {
            root.unlockRenderThread();
        }
        return true;
    }

    @Override
    public boolean onPopupItemClick(int itemId) {
        GLRoot root = mActivity.getGLRoot();
        root.lockRenderThread();
        try {
            if (itemId == R.id.action_select_all) {
                mMenuExecutor.onMenuClicked(itemId, null, false, true);
                updateSupportedOperation();
                updateSelectionMenu();
            }
            return true;
        } finally {
            root.unlockRenderThread();
        }
    }

    public void updateSelectionMenu() {
        // update title
        int count = mSelectionManager.getSelectedCount();
       //PR-540789,541989 added by xiaowei.xu begin 
       /* String format = mActivity.getResources().getQuantityString(
                R.plurals.number_of_items_selected, count);
        setTitle(String.format(format, count)); */ 

        // M: if current state is AlbumSetPage, tile maybe albums/groups, so getSelectedString from AlbumSetPage
        String title = null;
        ActivityState topState = null;

        //PR887588 fixed monkey test error by fengke at 2015.01.20 strat
        try {
            topState = mActivity.getStateManager().getTopState();
        } catch (AssertionError e) {
            Log.e(TAG,"fengke AssertionError="+e);
        }
        //PR887588 fixed monkey test error by fengke at 2015.01.20 end

        if(topState != null && topState instanceof AlbumSetPage) {
            title = ((AlbumSetPage)topState).getSelectedString();
        } else {
            String format = mActivity.getResources().getQuantityString(
                R.plurals.number_of_items_selected, count);
            title = String.format(format, count);
        }
        setTitle(title);
        //PR-540789,541989 added by xiaowei.xu end

        // For clients who call SelectionManager.selectAll() directly, we need to ensure the
        // menu status is consistent with selection manager.
        mSelectionMenu.updateSelectAllMode(mSelectionManager.inSelectAllMode()); //modified by qjz for Gallery NewUI 20130423 
    }

    private final OnShareTargetSelectedListener mShareTargetSelectedListener =
            new OnShareTargetSelectedListener() {
        @Override
        public boolean onShareTargetSelected(ShareActionProvider source, Intent intent) {
            //PR-540789,541989 added by xiaowei.xu begin
            //mSelectionManager.leaveSelectionMode();
          Log.e(TAG,"onShareTargetSelected:intent="+intent);
          /// M: if the intent is not ready intent, we ignore action, and show wait toast @{
            if (null != intent.getExtras()
                    && intent.getExtras().getBoolean(INTENT_NOT_READY, false)) {
                intent.putExtra(ShareActionProvider.SHARE_TARGET_SELECTION_IGNORE_ACTION, true);
                if (mWaitToast == null) {
                    mWaitToast = Toast.makeText(mActivity, com.android.internal.R.string.wait,
                            Toast.LENGTH_SHORT);
                }
                mWaitToast.show();
                return true;
            /// M: @}
            } else {
                 mSelectionManager.leaveSelectionMode();
            }
            //PR-540789,541989 added by xiaowei.xu end
                             

            //when set share intent, we should first check if there is stereo
            //image inside, and set this info into to Bundle inside the intent
            //When this function runs, we should check that whether we should
            //prompt a dialog. If No, returns false and continue original
            //rountin. If Yes, change the content of Bundle inside intent, and
            //re-start the intent by ourselves.
            Log.e(TAG,"onShareTargetSelected:intent="+intent);
            if (mIsStereoDisplaySupported && null != intent.getExtras() &&
                intent.getExtras().getBoolean(
                             StereoHelper.INCLUDED_STEREO_IMAGE, false)) {
                checkStereoIntent(intent);
                StereoHelper.makeShareProviderIgnorAction(intent);
                return true;
            }

            return false;
        }
    };
    //add by qjz for Gallery NewUI 20130423 begin
    private final OnJrdShareTargetSelectedListener mJrdShareTargetSelectedListener =
            new OnJrdShareTargetSelectedListener() {
        @Override
        public boolean onJrdShareTargetSelected(JrdShareActionProvider source, Intent intent) {
            mSelectionManager.leaveSelectionMode();

            //when set share intent, we should first check if there is stereo
            //image inside, and set this info into to Bundle inside the intent
            //When this function runs, we should check that whether we should
            //prompt a dialog. If No, returns false and continue original
            //rountin. If Yes, change the content of Bundle inside intent, and
            //re-start the intent by ourselves.
            Log.e(TAG,"onShareTargetSelected:intent="+intent);
            if (mIsStereoDisplaySupported && null != intent.getExtras() &&
                intent.getExtras().getBoolean(
                             StereoHelper.INCLUDED_STEREO_IMAGE, false)) {
                checkStereoIntent(intent);
                StereoHelper.makeShareProviderIgnorAction(intent);
                return true;
            }

            return false;
        }
    };
    //modified by qjz for Gallery NewUI 20130423 end
    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
        mode.getMenuInflater().inflate(R.menu.operation, menu);

        mMenu = menu;
        mSharePanoramaMenuItem = menu.findItem(R.id.action_share_panorama);
        if (mSharePanoramaMenuItem != null) {
            mSharePanoramaActionProvider = (ShareActionProvider) mSharePanoramaMenuItem
                .getActionProvider();
            mSharePanoramaActionProvider.setOnShareTargetSelectedListener(
                    mShareTargetSelectedListener);
            mSharePanoramaActionProvider.setShareHistoryFileName("panorama_share_history.xml");
        }
        mShareMenuItem = menu.findItem(R.id.action_share);
        if (mShareMenuItem != null) {
            //modified by qjz for Gallery NewUI 20130423 begin
            mShareActionProvider = (JrdShareActionProvider) mShareMenuItem.getActionProvider();
            mShareActionProvider.setOnJrdShareTargetSelectedListener(
            		mJrdShareTargetSelectedListener);
            //mShareActionProvider.setShareHistoryFileName("share_history.xml");//
            //modified by qjz for Gallery NewUI 20130423 end
        }
        return true;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mSelectionManager.leaveSelectionMode();
    }

    private ArrayList<MediaObject> getSelectedMediaObjects(JobContext jc) {
        ArrayList<Path> unexpandedPaths = mSelectionManager.getSelected(false);
        if (unexpandedPaths.isEmpty()) {
            // This happens when starting selection mode from overflow menu
            // (instead of long press a media object)
            return null;
        }
        ArrayList<MediaObject> selected = new ArrayList<MediaObject>();
        DataManager manager = mActivity.getDataManager();
        for (Path path : unexpandedPaths) {
            if (jc.isCancelled()) {
                return null;
            }
            selected.add(manager.getMediaObject(path));
        }

        return selected;
    }
    // Menu options are determined by selection set itself.
    // We cannot expand it because MenuExecuter executes it based on
    // the selection set instead of the expanded result.
    // e.g. LocalImage can be rotated but collections of them (LocalAlbum) can't.
    private int computeMenuOptions(ArrayList<MediaObject> selected) {
        if (selected == null)
            return 0;
        int operation = MediaObject.SUPPORT_ALL;
        int type = 0;
        for (MediaObject mediaObject: selected) {
            int support = mediaObject.getSupportedOperations();
            type |= mediaObject.getMediaType();
            operation &= support;
        }

        switch (selected.size()) {
            case 1:
                final String mimeType = MenuExecutor.getMimeType(type);
                if (!GalleryUtils.isEditorAvailable(mActivity, mimeType)) {
                    operation &= ~MediaObject.SUPPORT_EDIT;
                }
                break;
            default:
                operation &= SUPPORT_MULTIPLE_MASK;
        }

        return operation;
    }

    @TargetApi(ApiHelper.VERSION_CODES.JELLY_BEAN)
    private void setNfcBeamPushUris(Uri[] uris) {
        if (mNfcAdapter != null && ApiHelper.HAS_SET_BEAM_PUSH_URIS) {
            /// M: mediatek nfc modification @{
//            if(FeatureOption.MTK_BEAM_PLUS_SUPPORT) {
//                mNfcAdapter.setMtkBeamPushUrisCallback(null, mActivity);
//            } else {
                mNfcAdapter.setBeamPushUrisCallback(null, mActivity);
//            }
            // mNfcAdapter.setBeamPushUrisCallback(null, mActivity);
            /// @}
            mNfcAdapter.setBeamPushUris(uris, mActivity);
        }
    }

    // Share intent needs to expand the selection set so we can get URI of
    // each media item
    private Intent computePanoramaSharingIntent(JobContext jc) {
        ArrayList<Path> expandedPaths = mSelectionManager.getSelected(true);
        if (expandedPaths.size() == 0) {
            return null;
        }
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        DataManager manager = mActivity.getDataManager();
        final Intent intent = new Intent();
        for (Path path : expandedPaths) {
            if (jc.isCancelled()) return null;
            uris.add(manager.getContentUri(path));
        }

        final int size = uris.size();
        if (size > 0) {
            if (size > 1) {
                intent.setAction(Intent.ACTION_SEND_MULTIPLE);
                intent.setType(GalleryUtils.MIME_TYPE_PANORAMA360);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                intent.setAction(Intent.ACTION_SEND);
                intent.setType(GalleryUtils.MIME_TYPE_PANORAMA360);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }

        return intent;
    }

    private Intent computeSharingIntent(JobContext jc) {
        //PR-540789,541989 added by xiaowei.xu begin
        //ArrayList<Path> expandedPaths = mSelectionManager.getSelected(true);
        ArrayList<Path> expandedPaths = mSelectionManager.getSelected(jc, true);
        if (expandedPaths == null || jc.isCancelled()) {
            Log.i(TAG, "<computeSharingIntent> jc.isCancelled() - 1");
            return null;
        }
        //PR-540789,541989 added by xiaowei.xu end

        if (expandedPaths.size() == 0) {
            setNfcBeamPushUris(null);
            return null;
        }
        final ArrayList<Uri> uris = new ArrayList<Uri>();
        DataManager manager = mActivity.getDataManager();

        int mediaType = 0;
        boolean includedStereoImage = false;

        int type = 0;
        //PR-540789,541989 added by xiaowei.xu begin
        int totalUriSize = 0;
        //PR-540789,541989 added by xiaowei.xu end
        final Intent intent = new Intent();
        for (Path path : expandedPaths) {
            //PR-540789,541989 added by xiaowei.xu begin
           // if (jc.isCancelled()) return null;
           if (jc.isCancelled()) {
                Log.i(TAG, "<computeSharingIntent> jc.isCancelled() - 2");
                return null;
            }
            //PR-540789,541989 added by xiaowei.xu end
            int support = manager.getSupportedOperations(path);
            mediaType = manager.getMediaType(path);
            type |= mediaType;

            if ((support & MediaObject.SUPPORT_SHARE) != 0) {
               //PR-540789,541989 added by xiaowei.xu begin
                totalUriSize += manager.getContentUri(path).toString().length();
                uris.add(manager.getContentUri(path));
                //PR-540789,541989 added by xiaowei.xu end
            }

            if (mIsStereoDisplaySupported &&
                MediaObject.MEDIA_TYPE_IMAGE == mediaType &&
                (support & MediaObject.SUPPORT_STEREO_DISPLAY) != 0 &&
                (support & MediaObject.SUPPORT_CONVERT_TO_3D) == 0) {
                //we found a stereo image, record this info
                includedStereoImage = true;
                //PR-540789,541989 added by xiaowei.xu begin
                if (totalUriSize > SHARE_URI_SIZE_LIMITATION) {
                    MtkLog.i(TAG, "totalUriSize > SHARE_URI_SIZE_LIMITATION");
                   break;
              }
              //PR-540789,541989 added by xiaowei.xu ene

            }
        }

        final int size = uris.size();
        if (size > 0) {
            final String mimeType = MenuExecutor.getMimeType(type);
            if (size > 1) {
                intent.setAction(Intent.ACTION_SEND_MULTIPLE).setType(mimeType);
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else {
                intent.setAction(Intent.ACTION_SEND).setType(mimeType);
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            //there is some stereo image in the list, we record this info
            if (mIsStereoDisplaySupported && includedStereoImage) {
                Log.i(TAG,"updateSharingIntent:stereo image included in intent");
                intent.putExtra(StereoHelper.INCLUDED_STEREO_IMAGE, true);
            }

            setNfcBeamPushUris(uris.toArray(new Uri[uris.size()]));
        } else {
            setNfcBeamPushUris(null);
        }

        return intent;
    }

    public void updateSupportedOperation(Path path, boolean selected) {
        // TODO: We need to improve the performance
        updateSupportedOperation();
    }

    public void updateSupportedOperation() {
        //PR-540789,541989 added by xiaowei.xu begin
        // M: if menu task is running, we do not updateSupportedOperation once more
       /* if (mIsUpdateSupportedOperationRunning) {
            Log.i(TAG,"updateSupportedOperation: the menu task has not done, return");
            return;
        } else {
            Log.i(TAG,"updateSupportedOperation: begin, set mIsUpdateSupportedOperationRunning = true");
            mIsUpdateSupportedOperationRunning = true;
        }*/
        
        //PR-540789,541989 added by xiaowei.xu end

        // Interrupt previous unfinished task, mMenuTask is only accessed in main thread
        if (mMenuTask != null) {
            mMenuTask.cancel();
        }

        updateSelectionMenu();

        // Disable share actions until share intent is in good shape
        if (mSharePanoramaMenuItem != null) mSharePanoramaMenuItem.setEnabled(false);
        if (mShareMenuItem != null) mShareMenuItem.setEnabled(false);
        
        // Generate sharing intent and update supported operations in the background
        // The task can take a long time and be canceled in the mean time.
        mMenuTask = mActivity.getThreadPool().submit(new Job<Void>() {
            @Override
            public Void run(final JobContext jc) {
                // Pass1: Deal with unexpanded media object list for menu operation.
                // M: temporarily disable the menu to avoid mis-operation
                // during menu compute
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        MenuExecutor.updateSupportedMenuEnabled(mMenu, MediaObject.SUPPORT_ALL, false);
                    }
                });
               //PR-540789,541989 added by xiaowei.xu begin
              final  ArrayList<MediaObject> selected = getSelectedMediaObjects(jc);
               //PR-540789,541989 added by xiaowei.xu end
                final int operation = computeMenuOptions(selected);
                if (jc.isCancelled()) {
               //PR-540789,541989 added by xiaowei.xu begin
                   // Log.i(TAG, "updateSupportedOperation: menu task cancelled," 
                    //        + "set mIsUpdateSupportedOperationRunning = false");
                  //  mIsUpdateSupportedOperationRunning = false;
                    Log.i(TAG, "<updateSupportedOperation> menu task cancelled 1");
               //PR-540789,541989 added by xiaowei.xu end
                    return null;
                }
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!jc.isCancelled()) {
                            MenuExecutor.updateMenuOperation(mMenu, operation);
                            // M: re-enable menu after compute and update finished
                            MenuExecutor.updateSupportedMenuEnabled(mMenu, MediaObject.SUPPORT_ALL, true);
                            if (mShareMenuItem != null) {
                             //PR-540789,541989 added by xiaowei.xu begin
                                /*mShareMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                                mShareMenuItem.setEnabled(false);
                                mShareMenuItem.setVisible(false);
                                mShareActionProvider.setShareIntent(null);*/
                                if(selected == null || selected.size() == 0) {
                                    mShareMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                                    mShareMenuItem.setEnabled(false);
                                    mShareMenuItem.setVisible(false);
                                    mShareActionProvider.setShareIntent(null);
                                } else {
                                    mShareMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                                    mShareMenuItem.setEnabled(false);
                                    mShareMenuItem.setVisible(true);
                                    /// M: when share intent is not ready, set INVALID_INTENT as share intent, 
                                    // when user click share icon, we will set SHARE_TARGET_SELECTION_IGNORE_ACTION 
                                    // as true in onShareTargertSelected, and show a wait toast @{
                                    Intent INVALID_INTENT = new Intent();
                                    INVALID_INTENT.setAction(Intent.ACTION_SEND_MULTIPLE).setType(GalleryUtils.MIME_TYPE_ALL);
                                    INVALID_INTENT.putExtra(INTENT_NOT_READY, true);
                                    mShareActionProvider.setShareIntent(INVALID_INTENT);
                                    // @}
                                }
                            //PR-540789,541989 added by xiaowei.xu end
                            }
                        }
                    }
                });
                
                // M: when there are many media items, computeSharingIntent will spend long time, 
                // so we do it after update menu operation.
             //PR-540789,541989 added by xiaowei.xu begin
               /* if (mShareMenuItem != null) {
                    Intent tempIntent = null;
                    try {
                        int count = 0;
                        Log.i(TAG, "updateSupportedOperation: computeSharingIntent begin");
                        while (tempIntent == null&&count<18) {
                            tempIntent = computeSharingIntent(jc);
                            Thread.sleep(50);
                            count++;
                        }
                        Log.i(TAG, "updateSupportedOperation: computeSharingIntent end");
                    } catch (java.util.ConcurrentModificationException e) {
                        Log.i(TAG, "updateSupportedOperation: ConcurrentModificationException,"
                                + "set mIsUpdateSupportedOperationRunning = false");
                        mIsUpdateSupportedOperationRunning = false;
                        return null;
                    } catch (Exception e) {
                    }*/
                if (mShareMenuItem != null && selected != null && selected.size() != 0) {
                   Log.i(TAG, "<updateSupportedOperation> computeSharingIntent begin");
                    final Intent intent = computeSharingIntent(jc);
                    Log.i(TAG, "<updateSupportedOperation> computeSharingIntent end");

                    if (jc.isCancelled()) {
                      //  Log.i(TAG, "updateSupportedOperation: menu task cancelled," 
                       //         + "set mIsUpdateSupportedOperationRunning = false");
                        //mIsUpdateSupportedOperationRunning = false;
                        Log.i(TAG, "<updateSupportedOperation> menu task cancelled 3");
                        return null;
                    }
                    //final Intent intent = tempIntent;
                    if (intent != null) {
                        mMainHandler.post(new Runnable() {
                            public void run() {
                                mMenuTask = null;
                                if (!jc.isCancelled()) {
                                    mShareMenuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                                    mShareMenuItem.setEnabled(true);
                                    mShareMenuItem.setVisible(true);
                                    mShareActionProvider.setShareIntent(intent);
                                } else {
                                    Log.i(TAG, "<updateSupportedOperation> menu task cancelled 4");
                                }
                            }
                        });
                    }
                }
                //M: when menu task finish, set this flag as true
                 // Log.i(TAG, "updateSupportedOperation: menu task done," 
                  //      + "set mIsUpdateSupportedOperationRunning = false");
                //mIsUpdateSupportedOperationRunning = false;
                Log.i(TAG, "<updateSupportedOperation> menu task done");
                //PR-540789,541989 added by xiaowei.xu end
                return null;
            }
        });
    }

    public void pause() {
        if (mMenuTask != null) {
            mMenuTask.cancel();
            mMenuTask = null;
          //PR-540789,541989 added by xiaowei.xu begin
            //mIsUpdateSupportedOperationRunning = false;
          //PR-540789,541989 added by xiaowei.xu end
        }
        if (mConvertIntentTask != null) {
            mConvertIntentTask.cancel();
            mConvertIntentTask = null;
        }
        mMenuExecutor.pause();
    }

    public void resume() {
         //PR-540789,541989 added by xiaowei.xu begin
    	 //PR500726-lilei-begin
    	  //mIsUpdateSupportedOperationRunning = false;
    	 //PR500726-lilei-end
         //PR-540789,541989 added by xiaowei.xu end
        if (mSelectionManager.inSelectionMode()) updateSupportedOperation();
    }

    private void checkStereoIntent(Intent intent) {
        if (null == intent || null == intent.getComponent()) {
            Log.e(TAG,"checkStereoIntent:invalid intent:"+intent);
            return;
        }

        String packageName = intent.getComponent().getPackageName();
        Log.d(TAG,"checkStereoIntent:packageName="+packageName);
        //this judgement is very simple, need to enhance in the future
        boolean onlyShareAs2D = "com.android.mms".equals(packageName);
        showStereoShareDialog(intent, onlyShareAs2D);
    }

    private void showStereoShareDialog(Intent intent, boolean shareAs2D) {
        int positiveCap = 0;
        int negativeCap = 0;
        int title = 0;
        int message = 0;
        boolean multipleSelected = intent.getAction() == Intent.ACTION_SEND_MULTIPLE;

        if (shareAs2D) {
            positiveCap = android.R.string.ok;
            negativeCap = android.R.string.cancel;
            title = R.string.stereo3d_convert2d_dialog_title;
            if (multipleSelected) {
                message = R.string.stereo3d_share_convert_text_multiple;
            } else {
                message = R.string.stereo3d_share_convert_text_single;
            }
        } else {
            positiveCap = R.string.stereo3d_share_dialog_button_2d;
            negativeCap = R.string.stereo3d_share_dialog_button_3d;
            title = R.string.stereo3d_share_dialog_title;
            if (multipleSelected) {
                message = R.string.stereo3d_share_dialog_text_multiple;
            } else {
                message = R.string.stereo3d_share_dialog_text_single;
            }
        }
        final Intent shareIntent = intent;
        final boolean onlyShareAs2D = shareAs2D;
        final AlertDialog.Builder builder =
                        new AlertDialog.Builder((Context)mActivity);

        DialogInterface.OnClickListener clickListener =
            new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    if (DialogInterface.BUTTON_POSITIVE == which) {
                        convertAndShare(shareIntent);
                    } else {
                        if (!onlyShareAs2D) {
                            safeStartIntent(shareIntent);
                        }
                    }
                    dialog.dismiss();
                }
            };
        builder.setPositiveButton(positiveCap, clickListener);
        builder.setNegativeButton(negativeCap, clickListener);
        builder.setTitle(title)
               .setMessage(message);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void safeStartIntent(Intent intent) {
        try {
            ((Activity)mActivity).startActivity(intent);
        } catch (android.content.ActivityNotFoundException ex) {
            android.widget.Toast.makeText(((Activity)mActivity), 
                ((Activity)mActivity).getString(R.string.activity_not_found),
                android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void convertAndShare(final Intent intent) {
        Log.i(TAG,"convertAndShare(intent="+intent+")");
        if (mConvertIntentTask != null) {
            mConvertIntentTask.cancel();
        }
        //show converting dialog
        int messageId = R.string.stereo3d_convert2d_progress_text;
        mProgressDialog = ProgressDialog.show(
                ((Activity)mActivity), null, 
                ((Activity)mActivity).getString(messageId), true, false);
        //create a job that convert intents and start sharing intent.
        mConvertIntentTask = mActivity.getThreadPool().submit(new Job<Void>() {
            public Void run(JobContext jc) {
                //the majer process!
                processIntent(jc, intent);
                //dismis progressive dialog when we done
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mConvertIntentTask = null;
                        if (null != mProgressDialog) {
                            Log.v(TAG,"mConvertIntentTask:dismis ProgressDialog");
                            mProgressDialog.dismiss();
                        }
                    }
                });
                //start new intent
                if (!jc.isCancelled()) {
                    safeStartIntent(intent);
                }
                return null;
            }
        });
    }

    private void processIntent(JobContext jc, Intent intent) {
        DataManager manager = mActivity.getDataManager();
        Path itemPath = null;
        MediaItem item = null;
        int support = 0;
        Uri convertedUri = null;
        if (jc.isCancelled()) return;
        if (intent.getAction() == Intent.ACTION_SEND_MULTIPLE) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            ArrayList<Uri> newUris = StereoConvertor.convertMultiple(jc, mActivity, uris);
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, newUris);
        } else if (intent.getAction() == Intent.ACTION_SEND) {
            Uri uri = (Uri)intent.getExtra(Intent.EXTRA_STREAM);
            Log.v(TAG,"processIntent:send single:uri="+uri);
            itemPath = manager.findPathByUri(uri, intent.getType());
            item = (MediaItem) manager.getMediaObject(itemPath);
            if (StereoHelper.isStereoImage(item)) {
                convertedUri = StereoConvertor.convertSingle(jc, (Context)mActivity,
                                                             uri, item.getMimeType());
                Log.i(TAG,"processIntent:got new Uri="+convertedUri);
                //temporarily workaround
                if (null == convertedUri) {
                    Log.e(TAG,"processIntent:convert failed, insert original");
                    convertedUri = manager.getContentUri(itemPath);
                }
                intent.putExtra(Intent.EXTRA_STREAM, convertedUri);
            }
        }
    }
}
