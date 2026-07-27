package com.example.gas_server.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 蓝牙服务管理器
 * 支持: BLE 广播/SPP 监听（等待连接）+ BLE/经典扫描（发现设备）+ 主动连接
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

    // SPP UUID
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");
    private static final String SPP_NAME = "GasServer";

    private static final long BLE_TIMEOUT_MS = 5000;
    private static final long SCAN_TIMEOUT_MS = 10000;

    public enum BluetoothMode {
        NONE, BLE_GATT, SPP
    }

    public enum ScanState {
        IDLE, SCANNING
    }

    /**
     * 扫描发现的设备信息
     */
    public static class ScannedDevice {
        public final BluetoothDevice device;
        public final int rssi;
        public final boolean isBle;

        public ScannedDevice(BluetoothDevice device, int rssi, boolean isBle) {
            this.device = device;
            this.rssi = rssi;
            this.isBle = isBle;
        }

        public String getName() {
            String name = device.getName();
            return name != null ? name : "未知设备";
        }

        public String getAddress() {
            return device.getAddress();
        }
    }

    public interface Callback {
        void onModeChanged(BluetoothMode mode);
        void onAdvertisingChanged(boolean advertising);
        void onDeviceConnected(BluetoothDevice device);
        void onDeviceDisconnected(BluetoothDevice device);
        void onMtuChanged(int mtu);
        void onScanStateChanged(ScanState state);
        void onDeviceFound(ScannedDevice device);
        void onConnectionResult(BluetoothDevice device, boolean success, String message);
        void onError(String message);
    }

    private final Context context;
    private final BluetoothManager bluetoothManager;
    private final BluetoothAdapter bluetoothAdapter;
    private Callback callback;
    private Handler mainHandler;

    private BluetoothMode currentMode = BluetoothMode.NONE;
    private ScanState scanState = ScanState.IDLE;

    // BLE 组件
    private BluetoothLeAdvertiser advertiser;
    private BluetoothGattServer gattServer;
    private BluetoothGattService gattService;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private boolean isBleAdvertising = false;
    private int currentMtu = 23;

    // SPP 组件
    private BluetoothServerSocket sppServerSocket;
    private BluetoothSocket sppClientSocket;
    private OutputStream sppOutputStream;
    private boolean isSppListening = false;

    // 客户端连接组件（主动连接其他设备）
    private BluetoothGatt clientGatt;
    private BluetoothSocket clientSocket;
    private OutputStream clientOutputStream;

    // 扫描结果（按地址去重）
    private final Map<String, ScannedDevice> scannedDevicesMap =
            Collections.synchronizedMap(new LinkedHashMap<>());

    // 已连接设备
    private final Set<BluetoothDevice> connectedDevices =
            Collections.synchronizedSet(new HashSet<>());

    private final HandlerThread dataThread = new HandlerThread("BleDataThread");

    private final Runnable bleTimeoutRunnable = () -> {
        if (currentMode == BluetoothMode.BLE_GATT && !hasConnectedDevices()) {
            Log.i(TAG, "BLE 5秒无连接，自动降级到 SPP");
            stopBle();
            startSpp();
        }
    };

    private final Runnable scanTimeoutRunnable = () -> {
        if (scanState == ScanState.SCANNING) {
            Log.i(TAG, "扫描超时，自动停止");
            stopScan();
        }
    };

    public BluetoothServerManager(Context context) {
        this.context = context;
        bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        mainHandler = new Handler(context.getMainLooper());
        dataThread.start();
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

    /** 启动服务（广播/监听），等待其他设备连接 */
    public void start() {
        if (!isBluetoothAvailable() || !isBluetoothEnabled()) {
            notifyError("蓝牙不可用或未开启");
            return;
        }
        if (tryStartBle()) {
            mainHandler.postDelayed(bleTimeoutRunnable, BLE_TIMEOUT_MS);
        } else {
            startSpp();
        }
    }

    /** 停止服务 */
    public void stop() {
        mainHandler.removeCallbacks(bleTimeoutRunnable);
        stopScan();
        stopBle();
        stopSpp();
        disconnectClient();
        connectedDevices.clear();
        currentMode = BluetoothMode.NONE;
        notifyModeChanged(BluetoothMode.NONE);
    }

    /** 向已连接设备发送数据 */
    public void sendData(String jsonData) {
        if (connectedDevices.isEmpty()) return;
        byte[] data = jsonData.getBytes(StandardCharsets.UTF_8);

        switch (currentMode) {
            case BLE_GATT:
                sendBleData(data);
                break;
            case SPP:
                sendSppData(jsonData);
                break;
        }
    }

    // ==================== 扫描功能 ====================

    /** 开始扫描附近蓝牙设备（BLE + 经典） */
    public void startScan() {
        if (!isBluetoothAvailable() || !isBluetoothEnabled()) {
            notifyError("蓝牙不可用或未开启");
            return;
        }
        if (scanState == ScanState.SCANNING) return;

        scannedDevicesMap.clear();
        scanState = ScanState.SCANNING;
        notifyScanStateChanged();

        // BLE 扫描
        try {
            android.bluetooth.le.BluetoothLeScanner bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner != null) {
                android.bluetooth.le.ScanSettings settings = new android.bluetooth.le.ScanSettings.Builder()
                        .setScanMode(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                bleScanner.startScan(null, settings, bleScanCallback);
                Log.i(TAG, "BLE 扫描已启动");
            } else {
                Log.w(TAG, "BLE Scanner 为 null");
            }
        } catch (SecurityException e) {
            Log.e(TAG, "BLE 扫描权限不足: " + e.getMessage());
            notifyError("缺少蓝牙扫描权限");
        } catch (Exception e) {
            Log.e(TAG, "BLE 扫描启动失败: " + e.getMessage());
        }

        // 经典蓝牙扫描
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            // 注册经典蓝牙广播接收器
            registerClassicScanReceiver();
            boolean started = bluetoothAdapter.startDiscovery();
            Log.i(TAG, "经典蓝牙扫描已启动: " + started);
        } catch (SecurityException e) {
            Log.e(TAG, "经典扫描权限不足: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "经典扫描启动失败: " + e.getMessage());
        }

        // 超时自动停止
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    /** 停止扫描 */
    public void stopScan() {
        if (scanState == ScanState.IDLE) return;

        // 停止 BLE 扫描
        android.bluetooth.le.BluetoothLeScanner bleScanner = bluetoothAdapter != null
                ? bluetoothAdapter.getBluetoothLeScanner() : null;
        if (bleScanner != null) {
            try { bleScanner.stopScan(bleScanCallback); } catch (Exception ignored) {}
        }

        // 停止经典蓝牙扫描
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // 注销广播接收器
        unregisterClassicScanReceiver();

        mainHandler.removeCallbacks(scanTimeoutRunnable);
        scanState = ScanState.IDLE;
        notifyScanStateChanged();
        Log.i(TAG, "扫描已停止");
    }

    /** 获取扫描到的设备列表 */
    public List<ScannedDevice> getScannedDevices() {
        synchronized (scannedDevicesMap) {
            return new ArrayList<>(scannedDevicesMap.values());
        }
    }

    public ScanState getScanState() {
        return scanState;
    }

    /** 主动连接到一个扫描到的设备 */
    public void connectToDevice(BluetoothDevice device) {
        if (!isBluetoothAvailable() || !isBluetoothEnabled()) {
            notifyError("蓝牙不可用或未开启");
            return;
        }

        // 停止扫描以释放资源
        stopScan();

        Log.i(TAG, "尝试连接设备: " + device.getAddress());

        // 先尝试 BLE GATT 连接
        try {
            clientGatt = device.connectGatt(context, false, clientGattCallback);
            Log.i(TAG, "正在建立 BLE GATT 连接...");
        } catch (Exception e) {
            Log.e(TAG, "BLE 连接失败，尝试 SPP", e);
            connectSpp(device);
        }
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

    public BluetoothMode getCurrentMode() {
        return currentMode;
    }

    public int getCurrentMtu() {
        return currentMtu;
    }

    // ==================== BLE 扫描回调 ====================

    private final ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            int rssi = result.getRssi();
            addScannedDevice(device, rssi, true);
        }
    };

    // ==================== 经典蓝牙扫描 ====================

    private android.content.BroadcastReceiver classicScanReceiver;

    private void registerClassicScanReceiver() {
        classicScanReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, android.content.Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    if (device != null) {
                        addScannedDevice(device, rssi, false);
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    Log.i(TAG, "经典蓝牙扫描完成");
                }
            }
        };

        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(classicScanReceiver, filter);
    }

    private void unregisterClassicScanReceiver() {
        if (classicScanReceiver != null) {
            try { context.unregisterReceiver(classicScanReceiver); } catch (Exception ignored) {}
            classicScanReceiver = null;
        }
    }

    private void addScannedDevice(BluetoothDevice device, int rssi, boolean isBle) {
        String address = device.getAddress();
        if (scannedDevicesMap.containsKey(address)) return; // 已存在，跳过

        ScannedDevice scanned = new ScannedDevice(device, rssi, isBle);
        scannedDevicesMap.put(address, scanned);
        Log.i(TAG, "发现设备: " + scanned.getName() + " [" + address + "] RSSI:" + rssi
                + " " + (isBle ? "BLE" : "Classic"));
        notifyDeviceFound(scanned);
    }

    // ==================== BLE GATT 客户端回调（主动连接） ====================

    private final BluetoothGattCallback clientGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            BluetoothDevice device = gatt.getDevice();
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "BLE GATT 连接成功: " + device.getAddress());
                // 发现服务
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "BLE GATT 连接失败/断开: " + device.getAddress());
                gatt.close();
                if (clientGatt == gatt) clientGatt = null;
                // BLE 连接失败，尝试 SPP
                if (!connectedDevices.contains(device)) {
                    connectSpp(device);
                } else {
                    connectedDevices.remove(device);
                    notifyDeviceDisconnected(device);
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                BluetoothDevice device = gatt.getDevice();
                Log.i(TAG, "BLE 服务发现成功");

                // 检查是否有目标 Service
                BluetoothGattService service = gatt.getService(BLE_SERVICE_UUID);
                if (service != null) {
                    // 找到了目标 Service，设置为 BLE 模式
                    currentMode = BluetoothMode.BLE_GATT;
                    connectedDevices.add(device);
                    notifyDeviceConnected(device);
                    notifyModeChanged(BluetoothMode.BLE_GATT);
                    notifyConnectionResult(device, true, "BLE GATT 连接成功");

                    // 请求更大 MTU
                    gatt.requestMtu(512);

                    // 订阅 notify
                    BluetoothGattCharacteristic characteristic =
                            service.getCharacteristic(BLE_CHARACTERISTIC_UUID);
                    if (characteristic != null) {
                        gatt.setCharacteristicNotification(characteristic, true);
                        BluetoothGattCharacteristic descriptor =
                                service.getCharacteristic(CCCD_UUID);
                    }
                } else {
                    // 没找到目标 Service，断开并尝试 SPP
                    Log.i(TAG, "未发现目标 BLE Service，尝试 SPP");
                    gatt.close();
                    if (clientGatt == gatt) clientGatt = null;
                    connectSpp(device);
                }
            } else {
                Log.e(TAG, "服务发现失败: " + status);
                gatt.close();
                if (clientGatt == gatt) clientGatt = null;
                connectSpp(gatt.getDevice());
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                currentMtu = mtu;
                Log.i(TAG, "MTU 变更为: " + mtu);
                notifyMtuChanged(mtu);
            }
        }
    };

    // ==================== SPP 主动连接 ====================

    private void connectSpp(BluetoothDevice device) {
        Log.i(TAG, "尝试 SPP 连接: " + device.getAddress());

        new Thread(() -> {
            try {
                // 取消正在运行的 discovery 以加速连接
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                clientSocket = socket;
                clientOutputStream = socket.getOutputStream();
                currentMode = BluetoothMode.SPP;
                connectedDevices.add(device);

                Log.i(TAG, "SPP 连接成功: " + device.getAddress());
                notifyDeviceConnected(device);
                notifyModeChanged(BluetoothMode.SPP);
                notifyConnectionResult(device, true, "经典蓝牙 SPP 连接成功");

                // 保持连接活跃，检测断开
                new Thread(() -> {
                    try {
                        while (clientSocket != null && clientSocket.isConnected()) {
                            int b = socket.getInputStream().read();
                            if (b == -1) break;
                        }
                    } catch (IOException e) {
                        // 连接断开
                    } finally {
                        connectedDevices.remove(device);
                        notifyDeviceDisconnected(device);
                    }
                }, "SppClientReadThread").start();

            } catch (IOException e) {
                Log.e(TAG, "SPP 连接失败: " + e.getMessage());
                notifyConnectionResult(device, false, "连接失败: " + e.getMessage());
            }
        }, "SppConnectThread").start();
    }

    private void disconnectClient() {
        if (clientOutputStream != null) {
            try { clientOutputStream.close(); } catch (IOException ignored) {}
            clientOutputStream = null;
        }
        if (clientSocket != null) {
            try { clientSocket.close(); } catch (IOException ignored) {}
            clientSocket = null;
        }
        if (clientGatt != null) {
            try { clientGatt.close(); } catch (Exception ignored) {}
            clientGatt = null;
        }
    }

    // ==================== BLE GATT Server（等待连接） ====================

    private boolean tryStartBle() {
        if (bluetoothAdapter == null) return false;
        advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            Log.w(TAG, "设备不支持 BLE 广播");
            return false;
        }
        setupGattServer();
        startAdvertising();
        return true;
    }

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
            Log.e(TAG, "BLE 广播失败: " + msg + "，降级到 SPP");
            mainHandler.removeCallbacks(bleTimeoutRunnable);
            stopBle();
            startSpp();
        }
    };

    private final BluetoothGattServerCallback gattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevices.add(device);
                mainHandler.removeCallbacks(bleTimeoutRunnable);
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

    // ==================== SPP Server（等待连接） ====================

    private void startSpp() {
        try {
            sppServerSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SPP_NAME, SPP_UUID);
            isSppListening = true;
            currentMode = BluetoothMode.SPP;
            Log.i(TAG, "SPP 监听已启动");
            notifyModeChanged(BluetoothMode.SPP);
            notifyAdvertisingChanged(true);
            startSppAcceptLoop();
        } catch (IOException e) {
            Log.e(TAG, "SPP 启动失败", e);
            isSppListening = false;
            notifyError("SPP 启动失败: " + e.getMessage());
        }
    }

    private void startSppAcceptLoop() {
        new Thread(() -> {
            while (isSppListening && sppServerSocket != null) {
                try {
                    Log.i(TAG, "SPP 等待客户端连接...");
                    BluetoothSocket socket = sppServerSocket.accept();
                    if (socket != null) {
                        handleSppConnection(socket);
                    }
                } catch (IOException e) {
                    if (isSppListening) Log.e(TAG, "SPP accept 失败", e);
                    break;
                }
            }
        }, "SppAcceptThread").start();
    }

    private void handleSppConnection(BluetoothSocket socket) {
        if (sppClientSocket != null) {
            try { sppClientSocket.close(); } catch (IOException ignored) {}
        }
        sppClientSocket = socket;
        try {
            sppOutputStream = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "获取 SPP 输出流失败", e);
            return;
        }
        BluetoothDevice device = socket.getRemoteDevice();
        connectedDevices.add(device);
        Log.i(TAG, "SPP 设备已连接: " + device.getAddress());
        notifyDeviceConnected(device);

        new Thread(() -> {
            try {
                while (sppClientSocket != null && sppClientSocket.isConnected()) {
                    int b = socket.getInputStream().read();
                    if (b == -1) break;
                }
            } catch (IOException e) {
                // 断开
            } finally {
                connectedDevices.remove(device);
                Log.i(TAG, "SPP 设备已断开: " + device.getAddress());
                notifyDeviceDisconnected(device);
            }
        }, "SppReadThread").start();
    }

    private void stopSpp() {
        isSppListening = false;
        if (sppOutputStream != null) {
            try { sppOutputStream.close(); } catch (IOException ignored) {}
            sppOutputStream = null;
        }
        if (sppClientSocket != null) {
            try { sppClientSocket.close(); } catch (IOException ignored) {}
            sppClientSocket = null;
        }
        if (sppServerSocket != null) {
            try { sppServerSocket.close(); } catch (IOException ignored) {}
            sppServerSocket = null;
        }
        notifyAdvertisingChanged(false);
    }

    private void sendSppData(String jsonData) {
        OutputStream os = sppOutputStream != null ? sppOutputStream : clientOutputStream;
        if (os == null) return;
        try {
            os.write((jsonData + "\n").getBytes(StandardCharsets.UTF_8));
            os.flush();
        } catch (IOException e) {
            Log.e(TAG, "SPP 发送失败", e);
            if (sppClientSocket != null) {
                connectedDevices.remove(sppClientSocket.getRemoteDevice());
            }
        }
    }

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

    private void notifyScanStateChanged() {
        if (callback != null) mainHandler.post(() -> callback.onScanStateChanged(scanState));
    }

    private void notifyDeviceFound(ScannedDevice device) {
        if (callback != null) mainHandler.post(() -> callback.onDeviceFound(device));
    }

    private void notifyConnectionResult(BluetoothDevice device, boolean success, String message) {
        if (callback != null) mainHandler.post(() -> callback.onConnectionResult(device, success, message));
    }

    private void notifyError(String message) {
        if (callback != null) mainHandler.post(() -> callback.onError(message));
    }
}
