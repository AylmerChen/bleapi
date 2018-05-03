/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.aylmerchen.ble;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.NonNull;

import java.util.List;
import java.util.UUID;


/**
 * GATT 服务类，负责低功耗蓝牙的通信
 * @author AylmerChen
 * @date 2018/4/17
 */
public class BtLeGattService extends Service {

    private static final String TAG = BtLeGattService.class.getSimpleName();

    /**
     * BLE 官方规定的内容：
     * descriptor : Client Characteristic Configuration
     * GATT 服务中 客户端服务属性配置描述符的 UUID，可以使能服务属性的通知功能
     */
    private static final String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";

    /**
     * 当前的连接状态
     */
    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED_NOT_CONFIGURED = 2;
    public static final int STATE_CONNECTED_AND_CONFIGURED = 3;

    /**
     * 建立连接的过程中向前台回传的事件
     */
    public static final int MSG_GATT_CONNECT_SUCCESS = 0;
    public static final int MSG_GATT_CONNECT_FAIL = 1;
    public static final int MSG_GATT_SERVICES_DISCOVERED_SUCCESS = 2;
    public static final int MSG_GATT_SERVICES_DISCOVERED_FAIL = 4;
    public static final int MSG_GATT_DESCRIPTOR_WRITE_SUCCESS = 8;
    public static final int MSG_GATT_DESCRIPTOR_WRITE_FAIL = 16;
    public static final int MSG_GATT_DESCRIPTOR_READ_SUCCESS = 32;
    public static final int MSG_GATT_DESCRIPTOR_READ_FAIL = 64;
    public static final int MSG_GATT_LOSE_CONNECT = 128;


    /**
     * 连接建立后的通信过程中，向前台回传的事件
     */
    public static final int MSG_DATA_AVAILABLE = 0;
    public static final int MSG_DATA_WRITE_SUCCESS = 1;
    public static final int MSG_DATA_WRITE_FAIL = 2;
    public static final int MSG_DATA_RELIABLE_WRITE_SUCCESS = 4;
    public static final int MSG_DATA_RELIABLE_WRITE_FAIL = 8;
    public static final int MSG_DATA_READ_SUCCESS = 16;
    public static final int MSG_DATA_READ_FAIL = 32;


    /**
     * 低功耗蓝牙发送单个包的最大长度
     */
    public static final int SEND_PACKAGE_MAX_SIZE = 19;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;

    private Handler mConnectHandler;
    private Handler mTransmitHandler;

    /**
     * 接收数据的属性,发送数据的属性
     */
    private BluetoothGattCharacteristic mReadChara;
    private BluetoothGattCharacteristic mWriteChara;


    private int mConnectionState = STATE_DISCONNECTED;

    private BluetoothGattCallback mGattCallback;

    public class BtLeServiceBinder extends Binder {
        public BtLeGattService getService() {
            return BtLeGattService.this;
        }
    }
    private IBinder mBinder = new BtLeServiceBinder();

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
    @Override
    public boolean onUnbind(Intent intent) {

        // Android 最多支持连接 6 到 7 个左右的蓝牙设备，如果超出了这个数量就无法再连接了。
        // 所以当我们断开蓝牙设备的连接时，还必须调用 BluetoothGatt#closeGATT 方法释放连接资源。
        // 否则，在多次尝试连接蓝牙设备之后很快就会超出这一个限制，导致出现这一个错误再也无法连接蓝牙设备
        closeGATT();
        return super.onUnbind(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 服务刚创建时进行初始化工作，初始化失败则返回的 binder 为null,
        // 供外部检测初始化是否成功
        if( !initService() ){
            mBinder = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        closeGATT();
        mBinder = null;
        mConnectHandler = null;
        mTransmitHandler = null;
    }

    /**
     * 初始化，在服务中获得蓝牙模块的引用,初始化通信回调
     * @return 初始化是否成功
     */
    private boolean initService() {

        BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (mBluetoothManager != null) {
            mBluetoothAdapter = mBluetoothManager.getAdapter();
            if (mBluetoothAdapter != null) {
                mGattCallback = new BluetoothGattCallback() {

                    // 连接建立过程回调
                    @Override
                    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

                        if(gatt == mBluetoothGatt){
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                if (newState == BluetoothProfile.STATE_CONNECTED) {

                                    mConnectionState = STATE_CONNECTED_NOT_CONFIGURED;
                                    mConnectHandler.obtainMessage(MSG_GATT_CONNECT_SUCCESS, gatt.getDevice()).sendToTarget();

                                    // 尝试获取设备服务列表，结果由 onServicesDiscovered 回调
                                    mBluetoothGatt.discoverServices();

                                } else {
                                    mConnectionState = STATE_DISCONNECTED;
                                    mConnectHandler.obtainMessage(MSG_GATT_LOSE_CONNECT).sendToTarget();
                                }
                            } else {
                                mConnectHandler.obtainMessage(MSG_GATT_CONNECT_FAIL).sendToTarget();
                                closeGATT();
                            }
                        }
                    }

                    @Override
                    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                        if(gatt == mBluetoothGatt){
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                // 获取设备服务列表成功
                                mConnectHandler.obtainMessage(MSG_GATT_SERVICES_DISCOVERED_SUCCESS, gatt.getServices()).sendToTarget();
                            } else {
                                mConnectHandler.obtainMessage(MSG_GATT_SERVICES_DISCOVERED_FAIL).sendToTarget();
                                closeGATT();
                            }

                        }
                    }

                    @Override
                    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                        super.onDescriptorWrite(gatt, descriptor, status);

                        if (gatt == mBluetoothGatt) {

                            // 蓝牙 GATT 服务的属性默认是没有开启通知功能的，需要修改属性的描述符才能使能其通知功能，使得远端设备能通过该属性来向手机发送信息
                            if (descriptor.getUuid().equals(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG))) {

                                if(status == BluetoothGatt.GATT_SUCCESS){
                                    //属性描述符修改成功
                                    mConnectionState = STATE_CONNECTED_AND_CONFIGURED;
                                    mConnectHandler.obtainMessage(MSG_GATT_DESCRIPTOR_WRITE_SUCCESS).sendToTarget();
                                } else {
                                    mConnectHandler.obtainMessage(MSG_GATT_DESCRIPTOR_WRITE_FAIL).sendToTarget();
                                    closeGATT();
                                }
                            }
                        }
                    }

                    @Override
                    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                        super.onDescriptorRead(gatt, descriptor, status);
                        if(gatt == mBluetoothGatt){
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                mConnectHandler.obtainMessage(MSG_GATT_DESCRIPTOR_READ_SUCCESS, descriptor).sendToTarget();
                            } else {
                                mConnectHandler.obtainMessage(MSG_GATT_DESCRIPTOR_READ_FAIL).sendToTarget();
                            }
                        }
                    }




                    //------- 通信过程回调 --------------
                    @Override
                    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
                        if(gatt == mBluetoothGatt){
                            if(status == BluetoothGatt.GATT_SUCCESS){
                                mTransmitHandler.obtainMessage(MSG_DATA_READ_SUCCESS, characteristic.getValue()).sendToTarget();
                            } else {
                                mTransmitHandler.obtainMessage(MSG_DATA_READ_FAIL).sendToTarget();
                            }
                        }
                    }

                    @Override
                    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                        if (gatt == mBluetoothGatt) {
                            mTransmitHandler.obtainMessage(MSG_DATA_AVAILABLE , characteristic.getValue()).sendToTarget();
                        }
                    }

                    @Override
                    public void onCharacteristicWrite (BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status){

                        // 在可靠传输的情况下，每传输一次 20 字节的包，都需要远端返回再确认，
                        // 这里采用不可靠传输,所以是每次将数据交给蓝牙模块发送后，软件直接返回的回调，而不是远端设备返回的
                        if(gatt == mBluetoothGatt){
                            if( status == BluetoothGatt.GATT_SUCCESS){
                                mTransmitHandler.obtainMessage(MSG_DATA_WRITE_SUCCESS).sendToTarget();
                            } else {
                                mTransmitHandler.obtainMessage(MSG_DATA_WRITE_FAIL).sendToTarget();

                                // 传输出错，说明远端产生了错误，建议重新连接
                                closeGATT();
                            }
                        }

                    }

                    @Override
                    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
                        super.onReliableWriteCompleted(gatt, status);
                        if(gatt == mBluetoothGatt){
                            if(status == BluetoothGatt.GATT_SUCCESS){
                                mTransmitHandler.obtainMessage(MSG_DATA_RELIABLE_WRITE_SUCCESS).sendToTarget();
                            }else{
                                mTransmitHandler.obtainMessage(MSG_DATA_RELIABLE_WRITE_FAIL).sendToTarget();

                                // 传输出错，说明远端产生了错误，建议重新连接
                                closeGATT();
                            }
                        }
                    }

                };

                return true;
            }
        }
        return false;
    }

    /**
     * 开启 GATT 连接
     * @param address mac 地址
     */
    public void openGATT(@NonNull final String address) {
        if ( mBluetoothAdapter != null ) {

            // 查看当前是否存在与别的设备的连接
            if ( mBluetoothGatt != null) {
                closeGATT();
            }

            final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
            if (device != null) {
                // 直接主动发起连接, 所以将 autoConnect 设置成 false.
                mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
                mConnectionState = STATE_CONNECTING;
            }
        }
    }

    /**
     * 断开 GATT 连接
     */
    public void closeGATT() {
        if (mBluetoothGatt != null) {
            mBluetoothGatt.close();
        }
        mConnectionState = STATE_DISCONNECTED;
        mBluetoothGatt = null;
        mReadChara = null;
        mWriteChara = null;
    }

    /**
     * 查询式通信(即手机需要主动查询设备的值是否改变)是否建立完成
     */
    public boolean isConnectedNotConfigured(){
        return mConnectionState == STATE_CONNECTED_NOT_CONFIGURED;
    }

    /**
     * 中断式通信(即手机和设备都能主动向对方传输，设备主动传输时，手机这里会通过异步接口来向主线程传输结果)是否建立完成
     */
    public boolean isConnectedAndConfigured(){
        return mConnectionState == STATE_CONNECTED_AND_CONFIGURED;
    }

    /**
     * 建立连接的过程中向前台返回连接状态信息,连接过程的回调都由系统线程执行，所以需要跨线程通信
     */
    public void setConnectHandler(Handler handler){
        mConnectHandler = handler;
    }

    /**
     * 建立连接后，实际通信过程中向前台返回通信数据，通信过程的回调都由系统线程执行，所以需要跨线程通信
     */
    public void setTransmitHandler(Handler handler){
        mTransmitHandler = handler;
    }

    /**
     * 尝试主动读取远端设备的属性值(BluetoothGattCharacteristic)，结果通过 BluetoothGattCallback#onCharacteristicRead 接口返回
     */
    public void requestRead() {
        if (mBluetoothGatt != null && mReadChara != null) {
            mBluetoothGatt.readCharacteristic(mReadChara);
        }
    }

    /**
     * 对外传输的方法，修改远端设备的属性值，即向远端设备发送数据
     * @param data 待发送的数据,长度不能超过 19 字节
     */
    public void write(byte[] data){
        if (data.length <= SEND_PACKAGE_MAX_SIZE && mBluetoothGatt != null && mWriteChara != null) {
            mWriteChara.setValue(data);
            mBluetoothGatt.writeCharacteristic(mWriteChara);
        }
    }

    /**
     * 配置约定的属性
     * @param read 读属性
     * @param write 写属性
     * @param isNotify 是否使能读属性的通知功能
     * @return 是否成功配置通信功能
     */
    public boolean configCommunication(BluetoothGattCharacteristic read, BluetoothGattCharacteristic write, boolean isNotify) {
        if (mBluetoothAdapter != null && mBluetoothGatt != null) {

            mReadChara = read;
            mWriteChara = write;

            // 写属性设置成非可靠写，即只需要本地模块确认发送即可，无需远端确认接收
            mWriteChara.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);

            if (isNotify) {
                // 使能通知
                if( mBluetoothGatt.setCharacteristicNotification(mReadChara, true)){
                    // 配置描述符
                    BluetoothGattDescriptor descriptor = mReadChara.getDescriptor(UUID.fromString(CLIENT_CHARACTERISTIC_CONFIG));
                    descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    return mBluetoothGatt.writeDescriptor(descriptor);
                }
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     尝试从已连接的设备获取 GATT 服务列表，该方法在 BluetoothGatt#discoverServices() (发现服务) 成功后才能调用
     *
     * @return 返回设备所支持的 GATT 服务
     */
    public List<BluetoothGattService> getGattServiceList() {
        if (mBluetoothGatt == null){
            return null;
        }
        return mBluetoothGatt.getServices();
    }

}
