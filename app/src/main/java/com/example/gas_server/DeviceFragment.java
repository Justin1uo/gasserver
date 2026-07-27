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

import java.util.Set;

public class DeviceFragment extends Fragment {

    private LinearLayout layoutDeviceList;
    private TextView tvNoDevice;
    private TextView tvDeviceCount;

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

        layoutDeviceList = view.findViewById(R.id.layout_device_list);
        tvNoDevice = view.findViewById(R.id.tv_no_device);
        tvDeviceCount = view.findViewById(R.id.tv_device_count);

        initComponents();
    }

    private void initComponents() {
        MainActivity activity = (MainActivity) getActivity();
        if (activity == null) return;
        btServerManager = activity.getBtServerManager();
        refreshDeviceList();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次页面可见时刷新列表
        if (btServerManager != null) {
            refreshDeviceList();
        }
    }

    // ==================== 回调方法（由 Activity 分发调用） ====================

    void onDeviceConnected(BluetoothDevice device) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            String name = device.getName();
            Toast.makeText(requireContext(),
                    "设备已连接: " + (name != null ? name : device.getAddress()),
                    Toast.LENGTH_SHORT).show();
            refreshDeviceList();
        });
    }

    void onDeviceDisconnected(BluetoothDevice device) {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(() -> {
            String name = device.getName();
            Toast.makeText(requireContext(),
                    "设备已断开: " + (name != null ? name : device.getAddress()),
                    Toast.LENGTH_SHORT).show();
            refreshDeviceList();
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

    // ==================== 设备列表管理 ====================

    private void refreshDeviceList() {
        if (btServerManager == null) return;

        layoutDeviceList.removeAllViews();

        Set<BluetoothDevice> devices = btServerManager.getConnectedDevices();
        int count = devices.size();

        tvDeviceCount.setText(count + " 台设备");

        if (count == 0) {
            tvNoDevice.setVisibility(View.VISIBLE);
        } else {
            tvNoDevice.setVisibility(View.GONE);
            for (BluetoothDevice device : devices) {
                addDeviceItem(device);
            }
        }
    }

    private void addDeviceItem(BluetoothDevice device) {
        View itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_device, layoutDeviceList, false);

        TextView tvName = itemView.findViewById(R.id.tv_device_name);
        TextView tvAddress = itemView.findViewById(R.id.tv_device_address);
        TextView tvMode = itemView.findViewById(R.id.tv_device_mode);

        String name = device.getName();
        tvName.setText(name != null ? name : "未知设备");
        tvAddress.setText(device.getAddress());

        tvMode.setText("BLE");
        tvMode.setBackgroundResource(R.drawable.status_green);

        // 断开按钮
        itemView.findViewById(R.id.btn_disconnect).setOnClickListener(v -> {
            btServerManager.disconnectDevice(device);
        });

        layoutDeviceList.addView(itemView);
    }
}
