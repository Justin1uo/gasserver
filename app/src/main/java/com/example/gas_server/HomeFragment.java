package com.example.gas_server;

import android.bluetooth.BluetoothDevice;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.gas_server.ble.BluetoothServerManager;
import com.example.gas_server.data.DataSimulator;
import com.example.gas_server.data.SimulatedData;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;

public class HomeFragment extends Fragment {

    private static final long SEND_INTERVAL_MS = 200;
    private static final long UI_UPDATE_INTERVAL_MS = 100;

    private MaterialButton btnToggle;
    private MaterialButton btnReset;
    private TextView tvBtMode;
    private TextView tvAdvertisingStatus;
    private TextView tvConnectionStatus;
    private TextView tvConnectedDevices;
    private TextView tvMtu;
    private TextView tvDataCount;
    private TextView tvRuntime;
    private TextView tvJsonPreview;
    private TextView tvCh4;
    private TextView tvC2h6;
    private TextView tvEnumb;

    private BluetoothServerManager btServerManager;
    private DataSimulator dataSimulator;
    private Handler handler;

    private boolean isSimulating = false;
    private long dataCount = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        handler = new Handler(Looper.getMainLooper());

        initViews(view);
        initComponents();
    }

    private void initViews(View view) {
        btnToggle = view.findViewById(R.id.btn_toggle);
        btnReset = view.findViewById(R.id.btn_reset);
        tvBtMode = view.findViewById(R.id.tv_bt_mode);
        tvAdvertisingStatus = view.findViewById(R.id.tv_advertising_status);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        tvConnectedDevices = view.findViewById(R.id.tv_connected_devices);
        tvMtu = view.findViewById(R.id.tv_mtu);
        tvDataCount = view.findViewById(R.id.tv_data_count);
        tvRuntime = view.findViewById(R.id.tv_runtime);
        tvJsonPreview = view.findViewById(R.id.tv_json_preview);
        tvCh4 = view.findViewById(R.id.tv_ch4);
        tvC2h6 = view.findViewById(R.id.tv_c2h6);
        tvEnumb = view.findViewById(R.id.tv_enumb);

        btnToggle.setOnClickListener(v -> toggleSimulation());
        btnReset.setOnClickListener(v -> resetSimulation());
    }

    private void initComponents() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;

        btServerManager = activity.getBtServerManager();
        dataSimulator = new DataSimulator();
    }

    // ==================== 回调方法（由 Activity 分发调用） ====================

    void onModeChanged(BluetoothServerManager.BluetoothMode mode) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            switch (mode) {
                case BLE_GATT:
                    tvBtMode.setText("BLE GATT");
                    tvBtMode.setBackgroundResource(R.drawable.status_green);
                    break;
                case SPP:
                    tvBtMode.setText("经典蓝牙 SPP");
                    tvBtMode.setBackgroundResource(R.drawable.status_yellow);
                    break;
                default:
                    tvBtMode.setText("未启动");
                    tvBtMode.setBackgroundResource(R.drawable.status_gray);
                    break;
            }
        });
    }

    void onAdvertisingChanged(boolean active) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            String modeText = btServerManager.getCurrentMode() ==
                    BluetoothServerManager.BluetoothMode.SPP ? "SPP 监听中" : "BLE 广播中";
            tvAdvertisingStatus.setText(active ? modeText : "未启动");
            tvAdvertisingStatus.setBackgroundResource(
                    active ? R.drawable.status_green : R.drawable.status_gray);
        });
    }

    void onDeviceConnected(BluetoothDevice device) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            updateConnectionUI();
            String name = device.getName();
            Toast.makeText(requireContext(),
                    "设备已连接: " + (name != null ? name : device.getAddress()),
                    Toast.LENGTH_SHORT).show();
        });
    }

    void onDeviceDisconnected(BluetoothDevice device) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> updateConnectionUI());
    }

    void onMtuChanged(int mtu) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> tvMtu.setText(String.valueOf(mtu)));
    }

    void onConnectionResult(BluetoothDevice device, boolean success, String message) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show());
    }

    void onError(String message) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show());
    }

    // ==================== 模拟控制 ====================

    private void toggleSimulation() {
        if (isSimulating) {
            stopSimulation();
        } else {
            startSimulation();
        }
    }

    private void startSimulation() {
        if (!btServerManager.isBluetoothAvailable()) {
            Toast.makeText(requireContext(), "设备不支持蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        if (!btServerManager.isBluetoothEnabled()) {
            Toast.makeText(requireContext(), "请先开启蓝牙", Toast.LENGTH_LONG).show();
            return;
        }

        isSimulating = true;
        dataCount = 0;

        btServerManager.start();
        dataSimulator.start();
        startSendLoop();
        startUiUpdateLoop();

        btnToggle.setText("停止模拟");
        btnToggle.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#F44336")));
    }

    private void stopSimulation() {
        isSimulating = false;
        dataSimulator.stop();
        btServerManager.stop();
        handler.removeCallbacksAndMessages(null);

        btnToggle.setText("开始模拟");
        btnToggle.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#4CAF50")));
        updateUI();
    }

    private void resetSimulation() {
        if (isSimulating) {
            stopSimulation();
        }
        dataCount = 0;
        dataSimulator.reset();
        tvCh4.setText("0.000");
        tvC2h6.setText("0.000");
        tvEnumb.setText("100.0");
        tvJsonPreview.setText("{\"等待数据...\"}");
        tvDataCount.setText("0");
        tvRuntime.setText("00:00");
        Toast.makeText(requireContext(), "数据已重置", Toast.LENGTH_SHORT).show();
    }

    private void startSendLoop() {
        Runnable sendRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isSimulating) return;
                if (btServerManager.hasConnectedDevices()) {
                    SimulatedData data = dataSimulator.next();
                    if (data != null) {
                        btServerManager.sendData(data.toJson());
                        dataCount++;
                    }
                }
                handler.postDelayed(this, SEND_INTERVAL_MS);
            }
        };
        handler.post(sendRunnable);
    }

    private void startUiUpdateLoop() {
        Runnable uiRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isSimulating) return;
                updateUI();
                handler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
            }
        };
        handler.post(uiRunnable);
    }

    private void updateUI() {
        if (btServerManager == null || dataSimulator == null) return;

        updateConnectionUI();

        tvDataCount.setText(String.valueOf(dataCount));

        long elapsed = dataSimulator.getElapsedMs();
        long seconds = elapsed / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        tvRuntime.setText(String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds));

        if (dataSimulator.isRunning()) {
            SimulatedData data = dataSimulator.next();
            if (data != null) {
                tvJsonPreview.setText(data.toPrettyJson());
                tvCh4.setText(String.format(Locale.getDefault(), "%.3f", data.getCh4Conc()));
                tvC2h6.setText(String.format(Locale.getDefault(), "%.3f", data.getC2h6Conc()));
                tvEnumb.setText(String.format(Locale.getDefault(), "%.1f", data.getEnumb()));
            }
        }
    }

    private void updateConnectionUI() {
        if (btServerManager == null) return;
        int count = btServerManager.getConnectedDeviceCount();
        tvConnectionStatus.setText(count > 0 ? "已连接 (" + count + ")" : "未连接");
        tvConnectionStatus.setBackgroundResource(
                count > 0 ? R.drawable.status_green : R.drawable.status_yellow);
        tvConnectedDevices.setText(btServerManager.getConnectedDevicesInfo());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (isSimulating) {
            stopSimulation();
        }
    }
}
