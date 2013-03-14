package com.theksmith.steeringwheelinterface;

import com.theksmith.steeringwheelinterface.R;

import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.TaskStackBuilder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;


/**
 * background service that keeps running even if main activity is destroyed
 * manages the vehicle interface and provide notifications with it's status
 * 
 * @author Kristoffer Smith <stuff@theksmith.com>
 */
public class SteeringWheelInterfaceService extends Service {
	protected static final String TAG = SteeringWheelInterfaceService.class.getSimpleName();	
	
	protected static final int WATCHDOG_INTERVAL = 30000;
	
	protected final Handler watchdog_Timer = new Handler();
	
	protected NotificationManager mNoticeManager;
	protected final Builder mNoticeBuilder = new Builder(this);		
	protected int mNoticeID;
	
	protected ElmInterface mCarInterface;
	protected int mCarInterfaceID = 0;

	
	/**
	 * watch for device removal
	 * stop interface or exit app completely depending on settings
	 */
	public BroadcastReceiver mUsbDetachedReceiver = new BroadcastReceiver() {
		public void onReceive(Context context, Intent intent) {
			if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
				SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
				Boolean exitPrefDefault = Boolean.parseBoolean(getString(R.string.scantool_detach_disconnect));
				Boolean exitPrefValue = settings.getBoolean("scantool_detach_disconnect", exitPrefDefault);
				
				if (exitPrefValue) {
					UsbDevice device = (UsbDevice)intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
	
					Log.i("+++++++++++++", device.getDeviceId() + " ++++++++++++ " + SteeringWheelInterfaceService.this.mCarInterfaceID + "");
					
					//verify this notice is actually the specific device we opened, don't close another app's FTDI device
					if (device != null && device.getDeviceId() == SteeringWheelInterfaceService.this.mCarInterfaceID) {
						Intent exitIntent = new Intent(getBaseContext(), SteeringWheelInterfaceActivity.class);
						exitIntent.setAction(Intent.ACTION_DELETE);
						exitIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						startActivity(exitIntent);
						
						Toast.makeText(getApplicationContext(), getString(R.string.msg_device_disconnected), Toast.LENGTH_SHORT).show();
					}
				} else {
					carInterfaceStop();
				}
			}
		}
	};
	
	
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	
	@Override
	public void onDestroy() {
		watchdog_TimerStop();
		
		unregisterReceiver(mUsbDetachedReceiver);

		if (mCarInterface != null) {
			mCarInterface.deviceClose();			
			mCarInterface = null;
		}
		
		mNoticeManager.cancelAll();
	}
   
	
	@Override
	public void onCreate() {
		IntentFilter filterUsbDetached = new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED);
		registerReceiver(mUsbDetachedReceiver, filterUsbDetached);

		Intent settingsIntent = new Intent(this, SteeringWheelInterfaceActivity.class);
		settingsIntent.setAction(Intent.ACTION_EDIT);
		TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
		stackBuilder.addParentStack(SteeringWheelInterfaceActivity.class);
		stackBuilder.addNextIntent(settingsIntent);
		PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);

		mNoticeBuilder.setContentIntent(resultPendingIntent);
		mNoticeBuilder.setSmallIcon(R.drawable.ic_notice);
		mNoticeBuilder.setContentTitle(getString(R.string.app_name));
		mNoticeBuilder.setContentText(getString(R.string.msg_app_starting));

		mNoticeManager = (NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
		mNoticeManager.notify(mNoticeID, mNoticeBuilder.build());
		
		mCarInterface = new ElmInterface(getApplicationContext());		
	}

	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		startForeground(mNoticeID, mNoticeBuilder.build());
		
		carInterfaceRestartIfNeeded();

		watchdog_TimerReStart();
				
		return START_STICKY;
	}
	

	/**
	 * monitors the interface, restarts it when needed, and reports status as notifications
	 */
	protected void carInterfaceRestartIfNeeded() {
		try {
			if (mCarInterface.getsStatus() == ElmInterface.STATUS_OPEN_STOPPED) {
				mCarInterface.monitorStart();
			} else if (mCarInterface.getsStatus() != ElmInterface.STATUS_OPEN_MONITORING) {
				mCarInterfaceID = mCarInterface.deviceOpen();
				if (mCarInterface.getsStatus() == ElmInterface.STATUS_OPEN_STOPPED) {
					mCarInterface.monitorStart();
				} //else didn't finish opening, but should be good by next time around, so do nothing special now
			}
		} catch (Exception ex) {
			Log.e(TAG, "ERROR STARTING CAR INTERFACE", ex);
		}

		if (mCarInterface.getsStatus() == ElmInterface.STATUS_OPEN_MONITORING) {
			mNoticeBuilder.setContentText(getString(R.string.msg_monitoring));
		} else {
			mNoticeBuilder.setContentText(getString(R.string.msg_monitoring_stopped));
		}
		mNoticeManager.notify(mNoticeID, mNoticeBuilder.build());
	}
	
	
	protected void carInterfaceStop() {
		try {
			mCarInterface.deviceClose();			
		} catch (Exception ex) {
			Log.e(TAG, "ERROR STOPPING CAR INTERFACE", ex);
		}
		
		mNoticeBuilder.setContentText(getString(R.string.msg_monitoring_stopped));
		mNoticeManager.notify(mNoticeID, mNoticeBuilder.build());
	}


	protected Runnable watchdog_TimerRun = new Runnable() {
		public void run() {
			carInterfaceRestartIfNeeded();
			watchdog_TimerReStart();
		}
	};
	
		
	protected void watchdog_TimerStop() {
		watchdog_Timer.removeCallbacks(watchdog_TimerRun);
	}

	
	protected void watchdog_TimerReStart() {
		watchdog_TimerStop();
		watchdog_Timer.postDelayed(watchdog_TimerRun, WATCHDOG_INTERVAL);
	}
}