package com.aylmerchen.lib;

import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.util.ArraySet;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 低功耗蓝牙扫描服务，负责低功耗蓝牙的连接
 * @author AylmerChen
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class BtLeScanService extends Service {

    public static final String TAG = BtLeScanService.class.getSimpleName();

    /**
     * 默认的扫描时限,单位毫秒
     */
    private static final long MAX_SCAN_TIME = 3000;

    /**
     * 向外部返回的 扫描事件 的标记(msg.what)
     */
    public static final int MSG_SCAN_DEVICE_FOUND = 0;
    public static final int MSG_SCAN_STOP = 1;
    public static final int MSG_SCAN_ERROR = 2;

    /**
     * 版本低于 21，用此类来扫描
     */
    private BluetoothAdapter mBluetoothAdapter;

    /**
     * 版本高于 21，用此类来扫描
     */
    private BluetoothLeScanner mBluetoothLeScanner;

    /**
     * 外部传入，用于向外部传递 扫描事件
     */
    private Handler scanHandler;

    /**
     * 记录扫描到的设备
     */
    private Set<String> deviceSet;

    /**
     * 记录是否处在扫描过程中
     */
    private AtomicBoolean isScanning = new AtomicBoolean(false);

    /**
     * 用于扫描定时的线程池
     */
    private ScheduledExecutorService pool;

    /**
     * 版本高于 21 的扫描回调
     */
    private ScanCallback newScanCallback;

    /**
     * 版本低于 21 的扫描回调
     */
    private BluetoothAdapter.LeScanCallback oldScanCallback;

    /**
     * 向前台返回的 binder
     */
    private IBinder mBinder;


    public class ScanBinder extends Binder {
        public BtLeScanService getService() {
            return BtLeScanService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        if(!initService()){
            mBinder = null;
        } else {

            // 初始化 binder
            mBinder = new ScanBinder();

            // TODO 服务初始化成功，创建定时线程池, 应阿里规范的要求，以后再自定义线程池吧，先把论文肝了
            pool = Executors.newSingleThreadScheduledExecutor();

            // 初始化回调接口
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {

                newScanCallback = new ScanCallback() {

                    @Override
                    public void onScanResult(int callbackType, ScanResult result) {
                        super.onScanResult(callbackType, result);

                        String deviceAddress = result.getDevice().getAddress();
                        if ( deviceAddress != null && deviceSet != null && !deviceSet.contains(deviceAddress) && result.getDevice().getName() != null) {
                            deviceSet.add(deviceAddress);
                            scanHandler.obtainMessage(MSG_SCAN_DEVICE_FOUND, result.getDevice()).sendToTarget();
                        }
                    }

                    @Override
                    public void onScanFailed(int errorCode) {
                        super.onScanFailed(errorCode);
                        scanHandler.obtainMessage(MSG_SCAN_ERROR).sendToTarget();
                    }
                };
            } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2){

                oldScanCallback = new BluetoothAdapter.LeScanCallback() {

                    @Override
                    public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {

                        String deviceAddress = device.getAddress();
                        if ( deviceAddress != null && deviceSet != null && !deviceSet.contains(deviceAddress) && device.getName() != null) {
                            deviceSet.add(deviceAddress);
                            scanHandler.obtainMessage(MSG_SCAN_DEVICE_FOUND, device).sendToTarget();
                        }
                    }
                };
            }

        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if(pool.isShutdown()){
            pool.shutdownNow();
            pool = null;
        }
        stopScan();
        mBinder = null;
        newScanCallback = null;
        oldScanCallback = null;
        scanHandler = null;
    }

    /**
     * 初始化，在服务中获得蓝牙模块的引用
     * @return 初始化是否成功
     */
    private boolean initService() {

        BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager == null) {
            return false;
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            return false;
        }

        // 版本高于 21 ，采用新的 api
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();
            return mBluetoothLeScanner != null;
        }
        return true;
    }

    /**
     * 外部传入的 Handler 用于服务所在线程向外部通知扫描结果
     * @param handler 外部的 Handler
     */
    public void setScanHandler(Handler handler){
        scanHandler = handler;
    }

    /**
     * 开始扫描，扫描默认时间
     */
    public void startScan(){
        deviceSet = new ArraySet<>();
        startScan(MAX_SCAN_TIME, TimeUnit.MILLISECONDS);
    }

    /**
     * 开始扫描，扫描指定时间
     * @param scanTime 指定最长扫描时间
     */
    public void startScan(long scanTime, TimeUnit unit){

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            if ( mBluetoothLeScanner != null) {
                // 定时时间到则停止扫描
                pool.schedule(new Runnable() {
                    @Override
                    public void run() {
                        stopScan();
                    }
                }, scanTime, unit);

                mBluetoothLeScanner.startScan(newScanCallback);
                isScanning.set(true);
            }
        } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {

            if(mBluetoothAdapter != null){

                // 定时时间到则停止扫描
                pool.schedule(new Runnable() {
                    @Override
                    public void run() {
                        stopScan();
                    }
                }, scanTime, unit);

                mBluetoothAdapter.startLeScan(oldScanCallback);
                isScanning.set(true);
            }
        }
    }


    /**
     * 供外部手动停止扫描
     */
    public void stopScan(){
        if ( isScanning.get() ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if ( mBluetoothLeScanner != null) {
                    mBluetoothLeScanner.stopScan(newScanCallback);
                    isScanning.set(false);
                    scanHandler.obtainMessage(MSG_SCAN_STOP).sendToTarget();
                }
            } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2){
                if ( mBluetoothAdapter != null) {
                    mBluetoothAdapter.stopLeScan(oldScanCallback);
                    isScanning.set(false);
                    scanHandler.obtainMessage(MSG_SCAN_STOP).sendToTarget();
                }
            }
        }

        if (deviceSet != null) {
            deviceSet.clear();
            deviceSet = null;
        }
    }

}
