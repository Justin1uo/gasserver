package com.example.gas_server;

import android.bluetooth.BluetoothDevice;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.gas_server.ble.BluetoothServerManager;
import com.google.android.material.button.MaterialButton;

import java.util.List;

public class DeviceFragment extends Fragment {

    private MaterialButton btnScan;
    private LinearLayout layoutDeviceList;
    private TextView tvNoDevice;
    private TextView tvScanStatus;

    private BluetoothServerManager btServerManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnScan = view.findViewById(R.id.btn_scan);
        layoutDeviceList = view.findViewById(R.id.layout_device_list);
        tvNoDevice = view.findViewById(R.id.tv_no_device);
        tvScanStatus = view.findViewById(R.id.tv_scan_status);

        btnScan.setOnClickListener(v -> toggleScan());

        initComponents();
    }

    private void initComponents() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        btServerManager = activity.getBtServerManager();
    }

    // ==================== 回调方法（由 Activity 分发调用） ====================

    void onDeviceConnected(BluetoothDevice device) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), "设备已连接", Toast.LENGTH_SHORT).show());
    }

    void onDeviceDisconnected(BluetoothDevice device) {}

    void onScanStateChanged(BluetoothServerManager.ScanState state) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            if (state == BluetoothServerManager.ScanState.SCANNING) {
                btnScan.setText("停止扫描");
                tvScanStatus.setVisibility(View.VISIBLE);
                tvScanStatus.setText("正在扫描...");
            } else {
                btnScan.setText("扫描");
                List<BluetoothServerManager.ScannedDevice> devices =
                        btServerManager.getScannedDevices();
                tvScanStatus.setText(devices.isEmpty() ? "未发现设备" :
                        "发现 " + devices.size() + " 个设备");
            }
        });
    }

    void onDeviceFound(BluetoothServerManager.ScannedDevice device) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            tvNoDevice.setVisibility(View.GONE);
            tvScanStatus.setVisibility(View.VISIBLE);
            List<BluetoothServerManager.ScannedDevice> devices =
                    btServerManager.getScannedDevices();
            tvScanStatus.setText("发现 " + devices.size() + " 个设备");
            addDeviceToList(device);
        });
    }

    void onConnectionResult(BluetoothDevice device, boolean success, String message) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), message,
                        success ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show());
    }

    void onError(String message) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() ->
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show());
    }

    // ==================== 扫描控制 ====================

    private void toggleScan() {
        if (btServerManager.getScanState() == BluetoothServerManager.ScanState.SCANNING) {
            btServerManager.stopScan();
        } else {
            if (!btServerManager.isBluetoothEnabled()) {
                Toast.makeText(requireContext(), "请先开启蓝牙", Toast.LENGTH_SHORT).show();
                return;
            }

            // 检查权限
            MainActivity activity = (MainActivity) getActivity();
            if (activity != null && !activity.hasScanPermissions()) {
                Toast.makeText(requireContext(), "请授予蓝牙和定位权限", Toast.LENGTH_LONG).show();
                return;
            }

            layoutDeviceList.removeAllViews();
            tvNoDevice.setVisibility(View.VISIBLE);
            tvNoDevice.setText("正在扫描...");
            btServerManager.startScan();
        }
    }

    private void addDeviceToList(BluetoothServerManager.ScannedDevice scannedDevice) {
        View itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_device, layoutDeviceList, false);

        TextView tvName = itemView.findViewById(R.id.tv_device_name);
        TextView tvAddress = itemView.findViewById(R.id.tv_device_address);
        TextView tvMode = itemView.findViewById(R.id.tv_device_mode);

        tvName.setText(scannedDevice.getName());
        tvAddress.setText(scannedDevice.getAddress() + "  RSSI:" + scannedDevice.rssi);
        tvMode.setText(scannedDevice.isBle ? "BLE" : "经典");
        tvMode.setBackgroundResource(scannedDevice.isBle ? R.drawable.status_green : R.drawable.status_yellow);

        itemView.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "正在连接: " + scannedDevice.getName(),
                    Toast.LENGTH_SHORT).show();
            btServerManager.connectToDevice(scannedDevice.device);
        });

        layoutDeviceList.addView(itemView);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (btServerManager != null && btServerManager.getScanState() == BluetoothServerManager.ScanState.SCANNING) {
            btServerManager.stopScan();
        }
    }
}
