package com.winnyking.watchmousecompanion;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Companion app for a phone or tablet that receives the watch input over TCP/Wi-Fi and turns it
 * into touch gestures. The watch controls this device like a touchscreen.
 */
public class MainActivity extends AppCompatActivity {

    private static final int DEFAULT_PORT = 8888;

    private final Handler mainThread = new Handler(Looper.getMainLooper());

    private static TcpInputServer activeServer;

    private TextView statusText;
    private TextView addressText;
    private TextView accessibilityText;
    private TextView updateStatusText;
    private Button toggleButton;
    private Button updateButton;
    private EditText pinInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        addressText = findViewById(R.id.addressText);
        accessibilityText = findViewById(R.id.accessibilityText);
        updateStatusText = findViewById(R.id.updateStatusText);
        toggleButton = findViewById(R.id.toggleButton);
        updateButton = findViewById(R.id.updateButton);
        pinInput = findViewById(R.id.pinInput);

        addressText.setText(formatAddressList(getLocalIPv4Addresses(), DEFAULT_PORT));
        toggleButton.setOnClickListener(this::onToggle);
        updateButton.setOnClickListener(this::onUpdateWatch);
        refreshAccessibilityState();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (activeServer != null) {
            activeServer.setStateListener(
                    state ->
                            mainThread.post(
                                    () -> {
                                        setStatus(state);
                                        updateUi();
                                    }));
        }
        refreshAccessibilityState();
        updateUi();
    }

    @Override
    protected void onPause() {
        if (activeServer != null) {
            activeServer.setStateListener(null);
        }
        super.onPause();
    }

    private void onToggle(View view) {
        if (activeServer != null) {
            stopServer();
        } else {
            startServer();
        }
    }

    private synchronized void startServer() {
        if (activeServer != null) {
            return;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        GestureTarget target = new GestureTarget(metrics.widthPixels, metrics.heightPixels);
        String pin = pinInput.getText() == null ? "" : pinInput.getText().toString().trim();
        activeServer =
                new TcpInputServer(
                        target,
                        state ->
                                mainThread.post(
                                        () -> {
                                            setStatus(state);
                                            updateUi();
                                        }),
                        pin);
        try {
            activeServer.start(DEFAULT_PORT);
            setStatus("Listening on port " + activeServer.getPort());
        } catch (IOException e) {
            setStatus("Could not start: " + e.getMessage());
            activeServer = null;
        }
        updateUi();
    }

    private synchronized void stopServer() {
        if (activeServer != null) {
            activeServer.stop();
            activeServer = null;
        }
        updateUi();
    }

    private void updateUi() {
        boolean running = activeServer != null;
        toggleButton.setText(running ? R.string.stop_button : R.string.start_button);
        updateButton.setEnabled(running);
        statusText.setText(
                running
                        ? "Listening on port " + activeServer.getPort() + " — clients: "
                                + activeServer.getClientCount()
                        : getString(R.string.status_stopped));
    }

    private void onUpdateWatch(View view) {
        if (activeServer == null) {
            updateStatusText.setText(R.string.update_no_client);
            return;
        }
        boolean sent = activeServer.sendToAll("{\"t\":\"cmd\",\"a\":\"update\"}");
        updateStatusText.setText(sent ? R.string.update_sent : R.string.update_no_client);
    }

    private void setStatus(String text) {
        statusText.setText(
                String.format(
                        Locale.getDefault(),
                        "%s — clients: %d",
                        text,
                        activeServer == null ? 0 : activeServer.getClientCount()));
    }

    private void refreshAccessibilityState() {
        if (CompanionInputService.isRunning()) {
            accessibilityText.setText(R.string.accessibility_enabled);
        } else {
            accessibilityText.setText(R.string.accessibility_disabled);
        }
    }

    private static List<String> getLocalIPv4Addresses() {
        List<String> addresses = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> inet = iface.getInetAddresses();
                while (inet.hasMoreElements()) {
                    InetAddress address = inet.nextElement();
                    if (address instanceof Inet4Address) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException ignored) {
        }
        return addresses;
    }

    private static String formatAddressList(List<String> addresses, int port) {
        if (addresses.isEmpty()) {
            return "IP unknown — connect to this device's Wi-Fi address on port " + port;
        }
        return "Enter one of these on the watch (port " + port + "):\n"
                + TextUtils.join("\n", addresses);
    }
}