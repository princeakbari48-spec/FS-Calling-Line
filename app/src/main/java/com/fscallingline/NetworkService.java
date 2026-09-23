package com.fscallingline;

import android.app.*;
import android.content.*;
import android.os.*;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.LinkedHashSet;

public final class NetworkService extends Service {

    public static volatile NetworkService instance;
    public static volatile String status = "Stopped";

    private static final int MAX_FRAME = 65536;

    private volatile boolean running;
    private volatile Socket socket;

    private volatile BlockingQueue<String> outbound;

    private final Semaphore pending = new Semaphore(8);
    private final Handler main = new Handler(Looper.getMainLooper());

    public static JSONObject message(String type) {
        try {
            return new JSONObject()
                    .put("version", 1)
                    .put("type", type);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {

        NotificationManager nm = getSystemService(NotificationManager.class);

        nm.createNotificationChannel(
                new NotificationChannel(
                        "network",
                        "Network connection",
                        NotificationManager.IMPORTANCE_LOW
                )
        );

        PendingIntent pi = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE
        );

        startForeground(
                2,
                new Notification.Builder(this, "network")
                        .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                        .setContentTitle("FS Calling Line")
                        .setContentText("Wi-Fi companion is running")
                        .setContentIntent(pi)
                        .build()
        );

        if (!running) {
            running = true;
            new Thread(this::connectLoop, "FS-Network").start();
        }

        return START_STICKY;
    }

    private void connectLoop() {

        while (running) {

            try {
                android.content.SharedPreferences prefs =
                        getSharedPreferences("fs", 0);

                String host = prefs.getString("networkHost", "");
                int port = prefs.getInt("networkPort", 47655);

                // QR expiry is only for first-time authorization. Once the user approves
                // the PC, networkToken becomes the persistent authentication secret used
                // for later reconnects. Keep the old QR token as a short-lived fallback
                // during the initial pairing handshake.
                String token = prefs.getString("networkToken", "");
                if (token.isEmpty()) {
                    long expires = prefs.getLong("qrExpires", 0);
                    if (expires > System.currentTimeMillis())
                        token = prefs.getString("qrToken", "");
                }

                if (host.isEmpty()) {
                    status = "Waiting for Wi-Fi pairing";
                    sleep(1500);
                    continue;
                }

                if (token.isEmpty()) {
                    status = "Saved pairing unavailable";
                    sleep(1500);
                    continue;
                }

                status = "Connecting to " + host + ":" + port;

                Socket candidate = new Socket();

                candidate.connect(
                        new InetSocketAddress(host, port),
                        10000
                );

                candidate.setTcpNoDelay(true);
                candidate.setKeepAlive(true);

                socket = candidate;

                try {
                    final BlockingQueue<String> queue =
                            new ArrayBlockingQueue<>(64);

                    outbound = queue;

                    Thread writer = new Thread(() -> {
                        try {
                            DataOutputStream target =
                                    new DataOutputStream(
                                            new BufferedOutputStream(
                                                    candidate.getOutputStream()
                                            )
                                    );

                            while (running && socket == candidate) {

                                String value =
                                        queue.poll(
                                                1,
                                                TimeUnit.SECONDS
                                        );

                                if (value != null) {
                                    byte[] bytes =
                                            value.getBytes(
                                                    StandardCharsets.UTF_8
                                            );

                                    target.writeInt(bytes.length);
                                    target.write(bytes);
                                    target.flush();
                                }
                            }

                        } catch (Exception ex) {
                            try {
                                candidate.close();
                            } catch (IOException ignored) {
                            }
                        }

                    }, "FS-Network-writer");

                    writer.start();

                    DataInputStream in =
                            new DataInputStream(
                                    new BufferedInputStream(
                                            candidate.getInputStream()
                                    )
                            );

                    // Android authenticates itself to Windows first.
                    send(
                            message("READY")
                                    .put("pairingToken", token)
                    );

                    status = "Authenticating";

                    // Windows must reply READY after validating token.
                    JSONObject ready = readMessage(in);

                    if (!"READY".equals(
                            ready.optString("type", "")
                    )) {
                        throw new IOException(
                                "PC rejected network session"
                        );
                    }

                    // Promote a successful first-time QR session to a saved pairing.
                    if (prefs.getString("networkToken", "").isEmpty()) {
                        prefs.edit()
                                .putString("networkToken", token)
                                .remove("qrToken")
                                .remove("qrExpires")
                                .apply();
                    }

                    status = "Connected to PC";

                    while (running && socket == candidate) {

                        JSONObject msg = readMessage(in);

                        pending.acquire();

                        main.post(() -> {
                            try {
                                if (socket == candidate) {
                                    handle(msg);
                                }
                            } finally {
                                pending.release();
                            }
                        });
                    }

                } catch (Exception e) {
                    status =
                            "Disconnected: "
                                    + e.getClass().getSimpleName();

                } finally {
                    disconnect();
                }

            } catch (Exception e) {

                status =
                        "Network unavailable: "
                                + (
                                e.getMessage() == null
                                        ? e.getClass().getSimpleName()
                                        : e.getMessage()
                        );

                disconnect();

                sleep(2000);
            }
        }
    }

    private JSONObject readMessage(DataInputStream in)
            throws Exception {

        int size = in.readInt();

        if (size < 2 || size > MAX_FRAME) {
            throw new IOException(
                    "Invalid network frame length"
            );
        }

        byte[] bytes = new byte[size];

        in.readFully(bytes);

        JSONObject msg =
                new JSONObject(
                        new String(
                                bytes,
                                StandardCharsets.UTF_8
                        )
                );

        if (msg.getInt("version") != 1) {
            throw new IOException(
                    "Unsupported protocol version"
            );
        }

        return msg;
    }

    private final LinkedHashSet<String> requests =
            new LinkedHashSet<>();

    private void handle(JSONObject msg) {

        try {

            String type = msg.getString("type");

            if (type.equals("PING")) {
                send(message("PONG"));
                return;
            }

            if (type.equals("DISCONNECT")) {
                disconnect();
                return;
            }

            if (type.equals("TRANSCRIPT_STATE") ||
                    type.equals("TRANSCRIPT_ENTRY") ||
                    type.equals("TRANSCRIPT_CLEAR")) {
                TranscriptStore.handle(this, msg);
                return;
            }

            if (type.equals("SYNC")) {

                if (CallService.instance != null) {
                    CallService.instance.publish();

                } else {
                    send(
                            message("SNAPSHOT")
                                    .put(
                                            "calls",
                                            new JSONArray()
                                    )
                                    .put(
                                            "callControl",
                                            RoleManagerCheck.granted(this)
                                    )
                    );
                }

                android.content.SharedPreferences prefs =
                        getSharedPreferences("fs", 0);

                send(
                        message("SETTINGS")
                                .put(
                                        "autoAnswer",
                                        prefs.getBoolean(
                                                "autoAnswer",
                                                false
                                        )
                                )
                                .put(
                                        "autoDtmf",
                                        prefs.getBoolean(
                                                "autoDtmf",
                                                true
                                        )
                                )
                                .put(
                                        "digit",
                                        prefs.getString(
                                                "digit",
                                                "1"
                                        )
                                )
                                .put(
                                        "delay",
                                        prefs.getInt(
                                                "delay",
                                                2000
                                        )
                                )
                );

                return;
            }

            if (type.equals("HISTORY")) {

                int offset =
                        msg.optInt("offset", 0);

                if (offset < 0 || offset > 1000000) {
                    throw new IllegalArgumentException(
                            "Invalid history page"
                    );
                }

                send(
                        message("HISTORY")
                                .put(
                                        "calls",
                                        new Journal(this)
                                                .page(offset)
                                )
                                .put(
                                        "offset",
                                        offset
                                )
                );

                return;
            }

            if (type.equals("CONTACTS")) {

                if (checkSelfPermission(
                        android.Manifest.permission.READ_CONTACTS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {

                    throw new SecurityException(
                            "Grant Contacts permission on Android first"
                    );
                }

                int offset =
                        msg.optInt("offset", 0);

                if (offset < 0 || offset > 100000) {
                    throw new IllegalArgumentException(
                            "Invalid contacts page"
                    );
                }

                JSONArray contacts =
                        new JSONArray();

                try (
                        android.database.Cursor c =
                                getContentResolver().query(
                                        android.provider.ContactsContract
                                                .CommonDataKinds.Phone
                                                .CONTENT_URI,
                                        new String[]{
                                                "display_name",
                                                "data1"
                                        },
                                        null,
                                        null,
                                        "display_name ASC"
                                )
                ) {

                    if (c != null) {

                        c.moveToPosition(offset - 1);

                        while (
                                contacts.length() < 20
                                        && c.moveToNext()
                        ) {

                            contacts.put(
                                    new JSONObject()
                                            .put(
                                                    "name",
                                                    c.getString(0)
                                            )
                                            .put(
                                                    "number",
                                                    c.getString(1)
                                            )
                            );
                        }
                    }
                }

                send(
                        message("CONTACTS")
                                .put(
                                        "contacts",
                                        contacts
                                )
                                .put(
                                        "offset",
                                        offset
                                )
                );

                return;
            }

            String request =
                    msg.getString("requestId");

            if (
                    request.length() > 64
                            || requests.contains(request)
            ) {
                throw new IllegalArgumentException(
                        "Invalid or duplicate command"
                );
            }

            requests.add(request);

            while (requests.size() > 128) {
                requests.remove(
                        requests.iterator().next()
                );
            }

            if (type.equals("SETTINGS")) {

                String digit =
                        msg.getString("digit");

                int delay =
                        msg.getInt("delay");

                if (
                        !digit.matches("[0-9*#]")
                                || delay < 0
                                || delay > 30000
                ) {
                    throw new IllegalArgumentException(
                            "Invalid DTMF settings"
                    );
                }

                getSharedPreferences("fs", 0)
                        .edit()
                        .putBoolean(
                                "autoAnswer",
                                msg.optBoolean(
                                        "autoAnswer",
                                        false
                                )
                        )
                        .putBoolean(
                                "autoDtmf",
                                msg.getBoolean(
                                        "autoDtmf"
                                )
                        )
                        .putString(
                                "digit",
                                digit
                        )
                        .putInt(
                                "delay",
                                delay
                        )
                        .apply();

            } else if (type.equals("CLEAR_HISTORY")) {

                new Journal(this).clear();

            } else if (
                    type.equals("DIAL")
                            && CallService.instance == null
            ) {

                RoleManagerCheck.require(this);

                String number =
                        msg.getString("number");

                if (
                        !number.matches(
                                "[+0-9*#]{1,32}"
                        )
                ) {
                    throw new IllegalArgumentException(
                            "Invalid number"
                    );
                }

                getSystemService(
                        android.telecom.TelecomManager.class
                ).placeCall(
                        android.net.Uri.fromParts(
                                "tel",
                                number,
                                null
                        ),
                        new Bundle()
                );

            } else {

                if (CallService.instance == null) {
                    throw new IllegalStateException(
                            "Call control unavailable; no current call"
                    );
                }

                CallService.instance.command(msg);
            }

            send(
                    message("ACK")
                            .put(
                                    "requestId",
                                    request
                            )
                            .put(
                                    "message",
                                    "Request submitted; awaiting phone state"
                            )
            );

        } catch (Exception e) {

            try {
                send(
                        message("ERROR")
                                .put(
                                        "message",
                                        e.getMessage() == null
                                                ? "Operation unavailable"
                                                : e.getMessage()
                                )
                );

            } catch (JSONException ignored) {
            }
        }
    }

    public void send(JSONObject msg) {

        BlockingQueue<String> queue =
                outbound;

        if (queue == null) {
            return;
        }

        String value =
                msg.toString();

        if (
                value.getBytes(StandardCharsets.UTF_8)
                        .length > MAX_FRAME
                        || !queue.offer(value)
        ) {
            disconnect();
        }
    }

    public boolean connected() {
        Socket current = socket;

        return current != null
                && current.isConnected()
                && !current.isClosed();
    }

    public synchronized void disconnect() {

        Socket current = socket;

        socket = null;
        outbound = null;

        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }

        status = "Disconnected";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDestroy() {

        running = false;

        disconnect();

        instance = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}