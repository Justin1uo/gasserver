package com.example.gas_server.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * BLE GATT 服务管理器
 * 职责：BLE 广播 + GATT Server，等待客户端连接并发送数据
 */
@SuppressLint("MissingPermission")
public class BluetoothServerManager {

    private static final String TAG = "BluetoothServerManager";

    // BLE UUID
    public static final UUID BLE_SERVICE_UUID =
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    public static final UUID BLE_CHARACTERISTIC_UUID =
            UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    public enum BluetoothMode {
        NONE, BLE_GATT
    }

    public interface Callback {
        void onModeChanged(BluetoothMode mode);
        void onAdvertisingChanged(boolean advertising);
        void onDeviceConnected(BluetoothDevice device);
        void onDeviceDisconnected(BluetoothDevice device);
        void onMtuChanged(int mtu);
        void onConnectionResult(BluetoothDevice device, boolean success, String message);
        void onError(String message);
    }

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private Callback callback;
    private Handler mainHandler;

    private BluetoothMode currentMode = BluetoothMode.NONE;

    // BLE 组件
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattService gattService;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private boolean isBleAdvertising = false;
    private int currentMtu = 23;

    // 已连接设备
    private final Set<BluetoothDevice> connectedDevices =
            Collections.synchronizedSet(new HashSet<>());

    public BluetoothServerManager(Context context) {
        this.context = context;
        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        mainHandler = new Handler(context.getMainLooper());
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    // ==================== 公开接口 ====================

    public boolean isBluetoothAvailable() {
        return bluetoothAdapter != null;
    }

    public boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    /** 启动 BLE GATT 服务（广播 + GATT Server） */
    public void start() {
        if (!isBluetoothAvailable() || !isBluetoothEnabled()) {
            notifyError("蓝牙不可用或未开启");
            return;
        }
        if (currentMode == BluetoothMode.BLE_GATT) return; // 已在运行

        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            notifyError("设备不支持 BLE 广播");
            return;
        }
        setupGattServer();
        startAdvertising();
    }

    /** 停止 BLE 服务 */
    public void stop() {
        stopBle();
        connectedDevices.clear();
        currentMode = BluetoothMode.NONE;
        notifyModeChanged(BluetoothMode.NONE);
    }

    /** 向所有已连接设备发送 JSON 数据 */
    public void sendData(String jsonData) {
        if (connectedDevices.isEmpty() || currentMode != BluetoothMode.BLE_GATT) return;
        byte[] data = jsonData.getBytes(StandardCharsets.UTF_8);
        sendBleData(data);
    }

    // ==================== 状态查询 ====================

    public boolean hasConnectedDevices() {
        return !connectedDevices.isEmpty();
    }

    public int getConnectedDeviceCount() {
        return connectedDevices.size();
    }

    public String getConnectedDevicesInfo() {
        if (connectedDevices.isEmpty()) return "无设备连接";
        StringBuilder sb = new StringBuilder();
        for (BluetoothDevice device : connectedDevices) {
            if (sb.length() > 0) sb.append("\n");
            String name = device.getName();
            sb.append(name != null ? name : "未知设备")
              .append(" (").append(device.getAddress()).append(")");
        }
        return sb.toString();
    }

    public Set<BluetoothDevice> getConnectedDevices() {
        return new HashSet<>(connectedDevices);
    }

    /** 断开指定设备 */
    public void disconnectDevice(BluetoothDevice device) {
        if (device == null) return;
        if (gattServer != null) {
            try { gattServer.cancelConnection(device); } catch (Exception ignored) {}
        }
        connectedDevices.remove(device);
        notifyDeviceDisconnected(device);
        Log.i(TAG, "已断开设备: " + device.getAddress());
    }

    public BluetoothMode getCurrentMode() {
        return currentMode;
    }

    public int getCurrentMtu() {
        return currentMtu;
    }

    // ==================== BLE GATT Server ====================

    private void setupGattServer() {
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback);
        if (gattServer == null) {
            Log.e(TAG, "无法打开 GATT Server");
            return;
        }
        gattService = new BluetoothGattService(BLE_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY);
        notifyCharacteristic = new BluetoothGattCharacteristic(
                BLE_CHARACTERISTIC_UUID,
                BluetoothGattCharacteristic.PROPERTY_READ |
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ);
        gattService.addCharacteristic(notifyCharacteristic);
        try {
            gattServer.addService(gattService);
        } catch (Exception e) {
            Log.e(TAG, "添加 GATT Service 失败", e);
        }
    }

    private void startAdvertising() {
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build();
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(BLE_SERVICE_UUID))
                .build();
        advertiser.startAdvertising(settings, data, advertiseCallback);
    }

    private void stopBle() {
        if (advertiser != null && isBleAdvertising) {
            advertiser.stopAdvertising(advertiseCallback);
            isBleAdvertising = false;
        }
        if (gattServer != null) {
            try { gattServer.close(); } catch (Exception ignored) {}
            gattServer = null;
        }
        notifyAdvertisingChanged(false);
    }

    private void sendBleData(byte[] data) {
        if (gattServer == null || notifyCharacteristic == null) return;
        if (data.length > currentMtu - 3) {
            sendBleDataChunked(data);
            return;
        }
        notifyCharacteristic.setValue(data);
        for (BluetoothDevice device : connectedDevices) {
            try {
                gattServer.notifyCharacteristicChanged(device, notifyCharacteristic, false);
            } catch (Exception e) {
                Log.e(TAG, "BLE 发送失败", e);
            }
        }
    }

    private void sendBleDataChunked(byte[] data) {
        int chunkSize = currentMtu - 3;
        int offset = 0;
        while (offset < data.length) {
            int end = Math.min(offset + chunkSize, data.length);
            byte[] chunk = new byte[end - offset];
            System.arraycopy(data, offset, chunk, 0, chunk.length);
            notifyCharacteristic.setValue(chunk);
            for (BluetoothDevice device : connectedDevices) {
                try {
                    gattServer.notifyCharacteristicChanged(device, notifyCharacteristic, false);
                } catch (Exception e) {
                    Log.e(TAG, "BLE 分包发送失败", e);
                }
            }
            offset = end;
            try { Thread.sleep(20); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    // ==================== BLE 回调 ====================

    private final AdvertiseCallback advertiseCallback = new AdvertiseCallback() {
        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            isBleAdvertising = true;
            currentMode = BluetoothMode.BLE_GATT;
            Log.i(TAG, "BLE 广播已启动");
            notifyAdvertisingChanged(true);
            notifyModeChanged(BluetoothMode.BLE_GATT);
        }

        @Override
        public void onStartFailure(int errorCode) {
            isBleAdvertising = false;
            String msg;
            switch (errorCode) {
                case ADVERTISE_FAILED_DATA_TOO_LARGE: msg = "广播数据过大"; break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS: msg = "广播器数量过多"; break;
                case ADVERTISE_FAILED_ALREADY_STARTED: msg = "广播已在进行"; break;
                case ADVERTISE_FAILED_INTERNAL_ERROR: msg = "广播内部错误"; break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED: msg = "不支持 BLE 广播"; break;
                default: msg = "广播失败，错误码: " + errorCode;
            }
            Log.e(TAG, "BLE 广播失败: " + msg);
            notifyError("BLE 广播失败: " + msg);
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device);
                Log.i(TAG, "BLE 设备已连接: " + device.getAddress());
                notifyDeviceConnected(device);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedDevices.remove(device);
                Log.i(TAG, "BLE 设备已断开: " + device.getAddress());
                notifyDeviceDisconnected(device);
            }
        }

        @Override
        public void onMtuChanged(BluetoothDevice device, int mtu) {
            currentMtu = mtu;
            Log.i(TAG, "MTU 变更为: " + mtu);
            notifyMtuChanged(mtu);
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId,
                int offset, BluetoothGattCharacteristic characteristic) {
            if (BLE_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] value = characteristic.getValue();
                if (value == null) value = "{}".getBytes(StandardCharsets.UTF_8);
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value);
            } else {
                gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null);
            }
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                android.bluetooth.BluetoothGattDescriptor descriptor,
                boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
            if (CCCD_UUID.equals(descriptor.getUuid())) {
                if (responseNeeded) {
                    gattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null);
                }
                Log.i(TAG, "Client 订阅 CCCD: " + device.getAddress());
            }
        }
    };

    // ==================== 回调通知 ====================

    private void notifyModeChanged(BluetoothMode mode) {
        if (callback != null) mainHandler.post(() -> callback.onModeChanged(mode));
    }

    private void notifyAdvertisingChanged(boolean advertising) {
        if (callback != null) mainHandler.post(() -> callback.onAdvertisingChanged(advertising));
    }

    private void notifyDeviceConnected(BluetoothDevice device) {
        if (callback != null) mainHandler.post(() -> callback.onDeviceConnected(device));
    }

    private void notifyDeviceDisconnected(BluetoothDevice device) {
        if (callback != null) mainHandler.post(() -> callback.onDeviceDisconnected(device));
    }

    private void notifyMtuChanged(int mtu) {
        if (callback != null) mainHandler.post(() -> callback.onMtuChanged(mtu));
    }

    private void notifyConnectionResult(BluetoothDevice device, boolean success, String message) {
        if (callback != null) mainHandler.post(() -> callback.onConnectionResult(device, success, message));
    }

    private void notifyError(String message) {
        if (callback != null) mainHandler.post(() -> callback.onError(message));
    }
}
