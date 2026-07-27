package com.example.gas_server;

import android.Manifest;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.gas_server.ble.BluetoothServerManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 1001;

    private BluetoothServerManager btServerManager;
    private final HomeFragment homeFragment = new HomeFragment();
    private final DeviceFragment deviceFragment = new DeviceFragment();
    private Fragment activeFragment;

    // 统一回调分发，避免两个 Fragment 互相覆盖
    private final BluetoothServerManager.Callback dispatchCallback = new BluetoothServerManager.Callback() {
        @Override
        public void onModeChanged(BluetoothServerManager.BluetoothMode mode) {
            homeFragment.onModeChanged(mode);
        }

        @Override
        public void onAdvertisingChanged(boolean advertising) {
            homeFragment.onAdvertisingChanged(advertising);
        }

        @Override
        public void onDeviceConnected(BluetoothDevice device) {
            homeFragment.onDeviceConnected(device);
            deviceFragment.onDeviceConnected(device);
        }

        @Override
        public void onDeviceDisconnected(BluetoothDevice device) {
            homeFragment.onDeviceDisconnected(device);
            deviceFragment.onDeviceDisconnected(device);
        }

        @Override
        public void onMtuChanged(int mtu) {
            homeFragment.onMtuChanged(mtu);
        }

        @Override
        public void onConnectionResult(BluetoothDevice device, boolean success, String message) {
            homeFragment.onConnectionResult(device, success, message);
            deviceFragment.onConnectionResult(device, success, message);
        }

        @Override
        public void onError(String message) {
            if (activeFragment == homeFragment) {
                homeFragment.onError(message);
            } else {
                deviceFragment.onError(message);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btServerManager = new BluetoothServerManager(this);
        btServerManager.setCallback(dispatchCallback);

        initNavigation();
        requestPermissions();

        // 蓝牙服务常驻：App 启动即开启 BLE 广播
        btServerManager.start();
    }

    private void initNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);

        // 默认显示 HomeFragment
        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragment_container, deviceFragment, "device").hide(deviceFragment)
                .add(R.id.fragment_container, homeFragment, "home")
                .commit();
        activeFragment = homeFragment;

        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                switchFragment(homeFragment);
                return true;
            } else if (id == R.id.nav_device) {
                switchFragment(deviceFragment);
                return true;
            }
            return false;
        });
    }

    private void switchFragment(Fragment target) {
        if (target == activeFragment) return;
        getSupportFragmentManager().beginTransaction()
                .hide(activeFragment)
                .show(target)
                .commit();
        activeFragment = target;
    }

    /**
     * 供 Fragment 获取共享的 BluetoothServerManager
     */
    public BluetoothServerManager getBtServerManager() {
        return btServerManager;
    }

    private void requestPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
            };
        }

        boolean allGranted = true;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                Toast.makeText(this, "需要蓝牙和定位权限才能扫描设备", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (btServerManager != null) {
            btServerManager.stop();
        }
    }
}
