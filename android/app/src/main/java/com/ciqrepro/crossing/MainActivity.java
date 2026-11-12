package com.ciqrepro.crossing;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public class MainActivity extends Activity
        implements ConnectIQ.ConnectIQListener, ConnectIQ.IQApplicationEventListener {

    private static final String APP_ID = "d53f20c2-4aa5-466f-b170-f5a1615d545a";
    private static final String PAYLOAD = "0123456789".repeat(50);
    private static final long TIMEOUT_MS = 30_000;
    private static final long SLOW_MS = 5_000;
    private static final long FROZEN_MS = 10_000;
    private static final long SUMMARY_MS = 30_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IQApp app = new IQApp(APP_ID);
    // Garmin Connect may give the id upper case without dashes; incoming messages go to that exact id
    private final IQApp appUpper = new IQApp(APP_ID.toUpperCase(Locale.US).replace("-", ""));
    private ConnectIQ connectIQ;
    private IQDevice device;
    private File logFile;

    private boolean running;
    private int sequence;
    private int waiting;
    private long sentAt;
    private int delivered;
    private int failed;
    private long summaryAt;

    private Map<?, ?> watch;
    private Object lastDraws;
    private long drawsChangedAt;
    private boolean frozen;

    private TextView status;
    private TextView history;
    private Button button;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        logFile = new File(getExternalFilesDir(null), "crossing.log");

        button = new Button(this);
        button.setText("Start");
        button.setEnabled(false);
        button.setOnClickListener(v -> toggle());
        status = new TextView(this);
        history = new TextView(this);
        history.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(history);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        layout.addView(button);
        layout.addView(status);
        layout.addView(scroll);
        setContentView(layout);

        connectIQ = ConnectIQ.getInstance(this, ConnectIQ.IQConnectType.WIRELESS);
        connectIQ.initialize(this, true, this);
    }

    @Override
    protected void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        try {
            connectIQ.unregisterAllForEvents();
            connectIQ.shutdown(this);
        } catch (Exception e) {
            Log.w("Crossing", e);
        }
        super.onDestroy();
    }

    @Override
    public void onSdkReady() {
        handler.post(this::register);
    }

    private void register() {
        try {
            for (IQDevice known : connectIQ.getKnownDevices()) {
                connectIQ.registerForAppEvents(known, app, this);
                connectIQ.registerForAppEvents(known, appUpper, this);
                if (device == null || connectIQ.getDeviceStatus(known) == IQDevice.IQDeviceStatus.CONNECTED) {
                    device = known;
                }
            }
        } catch (Exception e) {
            log("device registration failed: " + e);
        }
        if (device == null) {
            log("no Garmin device paired in Garmin Connect");
            return;
        }
        log("ready: " + device.getFriendlyName());
        button.setEnabled(true);
    }

    @Override
    public void onInitializeError(ConnectIQ.IQSdkErrorStatus error) {
        handler.post(() -> log("Connect IQ initialize error: " + error));
    }

    @Override
    public void onSdkShutDown() {
        handler.post(() -> {
            running = false;
            log("Connect IQ SDK shut down");
        });
    }

    private void toggle() {
        running = !running;
        button.setText(running ? "Stop" : "Start");
        log(running ? "phone sending on" : "phone sending off");
        if (running) {
            send();
        }
    }

    private void send() {
        if (!running) {
            return;
        }
        int number = ++sequence;
        waiting = number;
        sentAt = System.currentTimeMillis();
        try {
            connectIQ.sendMessage(device, app, PAYLOAD, (d, a, result) -> handler.post(() -> onSent(number, result)));
            handler.postDelayed(() -> onSent(number, null), TIMEOUT_MS);
        } catch (Exception e) {
            log("sendMessage threw " + e);
            failed++;
            handler.postDelayed(this::send, SLOW_MS);
        }
    }

    private void onSent(int number, ConnectIQ.IQMessageStatus result) {
        if (number != waiting) {
            return;
        }
        waiting = 0;
        long elapsed = System.currentTimeMillis() - sentAt;
        if (result == ConnectIQ.IQMessageStatus.SUCCESS) {
            delivered++;
        } else {
            failed++;
        }
        if (result != ConnectIQ.IQMessageStatus.SUCCESS || elapsed > SLOW_MS) {
            log(String.format(Locale.US, "message %d: %s after %.1f s", number,
                result == null ? "NO CALLBACK" : result, elapsed / 1000.0));
        }
        showStatus();
        send();
    }

    @Override
    public void onMessageReceived(IQDevice from, IQApp to, List<Object> messages, ConnectIQ.IQMessageStatus result) {
        handler.post(() -> onWatchMessages(messages, result));
    }

    private void onWatchMessages(List<Object> messages, ConnectIQ.IQMessageStatus result) {
        if (result != ConnectIQ.IQMessageStatus.SUCCESS) {
            log("watch message: " + result);
            return;
        }
        for (Object message : messages) {
            if (message instanceof Map) {
                onWatchStatus((Map<?, ?>) message);
            }
        }
    }

    private void onWatchStatus(Map<?, ?> status) {
        long now = System.currentTimeMillis();
        watch = status;
        Object draws = status.get("draws");
        if (!Objects.equals(draws, lastDraws)) {
            if (frozen) {
                log("watch app drawing again");
            }
            lastDraws = draws;
            drawsChangedAt = now;
            frozen = false;
        } else if (!frozen && now - drawsChangedAt > FROZEN_MS) {
            frozen = true;
            log("WATCH APP FROZEN: its timer and transmit still run, but onUpdate has not been called for "
                + (now - drawsChangedAt) / 1000 + " s");
        }
        if (now - summaryAt > SUMMARY_MS) {
            summaryAt = now;
            log("summary");
        }
        showStatus();
    }

    private String counts() {
        return "phone delivered " + delivered + ", failed " + failed
            + (watch == null ? "" : "; watch received " + watch.get("received") + ", draws " + watch.get("draws")
                + ", sent " + watch.get("sent") + ", failed " + watch.get("failed"));
    }

    private void showStatus() {
        status.setText(counts());
    }

    private void log(String event) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + " " + event + " (" + counts() + ")";
        Log.i("Crossing", line);
        history.append(line + "\n");
        try (FileWriter writer = new FileWriter(logFile, true)) {
            writer.write(line + "\n");
        } catch (IOException e) {
            Log.w("Crossing", e);
        }
    }
}
