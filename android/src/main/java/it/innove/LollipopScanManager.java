package it.innove;


import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelUuid;
import android.util.Log;

import com.facebook.react.HeadlessJsTaskService;
import com.facebook.react.bridge.*;
import com.google.gson.Gson;

import java.sql.Time;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.facebook.react.bridge.UiThreadUtil.runOnUiThread;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class LollipopScanManager extends ScanManager {

    ReactApplicationContext mContext;
    Date lastBackgroundUpdate = new Date(0);
    Date startedListen = new Date();

    private  boolean mallowBackgroundHeadlessTask;
    private int mbackgroundWakeupInterval;
    private int mbackgroundWakeupDuration;

	public LollipopScanManager(ReactApplicationContext reactContext, BleManager bleManager, boolean allowBackgroundHeadlessTask, int backgroundWakeupInterval, int backgroundWakeupDuration) {
		super(reactContext, bleManager);
		mContext = reactContext;
        mallowBackgroundHeadlessTask = allowBackgroundHeadlessTask;
        mbackgroundWakeupInterval = backgroundWakeupInterval;
        mbackgroundWakeupDuration = backgroundWakeupDuration;
	}

	@Override
	public void stopScan(Callback callback) {
		// update scanSessionId to prevent stopping next scan by running timeout thread
		scanSessionId.incrementAndGet();

		getBluetoothAdapter().getBluetoothLeScanner().stopScan(mScanCallback);
		callback.invoke();
	}

    @Override
    public void scan(ReadableArray serviceUUIDs, final int scanSeconds, ReadableMap options,  Callback callback) {
        ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder();
        List<ScanFilter> filters = new ArrayList<>();
        
        scanSettingsBuilder.setScanMode(options.getInt("scanMode"));
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scanSettingsBuilder.setNumOfMatches(options.getInt("numberOfMatches"));
            scanSettingsBuilder.setMatchMode(options.getInt("matchMode"));
        }
        
        if (serviceUUIDs.size() > 0) {
            for(int i = 0; i < serviceUUIDs.size(); i++){
				ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(UUIDHelper.uuidFromString(serviceUUIDs.getString(i)))).build();
                filters.add(filter);
                Log.d(bleManager.LOG_TAG, "Filter service: " + serviceUUIDs.getString(i));
            }
        }
        
        getBluetoothAdapter().getBluetoothLeScanner().startScan(filters, scanSettingsBuilder.build(), mScanCallback);
        if (scanSeconds > 0) {
            Thread thread = new Thread() {
                private int currentScanSession = scanSessionId.incrementAndGet();
                
                @Override
                public void run() {
                    
                    try {
                        Thread.sleep(scanSeconds * 1000);
                    } catch (InterruptedException ignored) {
                    }
                    
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            BluetoothAdapter btAdapter = getBluetoothAdapter();
                            // check current scan session was not stopped
                            if (scanSessionId.intValue() == currentScanSession) {
                                if(btAdapter.getState() == BluetoothAdapter.STATE_ON) {
                                    btAdapter.getBluetoothLeScanner().stopScan(mScanCallback);
                                }
                                WritableMap map = Arguments.createMap();
                                bleManager.sendEvent("BleManagerStopScan", map);
                            }
                        }
                    });
                    
                }
                
            };
            thread.start();
        }
        callback.invoke();
    }

	private ScanCallback mScanCallback = new ScanCallback() {
		@Override
		public void onScanResult(final int callbackType, final ScanResult result) {
            Log.i(bleManager.LOG_TAG, "DiscoverPeripheral: " + result.getDevice().getName());

			runOnUiThread(new Runnable() {
				@Override
				public void run() {
                    Peripheral peripheral = bleManager.savePeripheral(result.getDevice(), result.getRssi(), result.getScanRecord());

                    if (isAppOnForeground(mContext)) {
                        WritableMap map = peripheral.asWritableMap();
                        bleManager.sendEvent("BleManagerDiscoverPeripheral", map);
                    } else {
                        if (!mallowBackgroundHeadlessTask) {
                            return;
                        }

                        Date now = new Date();

                        long diffInMiliSeconds = Math.abs(lastBackgroundUpdate.getTime() - now.getTime());
                        long diffInSeconds = TimeUnit.SECONDS.convert(diffInMiliSeconds, TimeUnit.MILLISECONDS);
                        if (diffInSeconds >= mbackgroundWakeupInterval) {
                            lastBackgroundUpdate = now;
                            startedListen = now;
                        }

                        long diffInMilisecondsFromStart = now.getTime() - startedListen.getTime();
                        long diffInSecondsFromStart = TimeUnit.SECONDS.convert(diffInMilisecondsFromStart, TimeUnit.MILLISECONDS);
                        if (diffInSecondsFromStart <= mbackgroundWakeupDuration) {
                            Gson gson = new Gson();
                            Intent serviceIntent = new Intent(context, HeadlessJobService.class);
                            serviceIntent.putExtra("peripheral", gson.toJson(peripheral));
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent);
                            } else {
                                context.startService(serviceIntent);
                            }
                            HeadlessJsTaskService.acquireWakeLockNow(context);
                        }
                    }
				}
			});
		}

		@Override
		public void onBatchScanResults(final List<ScanResult> results) {
		}

		@Override
		public void onScanFailed(final int errorCode) {
            WritableMap map = Arguments.createMap();
            bleManager.sendEvent("BleManagerStopScan", map);
		}
	};

    private boolean isAppOnForeground(Context context) {
        /**
         We need to check if app is in foreground otherwise the app will crash.
         http://stackoverflow.com/questions/8489993/check-android-application-is-in-foreground-or-not
         **/
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> appProcesses =
                activityManager.getRunningAppProcesses();
        if (appProcesses == null) {
            return false;
        }
        final String packageName = context.getPackageName();
        for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.importance ==
                    ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    appProcess.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}
