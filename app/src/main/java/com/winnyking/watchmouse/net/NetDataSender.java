/*
 * Copyright 2018 Google LLC All Rights Reserved.
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

package com.winnyking.watchmouse.net;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.MainThread;
import com.winnyking.watchmouse.bluetooth.KeyboardReport.KeyboardDataSender;
import com.winnyking.watchmouse.bluetooth.MouseReport.MouseDataSender;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sends input over a plain TCP socket using newline-delimited JSON.
 *
 * <p>The watch connects to a "receiver" (Linux/Windows server or Android companion app) on the
 * local network. Messages are tiny and lossless over Wi-Fi, so the full API is preserved:
 *
 * <ul>
 *   <li><code>{"t":"hello","app":"WatchMouse","v":1}</code> sent once on connect.
 *   <li><code>{"t":"mouse","l":0,"r":0,"m":0,"dx":1,"dy":-2,"w":0}</code> relative mouse state.
 *   <li><code>{"t":"key","mod":0,"k":[...6 scan codes]}</code> keyboard state.
 * </ul>
 *
 * <p>The receiver answers with a <code>hello</code> and then pings every 5 seconds so the watch can
 * detect stale connections and reconnect.
 */
public final class NetDataSender implements MouseDataSender, KeyboardDataSender {

    private static final String TAG = "NetDataSender";

    /** Notified on the main thread whenever the TCP connection state changes. */
    public interface StatusListener {
        void onConnectionChanged(boolean connected);
    }

    /** Notified on the main thread when a receiver asks the watch to do something. */
    public interface CommandListener {
        void onRemoteCommand(String command);
    }

    static final class InstanceHolder {
        static final NetDataSender INSTANCE = new NetDataSender();
    }

    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_STALE_MS = 20000;
    private static final int RETRY_INITIAL_MS = 2000;
    private static final int RETRY_MAX_MS = 30000;
    private static final String HELLO = "{\"t\":\"hello\",\"app\":\"WatchMouse\",\"v\":1}";

    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final Set<StatusListener> statusListeners = new CopyOnWriteArraySet<>();
    private final Set<CommandListener> commandListeners = new CopyOnWriteArraySet<>();

    private final Object lock = new Object();

    @GuardedBy("lock") @Nullable private Socket socket;
    @GuardedBy("lock") @Nullable private OutputStream outputStream;
    @GuardedBy("lock") @Nullable private Thread thread;
    @GuardedBy("lock") private String host = "";
    @GuardedBy("lock") private int port = NetTransport.DEFAULT_PORT;
    @GuardedBy("lock") private boolean enabled;
    @GuardedBy("lock") private boolean stopRequested;

    private volatile boolean connected;

    private NetDataSender() {}

    public static NetDataSender getInstance() {
        return InstanceHolder.INSTANCE;
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * Point the transport at a different receiver.
     *
     * @param host Receiver IP address or hostname.
     * @param port Receiver TCP port.
     */
    @MainThread
    public void setTarget(String host, int port) {
        synchronized (lock) {
            this.host = host == null ? "" : host.trim();
            this.port = port;
            Log.i(TAG, "target set to " + this.host + ":" + this.port);
            if (enabled) {
                requestConnect();
            }
        }
    }

    /** Enable (start connecting with auto-reconnect) or disable the network transport. */
    @MainThread
    public void setEnabled(boolean on) {
        synchronized (lock) {
            enabled = on;
            if (on) {
                requestConnect();
            } else {
                stopRequested = true;
                closeSocket();
                mainThread.post(() -> notifyStatus(false));
            }
        }
    }

    /** Should be called on the main thread. */
    @MainThread
    public void registerStatusListener(StatusListener listener) {
        statusListeners.add(listener);
        listener.onConnectionChanged(connected);
    }

    /** Should be called on the main thread. */
    @MainThread
    public void unregisterStatusListener(StatusListener listener) {
        statusListeners.remove(listener);
    }

    /**
     * Receives commands pushed by the receiver (e.g. asking the watch to update itself).
     *
     * <p>Example message: <code>{"t":"cmd","a":"update"}</code>
     *
     * @param listener Called on the main thread.
     */
    @MainThread
    public void registerCommandListener(CommandListener listener) {
        commandListeners.add(listener);
    }

    /** @param listener Callback that should no longer receive commands. */
    @MainThread
    public void unregisterCommandListener(CommandListener listener) {
        commandListeners.remove(listener);
    }

    private void requestConnect() {
        synchronized (lock) {
            if (stopRequested || !enabled) {
                return;
            }
            if (thread != null && thread.isAlive()) {
                // The background loop is already running and picks up new host/port/state.
                return;
            }
            Thread worker = new Thread(this::run, "watchmouse-net");
            worker.setDaemon(true);
            thread = worker;
            worker.start();
        }
    }

    private void run() {
        int retryMs = RETRY_INITIAL_MS;
        while (true) {
            String host;
            int port;
            synchronized (lock) {
                if (stopRequested || !enabled) {
                    return;
                }
                host = this.host;
                port = this.port;
            }

            if (host.isEmpty()) {
                Log.d(TAG, "no host configured, waiting");
                sleep(RETRY_INITIAL_MS);
                continue;
            }

            Socket newSocket = null;
            try {
                Log.d(TAG, "connecting to " + host + ":" + port + " ...");
                newSocket = new Socket();
                newSocket.setSoTimeout(READ_STALE_MS);
                newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                OutputStream out = newSocket.getOutputStream();
                synchronized (lock) {
                    if (stopRequested || !enabled) {
                        return;
                    }
                    socket = newSocket;
                    outputStream = out;
                }
                Log.i(TAG, "connected to " + host + ":" + port);
                write(HELLO);
                retryMs = RETRY_INITIAL_MS;
                notifyStatus(true);

                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        newSocket.getInputStream(), StandardCharsets.UTF_8));
                // Any data from the receiver (hello/pong/ping) refreshes the staleness timeout. An
                // EOF or a SocketTimeoutException closes the connection and we reconnect.
                while (true) {
                    synchronized (lock) {
                        if (stopRequested || !enabled) {
                            return;
                        }
                    }
                    String line = reader.readLine();
                    if (line == null) {
                        Log.i(TAG, "receiver closed the connection");
                        break;
                    }
                    handleIncoming(line);
                }
            } catch (IOException e) {
                Log.w(TAG, "connection to " + host + ":" + port + " failed: " + e.getMessage());
            } finally {
                closeSocket();
                notifyStatus(false);
            }

            sleep(retryMs);
            if (retryMs < RETRY_MAX_MS) {
                retryMs *= 2;
            }
        }
    }

    private void handleIncoming(String line) {
        if (!line.startsWith("{\"t\":\"cmd\"")) {
            return;
        }
        String command = null;
        try {
            JSONObject message = new JSONObject(line);
            if ("cmd".equals(message.optString("t"))) {
                command = message.optString("a", "");
            }
        } catch (JSONException e) {
            Log.w(TAG, "malformed command message, ignoring: " + line);
            return;
        }
        if (command == null || command.isEmpty()) {
            return;
        }
        String action = command;
        mainThread.post(() -> notifyCommand(action));
    }

    private void notifyCommand(String command) {
        for (CommandListener listener : commandListeners) {
            listener.onRemoteCommand(command);
        }
    }

    @Override
    public void sendMouse(
            boolean left, boolean right, boolean middle, int dX, int dY, int dWheel) {
        write(
                "{\"t\":\"mouse\",\"l\":"
                        + asInt(left)
                        + ",\"r\":"
                        + asInt(right)
                        + ",\"m\":"
                        + asInt(middle)
                        + ",\"dx\":"
                        + dX
                        + ",\"dy\":"
                        + dY
                        + ",\"w\":"
                        + dWheel
                        + "}");
    }

    @Override
    public void sendKeyboard(
            int modifier, int key1, int key2, int key3, int key4, int key5, int key6) {
        write(
                "{\"t\":\"key\",\"mod\":"
                        + modifier
                        + ",\"k\":["
                        + key1
                        + ","
                        + key2
                        + ","
                        + key3
                        + ","
                        + key4
                        + ","
                        + key5
                        + ","
                        + key6
                        + "]}");
    }

    private void write(String message) {
        Socket s;
        OutputStream out;
        synchronized (lock) {
            s = socket;
            out = outputStream;
        }
        if (s == null || out == null || s.isClosed() || !s.isConnected()) {
            return;
        }
        try {
            out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            Log.w(TAG, "send failed: " + e.getMessage());
            closeSocket();
            notifyStatus(false);
        }
    }

    private void closeSocket() {
        Socket s;
        synchronized (lock) {
            s = socket;
            socket = null;
            outputStream = null;
        }
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void notifyStatus(final boolean nowConnected) {
        if (connected == nowConnected) {
            return;
        }
        connected = nowConnected;
        // The socket worker thread observes connect/disconnect events, so deliver the
        // notification on the main thread to let listeners touch the UI safely.
        mainThread.post(() -> {
            for (StatusListener listener : statusListeners) {
                listener.onConnectionChanged(nowConnected);
            }
        });
    }

    private static int asInt(boolean value) {
        return value ? 1 : 0;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}