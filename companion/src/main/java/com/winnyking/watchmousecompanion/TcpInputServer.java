package com.winnyking.watchmousecompanion;

import android.util.Log;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * TCP receiver for the WatchMouse protocol: newline-delimited JSON.
 *
 * <p>Messages handled:
 *
 * <ul>
 *   <li><code>{"t":"mouse","l":0,"r":0,"m":0,"dx":1,"dy":-2,"w":0}</code>
 *   <li><code>{"t":"key","mod":0,"k":[40,0,0,0,0,0]}</code>
 * </ul>
 *
 * <p>The server answers with <code>{"t":"hello",...}</code> and sends a ping every 5 seconds so the
 * watch can detect stale connections.
 */
public final class TcpInputServer {

    private static final String TAG = "TcpInputServer";
    private static final long PING_INTERVAL_MS = 5000;
    private static final byte[] HELLO =
            ("{\"t\":\"hello\",\"app\":\"WatchMouseCompanion\",\"v\":1}\n")
                    .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PING =
            ("{\"t\":\"ping\"}\n").getBytes(StandardCharsets.UTF_8);

    /** Notified on the server thread when the client count changes. */
    public interface StateListener {
        void onStateChanged(String state);
    }

    private final InputTarget target;
    private volatile StateListener stateListener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService pinger;

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private volatile boolean running;

    public TcpInputServer(InputTarget target, StateListener stateListener) {
        this.target = target;
        this.stateListener = stateListener;
    }

    public void setStateListener(StateListener stateListener) {
        this.stateListener = stateListener;
    }

    public synchronized void start(int port) throws IOException {
        if (running) {
            return;
        }
        serverSocket = new ServerSocket(port);
        running = true;
        acceptThread = new Thread(this::acceptLoop, "watchmouse-tcp-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        pinger = Executors.newSingleThreadScheduledExecutor();
        pinger.scheduleWithFixedDelay(
                this::pingClients, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
        notifyState("Listening on port " + getPort());
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (pinger != null) {
            pinger.shutdownNow();
            pinger = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        for (Socket client : clients) {
            closeQuietly(client);
        }
        clients.clear();
        notifyState("Stopped");
    }

    public int getPort() {
        ServerSocket server = serverSocket;
        return server == null ? 0 : server.getLocalPort();
    }

    public int getClientCount() {
        return clients.size();
    }

    /**
     * Sends a JSON command to every connected watch.
     *
     * @param message Newline-less JSON message, e.g. <code>{"t":"cmd","a":"update"}</code>.
     * @return {@code true} if the message was sent to at least one watch.
     */
    public boolean sendToAll(String message) {
        byte[] bytes = (message + "\n").getBytes(StandardCharsets.UTF_8);
        boolean sent = false;
        for (Socket client : new java.util.ArrayList<>(clients)) {
            try {
                OutputStream out = client.getOutputStream();
                out.write(bytes);
                out.flush();
                sent = true;
            } catch (IOException e) {
                closeQuietly(client);
                clients.remove(client);
                updateState();
            }
        }
        return sent;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                clients.add(client);
                Thread handler = new Thread(() -> handleClient(client), "watchmouse-tcp-client");
                handler.setDaemon(true);
                handler.start();
                updateState();
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket client) {
        Log.i(TAG, "watch connected: " + client.getRemoteSocketAddress());
        updateState();
        try (BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(
                                        client.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = client.getOutputStream()) {
            out.write(HELLO);
            out.flush();
            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    handleLine(line);
                } catch (Exception e) {
                    Log.w(TAG, "bad message, ignoring: " + line, e);
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "watch disconnected: " + e.getMessage());
        } finally {
            closeQuietly(client);
            clients.remove(client);
            updateState();
        }
    }

    private void handleLine(String line) throws JSONException {
        JSONObject json = new JSONObject(line);
        String type = json.optString("t");
        switch (type) {
            case "mouse":
                target.onMouseEvent(
                        new MouseInput(
                                json.optInt("l", 0) != 0,
                                json.optInt("r", 0) != 0,
                                json.optInt("m", 0) != 0,
                                json.optInt("dx", 0),
                                json.optInt("dy", 0),
                                json.optInt("w", 0)));
                break;
            case "key":
                int[] keys = new int[6];
                JSONArray array = json.optJSONArray("k");
                if (array != null) {
                    for (int i = 0; i < keys.length && i < array.length(); i++) {
                        keys[i] = array.optInt(i, 0);
                    }
                }
                target.onKeyEvent(json.optInt("mod", 0), keys);
                break;
            case "hello":
            case "ping":
            default:
                break;
        }
    }

    private void pingClients() {
        for (Socket client : clients) {
            try {
                OutputStream out = client.getOutputStream();
                out.write(PING);
                out.flush();
            } catch (IOException e) {
                closeQuietly(client);
                clients.remove(client);
                updateState();
            }
        }
    }

    private void updateState() {
        notifyState("Connected clients: " + clients.size());
    }

    private void notifyState(String state) {
        StateListener listener = stateListener;
        if (listener != null) {
            listener.onStateChanged(state);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}