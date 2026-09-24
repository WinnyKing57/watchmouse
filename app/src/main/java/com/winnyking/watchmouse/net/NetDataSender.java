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
import java.util.Base64;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Sends input over an encrypted TCP socket (protocol v2) using newline-delimited JSON.
 *
 * <p>The watch connects to a "receiver" (Linux/Windows server or Android companion app) on the
 * local network. Messages are tiny and lossless over Wi-Fi, so the full API is preserved:
 *
 * <ul>
 *   <li><code>{"t":"hello","app":"WatchMouse","v":2,"salt":...}</code> is sent by the receiver and
 *       seeds the session key (HKDF + user PIN). The watch proves the PIN with an authenticated
 *       <code>auth</code> frame; every frame after that is AES-128-GCM encrypted (see {@link
 *       ProtocolCrypto}).
 *   <li><code>{"t":"mouse","l":0,"r":0,"m":0,"dx":1,"dy":-2,"w":0}</code> relative mouse state.
 *   <li><code>{"t":"key","mod":0,"k":[...6 scan codes]}</code> keyboard state.
 * </ul>
 *
 * <p>The receiver pings every 5 seconds so the watch can detect stale connections and reconnect.
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

    private final Handler mainThread = new Handler(Looper.getMainLooper());
    private final Set<StatusListener> statusListeners = new CopyOnWriteArraySet<>();
    private final Set<CommandListener> commandListeners = new CopyOnWriteArraySet<>();

    // Input events arrive from any thread (the UI thread for touch/mouse/key events, the status
    // callbacks for Bluetooth). StrictMode forbids network I/O off the dedicated net thread, so all
    // socket writes are serialized on one dedicated writer thread.
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(
                    new ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "watchmouse-write");
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private final Object lock = new Object();

    @GuardedBy("lock") @Nullable private Socket socket;
    @GuardedBy("lock") @Nullable private OutputStream outputStream;
    @GuardedBy("lock") @Nullable private Thread thread;
    @GuardedBy("lock") private String host = "";
    @GuardedBy("lock") private int port = NetTransport.DEFAULT_PORT;
    @GuardedBy("lock") private String pin = "";
    @GuardedBy("lock") @Nullable private byte[] sessionKey;
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

    /** Should be called on the main thread. */
    @MainThread
    public void setPin(String pin) {
        synchronized (lock) {
            this.pin = pin == null ? "" : pin;
        }
        closeSocket();
        requestConnect();
    }

    /** Enable (start connecting with auto-reconnect) or disable the network transport. */
    @MainThread
    public void setEnabled(boolean on) {
        synchronized (lock) {
            enabled = on;
            if (on) {
                stopRequested = false;
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
            byte[] sessionKey = null;
            try {
                Log.d(TAG, "connecting to " + host + ":" + port + " ...");
                newSocket = new Socket();
                newSocket.setSoTimeout(READ_STALE_MS);
                newSocket.setTcpNoDelay(true);
                newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                OutputStream out = newSocket.getOutputStream();
                synchronized (lock) {
                    if (stopRequested || !enabled) {
                        return;
                    }
                    socket = newSocket;
                    outputStream = out;
                }

                // Protocol v2 handshake: the receiver owns the session. It sends
                // {"t":"hello","v":2,"salt":...}; we derive the key from the PIN and reply with an
                // authenticated {"t":"auth"} frame. Everything after that is encrypted.
                BufferedReader rawReader =
                        new BufferedReader(
                                new InputStreamReader(
                                        newSocket.getInputStream(), StandardCharsets.ISO_8859_1));
                String sessionKeyPin;
                synchronized (lock) {
                    sessionKeyPin = pin;
                }
                String hello = readLineOrNull(rawReader);
                if (hello == null || !hello.startsWith("{\"t\":\"hello\"")) {
                    Log.w(TAG, "receiver did not start a v2 handshake");
                    closeSocket();
                    break;
                }
                JSONObject helloJson;
                String saltB64;
                try {
                    helloJson = new JSONObject(hello);
                    saltB64 = helloJson.optString("salt", "");
                } catch (JSONException e) {
                    Log.w(TAG, "malformed hello message");
                    closeSocket();
                    break;
                }
                if (helloJson.optInt("v") != ProtocolCrypto.PROTOCOL_VERSION
                        || saltB64.length() < 4) {
                    Log.w(TAG, "unsupported receiver version or missing salt");
                    closeSocket();
                    break;
                }
                final byte[] salt;
                try {
                    salt = Base64.getDecoder().decode(saltB64);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "invalid salt in hello message");
                    closeSocket();
                    break;
                }
                sessionKey = ProtocolCrypto.deriveSessionKey(sessionKeyPin, salt);
                synchronized (lock) {
                    this.sessionKey = sessionKey;
                }
                out.write(
                        (ProtocolCrypto.encryptFrame(sessionKey, "{\"t\":\"auth\"}") + "\n")
                                .getBytes(StandardCharsets.UTF_8));
                out.flush();

                retryMs = RETRY_INITIAL_MS;
                notifyStatus(true);

                byte[] key = sessionKey;
                // Any data from the receiver (welcome/ping) refreshes the staleness timeout. An
                // EOF or a SocketTimeoutException closes the connection and we reconnect.
                while (true) {
                    synchronized (lock) {
                        if (stopRequested || !enabled) {
                            return;
                        }
                    }
                    String sFrame = readLineOrNull(rawReader);
                    if (sFrame == null) {
                        Log.i(TAG, "receiver closed the connection");
                        break;
                    }
                    String plaintext = ProtocolCrypto.decryptFrame(key, sFrame.trim());
                    if (plaintext == null) {
                        Log.w(TAG, "auth rejected: wrong PIN or tampered traffic");
                        closeSocket();
                        break;
                    }
                    handleIncoming(plaintext);
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
        byte[] key;
        synchronized (lock) {
            s = socket;
            out = outputStream;
            key = sessionKey;
        }
        if (s == null || out == null || s.isClosed() || !s.isConnected()) {
            return;
        }
        if (key == null) {
            Log.w(TAG, "session key not ready, dropping message");
            return;
        }
        final String frame = ProtocolCrypto.encryptFrame(key, message);
        final byte[] payload = (frame + "\n").getBytes(StandardCharsets.UTF_8);
        final Socket socketSnapshot = s;
        final OutputStream outputSnapshot = out;
        writer.execute(
                () -> {
                    Socket current;
                    OutputStream currentOut;
                    synchronized (lock) {
                        current = socket;
                        currentOut = outputStream;
                    }
                    if (current != socketSnapshot || currentOut != outputSnapshot) {
                        // The connection changed while the message was queued; drop it.
                        return;
                    }
                    try {
                        outputSnapshot.write(payload);
                        outputSnapshot.flush();
                    } catch (IOException e) {
                        Log.w(TAG, "send failed: " + e.getMessage());
                        closeSocket();
                        notifyStatus(false);
                    }
                });
    }

    private void closeSocket() {
        Socket s;
        synchronized (lock) {
            s = socket;
            socket = null;
            outputStream = null;
            sessionKey = null;
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

    private static String readLineOrNull(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        return line == null ? null : line.trim();
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