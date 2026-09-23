package com.fscallingline;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.CallAudioState;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.NumberFormat;
import java.util.*;

/** FS Calling Line mobile calling companion. */
public class MainActivity extends Activity {
    private LinearLayout body, root, callPanel;
    private TextView status, readiness;
    private String lastCalls = "initial";
    private String phoneTab = "Home", callsMode = "Keypad", morePage = "";
    private String dialText = "", transcriptSearch = "", recentFilter = "All";
    private int statsDays = 7;
    private long homeRangeStart, homeRangeEnd;
    private String lastPromptedConnectedCallId = "";
    private boolean transcriptionPromptShowing;
    private LinearLayout liveTranscriptList;
    private TextView liveStateLabel, liveStatusDetailLabel, livePairLabel, liveLanguageLabel, liveAudioLabel, liveCountLabel;
    private Button liveStartStopButton, livePauseButton;
    private boolean showCallKeys;
    private final HashMap<String, TextView> callClocks = new HashMap<>();
    private JSONObject pendingQr;
    private boolean scanAfterPermission;
    private long transcriptRevision = -1;
    private String pendingExportText, pendingExportName;

    private final int navy = Color.rgb(7, 13, 23);
    private final int surface = Color.rgb(17, 29, 44);
    private final int surface2 = Color.rgb(25, 42, 62);
    private final int line = Color.rgb(49, 76, 103);
    private final int gold = Color.rgb(241, 198, 93);
    private final int blue = Color.rgb(86, 205, 255);
    private final int green = Color.rgb(78, 222, 137);
    private final int red = Color.rgb(255, 91, 105);
    private final int muted = Color.rgb(154, 174, 198);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = new Runnable() {
        public void run() {
            boolean connected = controlConnected();
            if (status != null) status.setText(connected ? "●  Connected to PC" : "○  PC disconnected");
            if (readiness != null) readiness.setText(
                    "Call controls  •  " + (RoleManagerCheck.granted(MainActivity.this) ? "Ready" : "Setup needed") +
                            "\nControl link  •  " + connectionMode() +
                            "\nCall audio  •  " + currentAudioName());
            if (pendingQr != null) continuePairing();
            refreshCalls();
            maybePromptTranscription(currentCalls());
            long rev = TranscriptStore.revision();
            if (phoneTab.equals("Live") && rev != transcriptRevision) {
                transcriptRevision = rev;
                refreshLiveUi();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private final BroadcastReceiver bonds = new BroadcastReceiver() {
        public void onReceive(Context c, Intent i) { continuePairing(); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            scanAfterPermission = saved.getBoolean("scan");
            phoneTab = saved.getString("tab", "Home");
            callsMode = saved.getString("callsMode", "Keypad");
            morePage = saved.getString("morePage", "");
            recentFilter = saved.getString("recentFilter", "All");
            statsDays = saved.getInt("statsDays", 7);
            homeRangeStart = saved.getLong("homeRangeStart", 0);
            homeRangeEnd = saved.getLong("homeRangeEnd", 0);
            lastPromptedConnectedCallId = saved.getString("lastPromptedConnectedCallId", "");
            dialText = saved.getString("dial", "");
            transcriptSearch = saved.getString("transcriptSearch", "");
            try { String q = saved.getString("qr"); if (q != null) pendingQr = new JSONObject(q); } catch (Exception ignored) {}
        }
        if (homeRangeEnd <= 0) {
            homeRangeEnd = endOfDay(System.currentTimeMillis());
            homeRangeStart = shiftDays(startOfToday(), -6);
        }
        if (getIntent().getData() != null) {
            dialText = getIntent().getData().getSchemeSpecificPart();
            phoneTab = "Calls";
            callsMode = "Keypad";
        }
        render();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bonds, filter, Context.RECEIVER_EXPORTED); else registerReceiver(bonds, filter);
        android.content.SharedPreferences p = getSharedPreferences("fs", 0);
        if ("network".equals(p.getString("transport", "")) && !p.getString("networkToken", "").isEmpty() && NetworkService.instance == null)
            startForegroundService(new Intent(this, NetworkService.class));
        else if (!p.getString("trusted", "").isEmpty() && BluetoothService.instance == null && bluetoothPermission()) startCompanion();
    }

    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putBoolean("scan", scanAfterPermission);
        out.putString("tab", phoneTab);
        out.putString("callsMode", callsMode);
        out.putString("morePage", morePage);
        out.putString("recentFilter", recentFilter);
        out.putInt("statsDays", statsDays);
        out.putLong("homeRangeStart", homeRangeStart);
        out.putLong("homeRangeEnd", homeRangeEnd);
        out.putString("lastPromptedConnectedCallId", lastPromptedConnectedCallId);
        out.putString("dial", dialText);
        out.putString("transcriptSearch", transcriptSearch);
        if (pendingQr != null) out.putString("qr", pendingQr.toString());
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (strokeColor != Color.TRANSPARENT) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private GradientDrawable heroBackground() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(22, 52, 75), Color.rgb(12, 26, 43), Color.rgb(24, 34, 51)});
        d.setCornerRadius(dp(24)); d.setStroke(dp(1), Color.rgb(65, 105, 137)); return d;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }

    private TextView label(String value, int size) {
        TextView t = text(value, size, Color.WHITE); t.setPadding(0, dp(4), 0, dp(7)); body.addView(t); return t;
    }

    private void title(String value) {
        TextView t = label(value, 22); t.setTypeface(null, Typeface.BOLD); t.setLetterSpacing(.01f);
    }

    private LinearLayout card(String heading, String subtitle) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(18)); panel.setBackground(rounded(surface, 20, line));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(12), 0, 0); root.addView(panel, p);
        LinearLayout previous = body; body = panel;
        if (heading != null && !heading.isEmpty()) { TextView h = label(heading, 19); h.setTypeface(null, Typeface.BOLD); }
        if (subtitle != null && !subtitle.isEmpty()) label(subtitle, 13).setTextColor(muted);
        body = previous; return panel;
    }

    private Button makeButton(String value, int color, int textColor, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setAllCaps(false); b.setTextSize(15); b.setTextColor(textColor);
        b.setMinHeight(dp(50)); b.setPadding(dp(12), 0, dp(12), 0); b.setBackground(rounded(color, 15, color == surface2 ? line : Color.TRANSPARENT));
        b.setOnClickListener(v -> action.run()); return b;
    }

    private void button(String value, Runnable action) {
        Button b = makeButton(value, surface2, Color.WHITE, action);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52)); p.setMargins(0, dp(7), 0, 0); body.addView(b, p);
    }

    private void primary(String value, Runnable action) {
        Button b = makeButton(value, gold, navy, action); b.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(52)); p.setMargins(0, dp(7), 0, 0); body.addView(b, p);
    }

    private Button roundButton(String value, int color, Runnable action) { return makeButton(value, color, Color.WHITE, action); }

    private void error(String value) {
        new AlertDialog.Builder(this).setTitle("FS Calling Line").setMessage(value).setPositiveButton("OK", null).show();
    }

    private boolean controlConnected() {
        return (NetworkService.instance != null && NetworkService.instance.connected()) ||
                (BluetoothService.instance != null && BluetoothService.instance.connected());
    }

    private String connectionMode() {
        if (NetworkService.instance != null && NetworkService.instance.connected()) return "Wi-Fi / network";
        if (BluetoothService.instance != null && BluetoothService.instance.connected()) return "Bluetooth fallback";
        return "Disconnected";
    }

    private String currentAudioName() {
        if (CallService.instance == null || CallService.instance.getCallAudioState() == null) return "Phone managed";
        CallAudioState a = CallService.instance.getCallAudioState();
        switch (a.getRoute()) {
            case CallAudioState.ROUTE_BLUETOOTH: return "Bluetooth";
            case CallAudioState.ROUTE_SPEAKER: return "Speaker";
            case CallAudioState.ROUTE_WIRED_HEADSET: return "Wired headset";
            case CallAudioState.ROUTE_EARPIECE: return "Earpiece";
            default: return "Phone managed";
        }
    }

    private void render() {
        status = null; readiness = null; callClocks.clear(); callPanel = null;
        liveTranscriptList = null; liveStateLabel = liveStatusDetailLabel = livePairLabel = liveLanguageLabel = liveAudioLabel = liveCountLabel = null;
        liveStartStopButton = livePauseButton = null;
        JSONArray current = currentCalls();
        for (int i = 0; i < current.length(); i++) if ("RINGING".equals(current.optJSONObject(i).optString("state"))) { phoneTab = "Calls"; break; }

        getWindow().setStatusBarColor(navy); getWindow().setNavigationBarColor(navy);
        LinearLayout screen = new LinearLayout(this); screen.setOrientation(LinearLayout.VERTICAL); screen.setBackgroundColor(navy); setContentView(screen);
        screen.setOnApplyWindowInsetsListener((v, insets) -> { screen.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom()); return insets; });

        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setPadding(dp(20), dp(10), dp(18), dp(8));
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.fs_logo); logo.setContentDescription("FS Calling Line"); logo.setScaleType(ImageView.ScaleType.FIT_CENTER); heading.addView(logo, new LinearLayout.LayoutParams(dp(114), dp(42)));
        Space spacer = new Space(this); heading.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        TextView connection = text(controlConnected() ? "● CONNECTED" : "○ OFFLINE", 11, controlConnected() ? green : muted); connection.setTypeface(null, Typeface.BOLD); heading.addView(connection); screen.addView(heading);

        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); screen.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18), dp(6), dp(18), dp(22)); scroll.addView(root); body = root;

        switch (phoneTab) {
            case "Calls": renderCalls(current); break;
            case "Live": renderLive(); break;
            case "Devices": renderDevices(); break;
            case "More": renderMore(); break;
            default: renderHome(current); break;
        }
        screen.addView(bottomNavigation());
    }

    private LinearLayout bottomNavigation() {
        LinearLayout nav = new LinearLayout(this); nav.setPadding(dp(8), dp(5), dp(8), dp(7)); nav.setBackgroundColor(Color.rgb(12, 22, 34));
        String[] tabs = {"Home", "Calls", "Live", "Devices", "More"};
        String[] icons = {"⌂", "☎", "▣", "▱", "•••"};
        for (int i = 0; i < tabs.length; i++) {
            String tab = tabs[i];
            LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL); item.setGravity(Gravity.CENTER); item.setPadding(dp(2), dp(4), dp(2), dp(3));
            boolean selected = phoneTab.equals(tab);
            TextView icon = text(icons[i], i == 4 ? 14 : 20, selected ? gold : muted); icon.setGravity(Gravity.CENTER); item.addView(icon);
            TextView caption = text(tab, 11, selected ? Color.WHITE : muted); caption.setGravity(Gravity.CENTER); item.addView(caption);
            item.setBackground(selected ? rounded(Color.rgb(26, 42, 58), 14, line) : rounded(Color.TRANSPARENT, 14, Color.TRANSPARENT));
            item.setOnClickListener(v -> { phoneTab = tab; morePage = ""; render(); });
            nav.addView(item, new LinearLayout.LayoutParams(0, dp(58), 1));
        }
        return nav;
    }

    private static final class CallStats {
        int calls, incoming, outgoing, answered, missed, declined;
        long talk, longest;
    }

    private CallStats callStats(JSONArray history, long fromInclusive, long toInclusive) {
        CallStats out = new CallStats();
        for (int i = 0; i < history.length(); i++) {
            JSONObject c = history.optJSONObject(i); if (c == null) continue;
            long created = c.optLong("created"); if (created < fromInclusive || created > toInclusive) continue;
            out.calls++; if (c.optBoolean("incoming")) out.incoming++; else out.outgoing++;
            String result = c.optString("result"); long duration = c.optLong("duration");
            if ("ANSWERED".equals(result)) { out.answered++; out.talk += duration; out.longest = Math.max(out.longest, duration); }
            if ("MISSED".equals(result)) out.missed++;
            if ("DECLINED".equals(result)) out.declined++;
        }
        return out;
    }

    private void renderHome(JSONArray current) {
        title("Home");
        status = label(controlConnected() ? "●  PC connected" : "○  PC disconnected", 14);
        status.setTextColor(controlConnected() ? green : muted);
        label(RoleManagerCheck.granted(this) ? "Phone controls ready" : "Default Phone role is needed for full call control", 12).setTextColor(muted);

        if (current.length() > 0) {
            LinearLayout c = card("Current call", "Call controls and live transcription are available now."); LinearLayout previous = body; body = c;
            JSONObject first = current.optJSONObject(0); if (first != null) {
                TextView who = label(displayCaller(first), 24); who.setTypeface(null, Typeface.BOLD);
                label(first.optString("state") + "  ·  " + callTime(first) + "  ·  " + first.optString("audioDevice", currentAudioName()), 14).setTextColor(muted);
                LinearLayout callActions = new LinearLayout(this); body.addView(callActions);
                Button controls = makeButton("Call controls", surface2, Color.WHITE, () -> { phoneTab = "Calls"; render(); });
                Button transcribe = makeButton("Transcribe", Color.rgb(35, 121, 83), Color.WHITE, this::startTranscriptionFlow);
                callActions.addView(controls, new LinearLayout.LayoutParams(0, dp(52), 1));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, dp(52), 1); tp.setMargins(dp(8), 0, 0, 0); callActions.addView(transcribe, tp);
            } body = previous;
        }

        JSONArray history = new Journal(this).all();
        long today = startOfToday();
        CallStats todayStats = callStats(history, today, endOfDay(today));
        TextView todayHeading = label("Today's call history", 18); todayHeading.setTypeface(null, Typeface.BOLD);
        LinearLayout today1 = new LinearLayout(this); today1.setPadding(0, dp(7), 0, 0); root.addView(today1);
        metric(today1, "CALLS", String.valueOf(todayStats.calls)); metric(today1, "ANSWERED", String.valueOf(todayStats.answered)); metric(today1, "MISSED", String.valueOf(todayStats.missed));
        LinearLayout today2 = new LinearLayout(this); today2.setPadding(0, dp(8), 0, 0); root.addView(today2);
        metric(today2, "IN", String.valueOf(todayStats.incoming)); metric(today2, "OUT", String.valueOf(todayStats.outgoing)); metric(today2, "TALK TIME", shortDuration(todayStats.talk));

        CallStats rangeStats = callStats(history, homeRangeStart, homeRangeEnd);
        LinearLayout analytics = card("Date range analytics", formatDate(homeRangeStart) + "  –  " + formatDate(homeRangeEnd)); LinearLayout previous = body; body = analytics;
        LinearLayout dates = new LinearLayout(this); body.addView(dates);
        Button from = makeButton("From\n" + formatDate(homeRangeStart), surface2, Color.WHITE, () -> showHomeDatePicker(true));
        Button to = makeButton("To\n" + formatDate(homeRangeEnd), surface2, Color.WHITE, () -> showHomeDatePicker(false));
        dates.addView(from, new LinearLayout.LayoutParams(0, dp(62), 1));
        LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(0, dp(62), 1); top.setMargins(dp(8), 0, 0, 0); dates.addView(to, top);
        LinearLayout r1 = new LinearLayout(this); r1.setPadding(0, dp(10), 0, 0); body.addView(r1);
        metric(r1, "CALLS", String.valueOf(rangeStats.calls)); metric(r1, "IN", String.valueOf(rangeStats.incoming)); metric(r1, "OUT", String.valueOf(rangeStats.outgoing));
        LinearLayout r2 = new LinearLayout(this); r2.setPadding(0, dp(8), 0, 0); body.addView(r2);
        metric(r2, "ANSWERED", String.valueOf(rangeStats.answered)); metric(r2, "MISSED", String.valueOf(rangeStats.missed)); metric(r2, "TALK TIME", shortDuration(rangeStats.talk));
        button("Open full statistics", () -> { phoneTab = "More"; morePage = "Statistics"; render(); });
        body = previous;

        LinearLayout quick = card("Quick actions", "Keypad, captions and contacts."); previous = body; body = quick;
        LinearLayout qrow = new LinearLayout(this); body.addView(qrow);
        qrow.addView(smallAction("Keypad", () -> { phoneTab = "Calls"; callsMode = "Keypad"; render(); }), new LinearLayout.LayoutParams(0, dp(58), 1));
        qrow.addView(smallAction("Live", () -> { phoneTab = "Live"; render(); }), spacedWeight());
        qrow.addView(smallAction("Contacts", () -> { phoneTab = "More"; morePage = "Contacts"; render(); }), spacedWeight());
        body = previous;

        LinearLayout recent = card("Recent activity", history.length() == 0 ? "No calls recorded yet." : "Latest calls on this phone."); previous = body; body = recent;
        int limit = Math.min(3, history.length());
        for (int i = 0; i < limit; i++) addRecentRow(history.optJSONObject(i), false);
        if (history.length() > 3) button("View all calls", () -> { phoneTab = "Calls"; callsMode = "Recents"; render(); });
        body = previous;
    }

    private void showHomeDatePicker(boolean start) {
        long selected = start ? homeRangeStart : homeRangeEnd;
        Calendar c = Calendar.getInstance(); c.setTimeInMillis(selected);
        new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar chosen = Calendar.getInstance(); chosen.set(year, month, day, 0, 0, 0); chosen.set(Calendar.MILLISECOND, 0);
            if (start) {
                homeRangeStart = chosen.getTimeInMillis();
                if (homeRangeStart > homeRangeEnd) homeRangeEnd = endOfDay(homeRangeStart);
            } else {
                homeRangeEnd = endOfDay(chosen.getTimeInMillis());
                if (homeRangeEnd < homeRangeStart) homeRangeStart = startOfDay(homeRangeEnd);
            }
            render();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String formatDate(long time) { return android.text.format.DateFormat.format("dd MMM yyyy", time).toString(); }

    private LinearLayout.LayoutParams spacedWeight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(58), 1); p.setMargins(dp(7), 0, 0, 0); return p; }
    private Button smallAction(String name, Runnable action) { return makeButton(name, surface2, Color.WHITE, action); }

    private void metric(LinearLayout parent, String name, String value) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(12), dp(12), dp(10), dp(12)); box.setBackground(rounded(surface, 17, line));
        TextView a = text(name, 10, muted); a.setTypeface(null, Typeface.BOLD); box.addView(a);
        TextView b = text(value, 20, gold); b.setTypeface(null, Typeface.BOLD); box.addView(b);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(72), 1); p.setMargins(dp(3), 0, dp(3), 0); parent.addView(box, p);
    }

    private void renderCalls(JSONArray current) {
        title("Calls"); label("Cellular calling through this Android phone", 13).setTextColor(muted);
        if (!RoleManagerCheck.granted(this)) {
            LinearLayout setup = card("Call controls need setup", "FS Calling Line must be the default Phone app to answer, decline, route audio and control active calls."); LinearLayout previous = body; body = setup;
            primary("Set as default Phone app", this::requestPhoneRole); body = previous;
        }
        if (current.length() > 0) {
            callPanel = new LinearLayout(this); callPanel.setOrientation(LinearLayout.VERTICAL); root.addView(callPanel); renderCallCards(current); return;
        }

        LinearLayout selector = new LinearLayout(this); selector.setPadding(0, dp(14), 0, dp(8)); root.addView(selector);
        selector.addView(segment("Keypad", callsMode.equals("Keypad"), () -> { callsMode = "Keypad"; render(); }), new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(48), 1); rp.setMargins(dp(8), 0, 0, 0);
        selector.addView(segment("Recents", callsMode.equals("Recents"), () -> { callsMode = "Recents"; render(); }), rp);
        if (callsMode.equals("Recents")) renderRecents(); else renderDialer();
    }

    private Button segment(String name, boolean selected, Runnable action) {
        Button b = makeButton(name, selected ? Color.rgb(47, 67, 87) : surface, selected ? gold : muted, action);
        if (selected) b.setTypeface(null, Typeface.BOLD); return b;
    }

    private void renderDialer() {
        LinearLayout dial = card("Dial pad", "Enter a number or long-press 0 for +."); LinearLayout previous = body; body = dial;
        EditText number = new EditText(this); number.setSingleLine(); number.setTextColor(Color.WHITE); number.setHintTextColor(Color.GRAY); number.setHint("Phone number"); number.setTextSize(30); number.setGravity(Gravity.CENTER); number.setInputType(InputType.TYPE_CLASS_PHONE); number.setShowSoftInputOnFocus(false); number.setMinHeight(dp(74)); number.setBackgroundColor(Color.TRANSPARENT); number.setText(dialText); body.addView(number);
        number.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){dialText=s.toString();} public void afterTextChanged(android.text.Editable e){} });
        keypad(body, number::append);
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER); row.setPadding(0, dp(12), 0, 0); body.addView(row);
        Button call = makeButton("Call", Color.rgb(38, 157, 102), Color.WHITE, () -> placeNumber(number.getText().toString())); call.setTypeface(null, Typeface.BOLD); row.addView(call, new LinearLayout.LayoutParams(0, dp(58), 1));
        Button erase = makeButton("⌫", surface2, Color.WHITE, () -> { int len=number.length(); if(len>0) number.getText().delete(len-1,len); }); LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(dp(72), dp(58)); ep.setMargins(dp(10),0,0,0); row.addView(erase,ep); erase.setOnLongClickListener(v->{number.setText("");return true;});
        body = previous;
    }

    private void keypad(LinearLayout parent, java.util.function.Consumer<String> press) {
        String[] digits={"1","2","3","4","5","6","7","8","9","*","0","#"}, letters={"","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ","","＋",""};
        for(int r=0;r<4;r++) { LinearLayout row=new LinearLayout(this); parent.addView(row);
            for(int col=0;col<3;col++) { int i=r*3+col; String digit=digits[i]; Button key=makeButton(digit+(letters[i].isEmpty()?"":"\n"+letters[i]), surface2, Color.WHITE, ()->press.accept(digit)); key.setTextSize(21); key.setContentDescription(digit); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(66),1);p.setMargins(dp(5),dp(4),dp(5),dp(4));row.addView(key,p);if(digit.equals("0"))key.setOnLongClickListener(v->{press.accept("+");return true;}); }
        }
    }

    private void renderRecents() {
        JSONArray history = new Journal(this).all();
        LinearLayout filters = new LinearLayout(this); filters.setPadding(0, dp(4), 0, dp(8)); root.addView(filters);
        String[] names = {"All", "Incoming", "Outgoing", "Missed"};
        for (int i = 0; i < names.length; i++) {
            String f = names[i];
            Button b = segment(f, recentFilter.equals(f), () -> { recentFilter = f; render(); });
            LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, dp(44), 1); if (i > 0) fp.setMargins(dp(5), 0, 0, 0); filters.addView(b, fp);
        }

        int matched = 0; long talk = 0;
        for (int i = 0; i < history.length(); i++) {
            JSONObject c = history.optJSONObject(i); if (c == null || !recentMatches(c)) continue;
            matched++; if ("ANSWERED".equals(c.optString("result"))) talk += c.optLong("duration");
        }
        LinearLayout summary = card("Recent calls", matched + " shown  ·  " + shortDuration(talk) + " answered talk time"); LinearLayout previous = body; body = summary;
        if (matched == 0) label("No calls match this filter.", 15).setTextColor(muted);
        for (int i = 0; i < history.length(); i++) { JSONObject c = history.optJSONObject(i); if (c != null && recentMatches(c)) addRecentRow(c, true); }
        body = previous;
    }

    private boolean recentMatches(JSONObject c) {
        if ("Incoming".equals(recentFilter)) return c.optBoolean("incoming");
        if ("Outgoing".equals(recentFilter)) return !c.optBoolean("incoming");
        if ("Missed".equals(recentFilter)) return "MISSED".equals(c.optString("result"));
        return true;
    }

    private void addRecentRow(JSONObject c, boolean full) {
        if (c == null) return;
        String number=c.optString("number"), name=c.optString("name"), result=c.optString("result"), direction=c.optBoolean("incoming")?"Incoming":"Outgoing";
        String title=name.isEmpty()?(number.isEmpty()?"Private caller":number):name;
        String when=android.text.format.DateFormat.format("MMM d · h:mm a",c.optLong("created")).toString();
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(12),dp(11),dp(8),dp(11)); row.setBackground(rounded(Color.rgb(21,36,53),15,line));
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView who=text((c.optBoolean("incoming")?"↙  ":"↗  ")+title,16,Color.WHITE);who.setTypeface(null,Typeface.BOLD);info.addView(who);
        String meta=("MISSED".equals(result)?"Missed":direction)+" · "+when+(c.optLong("duration")>0?" · "+shortDuration(c.optLong("duration")):""); info.addView(text(meta,12,"MISSED".equals(result)?red:muted));
        Button call=makeButton("Call",Color.rgb(35,92,77),green,()->{dialText=number;phoneTab="Calls";callsMode="Keypad";render();}); row.addView(call,new LinearLayout.LayoutParams(dp(72),dp(44)));
        row.setOnClickListener(v -> showCallDetails(c));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,0);body.addView(row,p);
        if (!full && body.getChildCount()>8) return;
    }

    private void showCallDetails(JSONObject selected) {
        String number=selected.optString("number"), name=selected.optString("name");
        JSONArray h=new Journal(this).all(); int total=0,answered=0,missed=0,declined=0,incoming=0,outgoing=0; long talk=0,longest=0,last=0;
        for(int i=0;i<h.length();i++){JSONObject c=h.optJSONObject(i);if(c==null||!sameNumber(number,c.optString("number")))continue;total++;if(c.optBoolean("incoming"))incoming++;else outgoing++;String r=c.optString("result");if("ANSWERED".equals(r)){answered++;talk+=c.optLong("duration");longest=Math.max(longest,c.optLong("duration"));}if("MISSED".equals(r))missed++;if("DECLINED".equals(r))declined++;last=Math.max(last,c.optLong("created"));}
        String display=name.isEmpty()?(number.isEmpty()?"Private caller":number):name;
        StringBuilder m=new StringBuilder(); if(!name.isEmpty()&&!number.isEmpty())m.append(number).append("\n\n");
        m.append("Calls  ·  ").append(total).append("\nIncoming  ·  ").append(incoming).append("    Outgoing  ·  ").append(outgoing)
                .append("\nAnswered  ·  ").append(answered).append("    Missed  ·  ").append(missed).append("    Declined  ·  ").append(declined)
                .append("\n\nTalk time  ·  ").append(shortDuration(talk)).append("\nAverage answered  ·  ").append(shortDuration(answered==0?0:talk/answered))
                .append("\nLongest  ·  ").append(shortDuration(longest));
        if(last>0)m.append("\nLast activity  ·  ").append(android.text.format.DateFormat.format("MMM d, yyyy · h:mm a",last));
        AlertDialog.Builder b=new AlertDialog.Builder(this).setTitle(display).setMessage(m.toString()).setNegativeButton("Close",null);
        if(!number.isEmpty())b.setPositiveButton("Call",(d,w)->{dialText=number;phoneTab="Calls";callsMode="Keypad";render();});
        b.show();
    }

    private boolean sameNumber(String a,String b){String x=normalizeNumber(a),y=normalizeNumber(b);return !x.isEmpty()&&x.equals(y);}
    private String normalizeNumber(String n){if(n==null)return "";StringBuilder b=new StringBuilder();for(char c:n.toCharArray())if(Character.isDigit(c))b.append(c);return b.toString();}

    private void renderCallCards(JSONArray calls) {
        LinearLayout previous=body; body=callPanel;
        CallAudioState audio=CallService.instance==null?null:CallService.instance.getCallAudioState();
        for(int i=0;i<calls.length();i++) { JSONObject c=calls.optJSONObject(i); if(c==null)continue; String state=c.optString("state");
            LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(20),dp(22),dp(20),dp(22)); panel.setBackground(heroBackground()); LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,dp(12),0,dp(6));callPanel.addView(panel,cp); body=panel;
            TextView stateLabel=label(state.equals("RINGING")?"INCOMING CALL":state,12);stateLabel.setTextColor(state.equals("RINGING")?gold:green);stateLabel.setTypeface(null,Typeface.BOLD);stateLabel.setGravity(Gravity.CENTER);
            TextView caller=label(displayCaller(c),30);caller.setGravity(Gravity.CENTER);caller.setTypeface(null,Typeface.BOLD);
            if(!c.optString("name").isEmpty()&&!c.optString("number").isEmpty()){TextView sub=label(c.optString("number"),16);sub.setGravity(Gravity.CENTER);sub.setTextColor(muted);}
            TextView clock=label(state.equals("RINGING")?"Incoming call":callTime(c),19);clock.setGravity(Gravity.CENTER);clock.setTextColor(Color.WHITE);callClocks.put(c.optString("id"),clock);
            TextView route=label(c.optString("audioDevice",currentAudioName()),13);route.setGravity(Gravity.CENTER);route.setTextColor(muted);
            if(state.equals("RINGING")) {
                LinearLayout actions=new LinearLayout(this);actions.setPadding(0,dp(24),0,0);body.addView(actions);
                Button reject=makeButton("Decline",Color.rgb(156,50,60),Color.WHITE,()->callCommand(c,"DECLINE",null)); Button answer=makeButton("Answer",Color.rgb(36,145,96),Color.WHITE,()->callCommand(c,"ANSWER",null));
                LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(62),1);rp.setMargins(dp(4),0,dp(5),0);actions.addView(reject,rp);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,dp(62),1);ap.setMargins(dp(5),0,dp(4),0);actions.addView(answer,ap);
            } else {
                boolean isMuted=audio!=null&&audio.isMuted(), speaker=audio!=null&&audio.getRoute()==CallAudioState.ROUTE_SPEAKER;
                LinearLayout controls=new LinearLayout(this);controls.setPadding(0,dp(20),0,dp(10));body.addView(controls);
                Button muteButton=makeButton(isMuted?"Unmute":"Mute",surface2,Color.WHITE,()->callCommand(c,"MUTE",field("muted",!isMuted)));
                Button keys=makeButton("Keypad",surface2,Color.WHITE,()->{showCallKeys=!showCallKeys;render();});
                Button speakerButton=makeButton(speaker?"Earpiece":"Speaker",surface2,Color.WHITE,()->callCommand(c,"AUDIO_ROUTE",field("route",speaker?1:8)));
                speakerButton.setEnabled(audio!=null&&(audio.getSupportedRouteMask()&(speaker?1:8))!=0);
                for(Button b:new Button[]{muteButton,keys,speakerButton}){LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(58),1);bp.setMargins(dp(3),0,dp(3),0);controls.addView(b,bp);}
                if(showCallKeys&&state.equals("CONNECTED"))keypad(body,d->{if(!d.equals("+"))callCommand(c,"DTMF",field("digit",d));});
                Button end=makeButton("End call",Color.rgb(159,48,60),Color.WHITE,()->callCommand(c,"END",null));end.setTypeface(null,Typeface.BOLD);LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(-1,dp(58));ep.setMargins(0,dp(16),0,0);body.addView(end,ep);
                Button live=makeButton(TranscriptStore.snapshot().live?"View Live Transcription":"Start Live Transcription",Color.rgb(39,74,99),blue,()->{if(TranscriptStore.snapshot().live){phoneTab="Live";render();}else startTranscriptionFlow();});LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(52));lp.setMargins(0,dp(9),0,0);body.addView(live,lp);
            }
        }
        body=previous;
    }

    private String displayCaller(JSONObject c) { String name=c.optString("name"),number=c.optString("number"); return name.isEmpty()?(number.isEmpty()?"Private caller":number):name; }
    private String callTime(JSONObject c){if(!c.optString("state").equals("CONNECTED"))return c.optString("state");long s=c.optLong("duration");return String.format(Locale.getDefault(),"%02d:%02d",s/60,s%60);}

    private void renderLive() {
        title("Live Transcription");
        TranscriptStore.Snapshot s = TranscriptStore.snapshot(); transcriptRevision = s.revision;
        JSONObject connected = connectedCall();
        if (connected != null) {
            TextView call = label(displayCaller(connected) + "  ·  " + callTime(connected), 14);
            call.setTextColor(green); call.setTypeface(null, Typeface.BOLD);
        } else {
            label("Answer a call to start live transcription", 13).setTextColor(muted);
        }

        LinearLayout state = card("", ""); LinearLayout previous = body; body = state;
        liveStateLabel = label(s.live ? (s.paused ? "PAUSED" : "● LIVE TRANSCRIPTION") : (s.status.startsWith("STARTING") ? "STARTING TRANSCRIPTION" : "READY TO TRANSCRIBE"), 18);
        liveStateLabel.setTypeface(null, Typeface.BOLD); liveStateLabel.setTextColor(s.live && !s.paused ? green : (s.status.startsWith("STARTING") ? gold : (s.paused ? gold : muted)));
        liveStatusDetailLabel = label(s.status, 12); liveStatusDetailLabel.setTextColor(s.status.toLowerCase(Locale.ROOT).contains("could not") || s.status.toLowerCase(Locale.ROOT).contains("not responding") ? red : muted);
        livePairLabel = label(s.pair, 17); livePairLabel.setTextColor(gold); livePairLabel.setTypeface(null, Typeface.BOLD);
        liveLanguageLabel = label("Language  ·  " + s.language, 12); liveLanguageLabel.setTextColor(muted);
        liveAudioLabel = label("Audio  ·  " + s.audio, 12); liveAudioLabel.setTextColor(muted);
        LinearLayout actions = new LinearLayout(this); actions.setPadding(0, dp(10), 0, 0); body.addView(actions);
        liveStartStopButton = makeButton(s.live ? "Stop" : "Start transcription", s.live ? Color.rgb(138,48,58) : Color.rgb(35,121,83), Color.WHITE, () -> {
            TranscriptStore.Snapshot now = TranscriptStore.snapshot(); if (now.live) sendTranscriptionCommand("STOP", null); else startTranscriptionFlow();
        });
        actions.addView(liveStartStopButton, new LinearLayout.LayoutParams(0, dp(54), 2));
        livePauseButton = makeButton(s.paused ? "Resume" : "Pause", surface2, Color.WHITE, () -> {
            TranscriptStore.Snapshot now = TranscriptStore.snapshot(); sendTranscriptionCommand(now.paused ? "RESUME" : "PAUSE", null);
        });
        livePauseButton.setEnabled(s.live); LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(54), 1); pp.setMargins(dp(8),0,0,0); actions.addView(livePauseButton, pp);
        Button languages = makeButton("Languages", surface2, blue, this::startTranscriptionFlow); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1); lp.setMargins(dp(8),0,0,0); actions.addView(languages, lp);
        body = previous;

        LinearLayout transcriptCard = card("Conversation", ""); previous = body; body = transcriptCard;
        liveCountLabel = label(s.entries.isEmpty() ? "Captions will appear here while the call is transcribed." : s.entries.size() + " caption segment" + (s.entries.size()==1?"":"s"), 12);
        liveCountLabel.setTextColor(muted);
        liveTranscriptList = new LinearLayout(this); liveTranscriptList.setOrientation(LinearLayout.VERTICAL); body.addView(liveTranscriptList);
        populateTranscript(liveTranscriptList, s.entries, transcriptSearch);
        body = previous;

        LinearLayout tools = card("Transcript tools", "Search, copy or export the synchronized conversation."); previous = body; body = tools;
        EditText search = new EditText(this); search.setSingleLine(); search.setTextColor(Color.WHITE); search.setHintTextColor(muted); search.setHint("Search transcript"); search.setTextSize(15); search.setText(transcriptSearch); search.setBackground(rounded(Color.rgb(12,24,37),14,line)); search.setPadding(dp(14),0,dp(14),0); body.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence q,int a,int c,int d){}public void onTextChanged(CharSequence q,int a,int b,int c){transcriptSearch=q.toString();TranscriptStore.Snapshot now=TranscriptStore.snapshot();if(liveTranscriptList!=null)populateTranscript(liveTranscriptList,now.entries,transcriptSearch);}public void afterTextChanged(android.text.Editable e){}});
        LinearLayout row = new LinearLayout(this); row.setPadding(0,dp(10),0,0); body.addView(row);
        Button copy=makeButton("Copy",surface2,Color.WHITE,()->copyTranscript(TranscriptStore.snapshot().entries));
        Button export=makeButton("Export",surface2,Color.WHITE,()->exportTranscriptChoice(TranscriptStore.snapshot().entries));
        Button clear=makeButton("Clear",surface2,red,()->sendTranscriptionCommand("CLEAR",null));
        for(Button b:new Button[]{copy,export,clear}){LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(48),1);bp.setMargins(dp(3),0,dp(3),0);row.addView(b,bp);}
        body = previous;
    }

    private void refreshLiveUi() {
        if (!phoneTab.equals("Live") || liveTranscriptList == null) return;
        TranscriptStore.Snapshot s = TranscriptStore.snapshot();
        if (liveStateLabel != null) { liveStateLabel.setText(s.live ? (s.paused ? "PAUSED" : "● LIVE TRANSCRIPTION") : (s.status.startsWith("STARTING") ? "STARTING TRANSCRIPTION" : "READY TO TRANSCRIBE")); liveStateLabel.setTextColor(s.live && !s.paused ? green : (s.status.startsWith("STARTING") ? gold : (s.paused ? gold : muted))); }
        if (liveStatusDetailLabel != null) { liveStatusDetailLabel.setText(s.status); String lowerStatus=s.status.toLowerCase(Locale.ROOT); liveStatusDetailLabel.setTextColor(lowerStatus.contains("could not")||lowerStatus.contains("not responding")?red:muted); }
        if (livePairLabel != null) livePairLabel.setText(s.pair);
        if (liveLanguageLabel != null) liveLanguageLabel.setText("Language  ·  " + s.language);
        if (liveAudioLabel != null) liveAudioLabel.setText("Audio  ·  " + s.audio);
        if (liveCountLabel != null) liveCountLabel.setText(s.entries.isEmpty() ? "Captions will appear here while the call is transcribed." : s.entries.size()+" caption segment"+(s.entries.size()==1?"":"s"));
        if (liveStartStopButton != null) { liveStartStopButton.setText(s.live ? "Stop" : "Start transcription"); liveStartStopButton.setBackground(rounded(s.live ? Color.rgb(138,48,58) : Color.rgb(35,121,83),15,Color.TRANSPARENT)); }
        if (livePauseButton != null) { livePauseButton.setEnabled(s.live); livePauseButton.setText(s.paused ? "Resume" : "Pause"); }
        populateTranscript(liveTranscriptList, s.entries, transcriptSearch);
    }

    private void populateTranscript(LinearLayout target,List<TranscriptStore.Entry> entries,String query){
        target.removeAllViews(); String q=query==null?"":query.trim().toLowerCase(Locale.ROOT); int shown=0; int start=Math.max(0,entries.size()-220);
        for(int i=start;i<entries.size();i++){
            TranscriptStore.Entry e=entries.get(i); if(!q.isEmpty()&&!((e.text+" "+e.speaker+" "+e.language).toLowerCase(Locale.ROOT).contains(q))) continue; shown++;
            LinearLayout lineItem=new LinearLayout(this); lineItem.setOrientation(LinearLayout.VERTICAL); lineItem.setPadding(dp(2),dp(13),dp(2),dp(14));
            TextView meta=text(android.text.format.DateFormat.format("HH:mm:ss",e.time)+"   "+e.speaker+(e.language.isEmpty()?"":"   ·   "+e.language),11,e.speaker.equalsIgnoreCase("Me")?blue:gold); meta.setTypeface(null,Typeface.BOLD); lineItem.addView(meta);
            TextView content=text(e.text,20,Color.WHITE); content.setPadding(0,dp(6),0,dp(5)); content.setLineSpacing(0,1.08f); if(e.rtl){content.setTextDirection(View.TEXT_DIRECTION_RTL);content.setGravity(Gravity.RIGHT);} lineItem.addView(content,new LinearLayout.LayoutParams(-1,-2));
            View divider=new View(this); divider.setBackgroundColor(Color.rgb(40,58,76)); lineItem.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
            target.addView(lineItem,new LinearLayout.LayoutParams(-1,-2));
        }
        if(shown==0){TextView empty=text(entries.isEmpty()?"No captions yet. When a call connects, choose the language pair from the popup and tap Start transcription.":"No transcript matches your search.",15,muted); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(8),dp(34),dp(8),dp(34)); target.addView(empty,new LinearLayout.LayoutParams(-1,-2));}
    }

    private JSONObject connectedCall() {
        JSONArray calls=currentCalls(); for(int i=0;i<calls.length();i++){JSONObject c=calls.optJSONObject(i);if(c!=null&&"CONNECTED".equals(c.optString("state")))return c;} return null;
    }

    private void startTranscriptionFlow() {
        JSONObject call = connectedCall();
        if (call == null) { error("Answer a call first. Live transcription starts during a connected call."); return; }
        if (!controlConnected()) { error("Connect this phone to FS Calling Line PC first."); return; }
        showTranscriptionStartPrompt(call, false);
    }

    private void maybePromptTranscription(JSONArray calls) {
        if (transcriptionPromptShowing || !controlConnected()) return;
        TranscriptStore.Snapshot snapshot=TranscriptStore.snapshot();
        for(int i=0;i<calls.length();i++){
            JSONObject c=calls.optJSONObject(i); if(c==null||!"CONNECTED".equals(c.optString("state"))) continue;
            String id=c.optString("id"); if(id.isEmpty()||id.equals(lastPromptedConnectedCallId)) return;
            lastPromptedConnectedCallId=id;
            if(snapshot.live) return;
            showTranscriptionStartPrompt(c, true); return;
        }
    }

    private void showTranscriptionStartPrompt(JSONObject call, boolean automatic) {
        if (transcriptionPromptShowing) return; transcriptionPromptShowing=true;
        TranscriptStore.Snapshot snapshot=TranscriptStore.snapshot();
        boolean alreadyLive=snapshot.live;
        String lower=snapshot.pair.toLowerCase(Locale.ROOT); int initial=lower.contains("pashto")?1:0; final int[] selected={initial};
        String caller=displayCaller(call);
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle(alreadyLive?"Change transcription languages":"Start live transcription?")
                .setMessage(caller+" is connected. Choose the languages for this call.")
                .setSingleChoiceItems(new String[]{"English + Dari / Farsi · دری / فارسی","English + Pashto · پښتو"},initial,(d,which)->selected[0]=which)
                .setNegativeButton(automatic?"Not now":"Cancel",null)
                .setPositiveButton(alreadyLive?"Apply languages":"Start transcription",null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String code=selected[0]==1?"ps":"fa-dari";
            long stateBefore=TranscriptStore.snapshot().stateReceivedAt;
            sendTranscriptionCommand("SET_PAIR",field("code",code));
            if(!alreadyLive){
                TranscriptStore.setLocalStatus("STARTING · sending request to FS Calling Line PC");
                handler.postDelayed(() -> sendTranscriptionCommand("START",null),350);
                handler.postDelayed(() -> {
                    TranscriptStore.Snapshot check=TranscriptStore.snapshot();
                    if(!check.live && check.stateReceivedAt<=stateBefore){
                        TranscriptStore.setLocalStatus("PC transcription bridge is not responding. Update/restart FS Calling Line PC, then reconnect the phone.");
                        refreshLiveUi();
                    }
                },4200);
            }
            handler.postDelayed(() -> sendTranscriptionCommand("SYNC",null),alreadyLive?650:900);
            phoneTab="Live"; transcriptionPromptShowing=false; dialog.dismiss(); render();
        }));
        dialog.setOnDismissListener(x -> transcriptionPromptShowing=false); dialog.show();
    }

    private void sendTranscriptionCommand(String action, JSONObject fields) {
        try {
            if (!controlConnected()) throw new IllegalStateException("Connect the phone to FS Calling Line PC first.");
            JSONObject msg=NetworkService.message("TRANSCRIPTION_COMMAND").put("action",action);
            if(fields!=null){Iterator<String>keys=fields.keys();while(keys.hasNext()){String k=keys.next();msg.put(k,fields.opt(k));}}
            if(NetworkService.instance!=null&&NetworkService.instance.connected()) NetworkService.instance.send(msg); else if(BluetoothService.instance!=null&&BluetoothService.instance.connected()) BluetoothService.instance.send(msg);
        } catch(Exception e){error(e.getMessage());}
    }

    private void copyTranscript(List<TranscriptStore.Entry> entries){String text=transcriptText(entries);((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("FS Calling Line transcript",text));Toast.makeText(this,"Transcript copied",Toast.LENGTH_SHORT).show();}
    private String transcriptText(List<TranscriptStore.Entry> entries){StringBuilder b=new StringBuilder();for(TranscriptStore.Entry e:entries)b.append('[').append(android.text.format.DateFormat.format("HH:mm:ss",e.time)).append("] ").append(e.speaker).append(e.language.isEmpty()?"":" ["+e.language+"]").append('\n').append(e.text).append("\n\n");return b.toString();}
    private void exportTranscriptChoice(List<TranscriptStore.Entry> entries){new AlertDialog.Builder(this).setTitle("Export transcript").setItems(new String[]{"Text (.txt)","JSON (.json)"},(d,n)->{try{pendingExportName="fs-calling-line-transcript."+(n==0?"txt":"json");pendingExportText=n==0?transcriptText(entries):transcriptJson(entries).toString(2);Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(n==0?"text/plain":"application/json").putExtra(Intent.EXTRA_TITLE,pendingExportName);startActivityForResult(i,50);}catch(Exception e){error(e.getMessage());}}).show();}
    private JSONArray transcriptJson(List<TranscriptStore.Entry> entries)throws Exception{JSONArray out=new JSONArray();for(TranscriptStore.Entry e:entries)out.put(new JSONObject().put("id",e.id).put("sessionId",e.sessionId).put("time",e.time).put("speaker",e.speaker).put("language",e.language).put("languageCode",e.languageCode).put("text",e.text).put("confidence",e.confidence).put("isFinal",e.isFinal).put("sourceDevice",e.sourceDevice).put("audioSource",e.audioSource));return out;}

    private void renderDevices() {
        title("Devices"); label("Phone ↔ PC connection and call-audio readiness",13).setTextColor(muted);
        android.content.SharedPreferences prefs=getSharedPreferences("fs",0);
        String transport=prefs.getString("transport",""); String trusted=prefs.getString("trusted",""); String host=prefs.getString("networkHost","");
        String saved="network".equals(transport)?(host.isEmpty()?"Saved network PC":host):(trusted.isEmpty()?"No saved PC":trusted);

        LinearLayout pc=card("Your PC", controlConnected()?"FS Calling Line PC is connected":"Pair or reconnect your PC to synchronize calls and live captions.");LinearLayout previous=body;body=pc;
        status=label(controlConnected()?"●  Connected to PC":"○  PC disconnected",18);status.setTextColor(controlConnected()?green:muted);
        label("Control link  ·  "+connectionMode(),13).setTextColor(muted); label("Saved device  ·  "+saved,13).setTextColor(muted);
        LinearLayout actions=new LinearLayout(this);body.addView(actions);Button scanBtn=makeButton("Scan new PC",gold,navy,this::scan);Button reconnect=makeButton("Reconnect",surface2,Color.WHITE,this::reconnectSavedPc);actions.addView(scanBtn,new LinearLayout.LayoutParams(0,dp(52),1));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(52),1);rp.setMargins(dp(8),0,0,0);actions.addView(reconnect,rp);
        if(controlConnected()) button("Disconnect control link",this::disconnectControlLink);
        if(!trusted.isEmpty()||!prefs.getString("networkToken","").isEmpty()) button("Forget saved PC",this::forgetSavedPc);
        body=previous;

        LinearLayout ready=card("Connection readiness","Call control and call audio are separate paths.");previous=body;body=ready;readiness=label("",14);readiness.setTextColor(Color.LTGRAY);
        if(!RoleManagerCheck.granted(this))primary("Enable call controls",this::requestPhoneRole);
        button("Open diagnostics",()->{phoneTab="More";morePage="Diagnostics";render();});body=previous;

        LinearLayout audio=card("Call audio","Available routes come from Android Telecom during an active call.");previous=body;body=audio;
        CallAudioState a=CallService.instance==null?null:CallService.instance.getCallAudioState();label("Current route  ·  "+currentAudioName(),16).setTextColor(gold);
        if(a==null)label("Start or answer a call to see route controls.",13).setTextColor(muted);else{int mask=a.getSupportedRouteMask();StringBuilder routes=new StringBuilder();if((mask&CallAudioState.ROUTE_EARPIECE)!=0)routes.append("Earpiece  ");if((mask&CallAudioState.ROUTE_SPEAKER)!=0)routes.append("Speaker  ");if((mask&CallAudioState.ROUTE_BLUETOOTH)!=0)routes.append("Bluetooth  ");if((mask&CallAudioState.ROUTE_WIRED_HEADSET)!=0)routes.append("Wired headset");label("Available  ·  "+routes,13).setTextColor(muted);label("Muted  ·  "+(a.isMuted()?"Yes":"No"),13).setTextColor(muted);}
        body=previous;
    }

    private void disconnectControlLink(){stopService(new Intent(this,BluetoothService.class));stopService(new Intent(this,NetworkService.class));handler.postDelayed(this::render,250);}
    private void forgetSavedPc(){new AlertDialog.Builder(this).setTitle("Forget saved PC?").setMessage("You will need to scan a new FS Calling Line pairing code before reconnecting.").setNegativeButton("Cancel",null).setPositiveButton("Forget",(d,w)->{pendingQr=null;disconnectControlLink();getSharedPreferences("fs",0).edit().remove("trusted").remove("qrToken").remove("qrExpires").remove("networkToken").remove("networkHost").remove("networkPort").remove("transport").apply();render();}).show();}

    private void renderMore() {
        if(morePage.isEmpty()) {
            title("More"); label("Contacts, statistics, settings and app information",13).setTextColor(muted);
            moreLink("Contacts","Search your phone contacts and call directly.","Contacts");
            moreLink("Statistics","Call totals, talk time and the last 7 days.","Statistics");
            moreLink("Settings","Calling, DTMF, permissions and local data.","Settings");
            moreLink("Diagnostics","Connection, permissions, audio and live-transcription status.","Diagnostics");
            moreLink("About","FS Calling Line build and architecture details.","About");
            return;
        }
        Button back=makeButton("‹  Back to More",surface2,Color.WHITE,()->{morePage="";render();});root.addView(back,new LinearLayout.LayoutParams(-1,dp(48)));
        switch(morePage){case "Contacts":renderContacts();break;case "Statistics":renderStatistics();break;case "Settings":renderSettings();break;case "Diagnostics":renderDiagnostics();break;default:renderAbout();break;}
    }

    private void moreLink(String heading,String subtitle,String page){LinearLayout panel=card(heading,subtitle);LinearLayout previous=body;body=panel;button("Open "+heading,()->{morePage=page;render();});body=previous;}

    private void renderContacts() {
        title("Contacts");
        if(checkSelfPermission(Manifest.permission.READ_CONTACTS)!=PackageManager.PERMISSION_GRANTED){label("Grant Contacts permission to search the phone address book.",14).setTextColor(muted);primary("Grant Contacts permission",()->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},6));return;}
        LinearLayout panel=card("Phone contacts","Contacts remain on the phone; the PC only receives them when you explicitly sync there.");LinearLayout previous=body;body=panel;
        EditText search=new EditText(this);search.setSingleLine();search.setHint("Search name or number");search.setHintTextColor(muted);search.setTextColor(Color.WHITE);search.setBackground(rounded(Color.rgb(12,24,37),14,line));search.setPadding(dp(14),0,dp(14),0);body.addView(search,new LinearLayout.LayoutParams(-1,dp(50)));
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);populateContacts(list,"");search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){populateContacts(list,s.toString());}public void afterTextChanged(android.text.Editable e){}});body=previous;
    }

    private void populateContacts(LinearLayout target,String query){target.removeAllViews();String selection=null;String[] args=null;if(query!=null&&!query.trim().isEmpty()){selection=ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" LIKE ? OR "+ContactsContract.CommonDataKinds.Phone.NUMBER+" LIKE ?";String q="%"+query.trim()+"%";args=new String[]{q,q};}int count=0;try(Cursor c=getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,ContactsContract.CommonDataKinds.Phone.NUMBER},selection,args,ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME+" ASC")){while(c!=null&&c.moveToNext()&&count<100){String name=c.getString(0),number=c.getString(1);Button b=makeButton((name==null?"Unknown":name)+"\n"+(number==null?"":number),Color.rgb(21,36,53),Color.WHITE,()->{dialText=number==null?"":number;phoneTab="Calls";callsMode="Keypad";morePage="";render();});b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(62));p.setMargins(0,dp(6),0,0);target.addView(b,p);count++;}}catch(Exception e){target.addView(text("Contacts unavailable: "+e.getMessage(),13,red));}if(count==0)target.addView(text("No matching contacts",14,muted));}

    private void renderStatistics() {
        title("Statistics"); JSONArray all=new Journal(this).all();
        LinearLayout range=new LinearLayout(this);range.setPadding(0,dp(12),0,dp(6));root.addView(range);int[] days={1,7,30,0};String[] labels={"Today","7 days","30 days","All"};
        for(int i=0;i<days.length;i++){int d=days[i];Button b=segment(labels[i],statsDays==d,()->{statsDays=d;render();});LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);if(i>0)p.setMargins(dp(5),0,0,0);range.addView(b,p);}
        long cutoff=statsDays==0?0:(statsDays==1?startOfToday():startOfToday()-(statsDays-1L)*86400000L);
        int incoming=0,outgoing=0,answered=0,missed=0,declined=0,totalCalls=0;long total=0,longest=0;
        for(int i=0;i<all.length();i++){JSONObject c=all.optJSONObject(i);if(c==null||c.optLong("created")<cutoff)continue;totalCalls++;if(c.optBoolean("incoming"))incoming++;else outgoing++;String r=c.optString("result");long dur=c.optLong("duration");if("ANSWERED".equals(r)){answered++;total+=dur;longest=Math.max(longest,dur);}if("MISSED".equals(r))missed++;if("DECLINED".equals(r))declined++;}
        LinearLayout m1=new LinearLayout(this);m1.setPadding(0,dp(6),0,0);root.addView(m1);metric(m1,"CALLS",String.valueOf(totalCalls));metric(m1,"IN",String.valueOf(incoming));metric(m1,"OUT",String.valueOf(outgoing));
        LinearLayout m2=new LinearLayout(this);m2.setPadding(0,dp(8),0,0);root.addView(m2);metric(m2,"ANSWERED",String.valueOf(answered));metric(m2,"MISSED",String.valueOf(missed));metric(m2,"DECLINED",String.valueOf(declined));
        LinearLayout time=card("Talk time",statsDays==0?"All locally recorded answered calls.":"Selected period only.");LinearLayout previous=body;body=time;label("Total  ·  "+shortDuration(total),18).setTextColor(gold);label("Average answered  ·  "+shortDuration(answered==0?0:total/answered),15);label("Longest  ·  "+shortDuration(longest),15);body=previous;
        LinearLayout activity=card("Call activity · last 7 days","Daily call count, regardless of the selected summary range.");previous=body;body=activity;long today=startOfToday();int max=1;int[]counts=new int[7];for(int i=0;i<all.length();i++){JSONObject c=all.optJSONObject(i);if(c==null)continue;long diff=(today-startOfDay(c.optLong("created")))/86400000L;if(diff>=0&&diff<7){counts[6-(int)diff]++;max=Math.max(max,counts[6-(int)diff]);}}for(int i=0;i<7;i++){long day=today-(6L-i)*86400000L;label(android.text.format.DateFormat.format("EEE dd MMM",day)+"   "+counts[i],12).setTextColor(muted);ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(max);bar.setProgress(counts[i]);bar.setProgressTintList(android.content.res.ColorStateList.valueOf(gold));body.addView(bar,new LinearLayout.LayoutParams(-1,dp(10)));}body=previous;
    }

    private void renderSettings() {
        title("Settings"); android.content.SharedPreferences p=getSharedPreferences("fs",0);
        LinearLayout calls=card("Calling","Automatic call behavior on this phone.");LinearLayout previous=body;body=calls;
        Switch autoAnswer=switchRow("Automatically answer incoming calls while PC is connected",p.getBoolean("autoAnswer",false));Switch autoDtmf=switchRow("Enable automatic DTMF",p.getBoolean("autoDtmf",true));
        label("Automatic DTMF digit",13).setTextColor(muted);EditText digit=input(p.getString("digit","1"),1,InputType.TYPE_CLASS_PHONE);label("Delay in milliseconds (0–30000)",13).setTextColor(muted);EditText delay=input(String.valueOf(p.getInt("delay",2000)),5,InputType.TYPE_CLASS_NUMBER);
        primary("Save call settings",()->{try{String d=digit.getText().toString();int ms=Integer.parseInt(delay.getText().toString());if(!d.matches("[0-9*#]")||ms<0||ms>30000)throw new IllegalArgumentException("Enter one DTMF digit and a delay from 0 to 30000 ms.");p.edit().putBoolean("autoAnswer",autoAnswer.isChecked()).putBoolean("autoDtmf",autoDtmf.isChecked()).putString("digit",d).putInt("delay",ms).apply();Toast.makeText(this,"Call settings saved",Toast.LENGTH_SHORT).show();}catch(Exception e){error(e.getMessage());}});body=previous;

        LinearLayout perms=card("Permissions","Required Android permissions are requested only for the feature that needs them.");previous=body;body=perms;
        label("Default Phone role  ·  "+(RoleManagerCheck.granted(this)?"Ready":"Needed"),14).setTextColor(RoleManagerCheck.granted(this)?green:gold);if(!RoleManagerCheck.granted(this))button("Enable call controls",this::requestPhoneRole);
        label("Contacts  ·  "+(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED?"Allowed":"Not allowed"),14).setTextColor(muted);button("Contacts permission",()->requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},6));
        if(Build.VERSION.SDK_INT>=33)button("Notifications permission",()->requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},7));body=previous;

        LinearLayout data=card("Local data","Call history is stored locally on this phone. Live captions are mirrored from the PC in memory.");previous=body;body=data;button("Clear phone call history",()->new AlertDialog.Builder(this).setTitle("Clear local history?").setMessage("This removes FS Calling Line companion history on this phone.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(d,w)->{new Journal(this).clear();render();}).show());button("Clear live caption view",()->{TranscriptStore.clear();render();});body=previous;
    }

    private Switch switchRow(String text,boolean checked){Switch s=new Switch(this);s.setText(text);s.setTextColor(Color.WHITE);s.setTextSize(14);s.setChecked(checked);s.setPadding(0,dp(8),0,dp(8));body.addView(s);return s;}
    private EditText input(String text,int max,int type){EditText e=new EditText(this);e.setText(text);e.setTextColor(Color.WHITE);e.setInputType(type);e.setSingleLine();e.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(max)});e.setBackground(rounded(Color.rgb(12,24,37),12,line));e.setPadding(dp(12),0,dp(12),0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(48));p.setMargins(0,dp(4),0,dp(7));body.addView(e,p);return e;}

    private void renderDiagnostics() {
        title("Diagnostics"); android.content.SharedPreferences p=getSharedPreferences("fs",0);
        LinearLayout state=card("System status","Use this page when PC connection, call controls, audio or captions are not behaving as expected.");LinearLayout previous=body;body=state;
        label("Control link  ·  "+connectionMode(),15).setTextColor(controlConnected()?green:gold);
        label("Network service  ·  "+(NetworkService.instance!=null?(NetworkService.instance.connected()?"Connected":"Running / offline"):"Stopped"),14).setTextColor(muted);
        label("Bluetooth service  ·  "+(BluetoothService.instance!=null?(BluetoothService.instance.connected()?"Connected":"Running / offline"):"Stopped"),14).setTextColor(muted);
        label("Default Phone role  ·  "+(RoleManagerCheck.granted(this)?"Ready":"Not granted"),14).setTextColor(RoleManagerCheck.granted(this)?green:gold);
        label("Active calls  ·  "+currentCalls().length(),14).setTextColor(muted);
        label("Call audio  ·  "+currentAudioName(),14).setTextColor(muted);
        label("Contacts permission  ·  "+(checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED?"Allowed":"Not allowed"),14).setTextColor(muted);
        if(Build.VERSION.SDK_INT>=31)label("Nearby devices  ·  "+(bluetoothPermission()?"Allowed":"Not allowed"),14).setTextColor(muted);
        if(Build.VERSION.SDK_INT>=33)label("Notifications  ·  "+(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED?"Allowed":"Not allowed"),14).setTextColor(muted);
        TranscriptStore.Snapshot ts=TranscriptStore.snapshot();label("Live transcription  ·  "+(ts.live?(ts.paused?"Paused":"Live"):"Stopped")+" · "+ts.engine,14).setTextColor(ts.live?green:muted);
        body=previous;
        LinearLayout tools=card("Recovery tools","These actions do not delete call history or transcripts unless explicitly stated.");previous=body;body=tools;button("Reconnect saved PC",this::reconnectSavedPc);button("Sync live transcription",()->sendTranscriptionCommand("SYNC",null));button("Open Bluetooth settings",()->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));button("Open app settings",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName()))));if(!RoleManagerCheck.granted(this))primary("Enable call controls",this::requestPhoneRole);body=previous;
    }

    private void renderAbout() {
        title("About"); LinearLayout panel=card("FS Calling Line","Android cellular calling companion for FS Calling Line PC.");LinearLayout previous=body;body=panel;
        label("Version 0.7.0-dev",16).setTextColor(gold);label("Android Telecom call control",14);label("Wi-Fi/network control with Bluetooth fallback",14);label("Bluetooth/HFP call-audio routing where Android and Windows expose it",14);label("PC-powered live transcription synchronized to Android",14);label("Home date-range analytics, filtered call history, per-number analytics, contacts, statistics and DTMF controls",14);label("Connection and audio diagnostics with recovery tools",14);body=previous;
    }

    private void requestPhoneRole(){try{android.app.role.RoleManager r=getSystemService(android.app.role.RoleManager.class);if(r.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER))startActivityForResult(r.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER),2);else error("Default phone role is unavailable on this device.");}catch(Exception e){error(e.getMessage());}}

    private boolean bluetoothPermission(){return Build.VERSION.SDK_INT<31||checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;}
    private void scan(){try{scanAfterPermission=false;new com.google.zxing.integration.android.IntentIntegrator(this).setDesiredBarcodeFormats(com.google.zxing.integration.android.IntentIntegrator.QR_CODE).setPrompt("Scan the code shown in FS Calling Line on your PC").setBeepEnabled(false).setOrientationLocked(false).initiateScan();}catch(Exception e){error(e.getMessage());}}

    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==1&&scanAfterPermission){scanAfterPermission=false;if(bluetoothPermission())scan();else error("Nearby devices permission is needed to connect your PC.");}if(request==6&&checkSelfPermission(Manifest.permission.READ_CONTACTS)==PackageManager.PERMISSION_GRANTED&&phoneTab.equals("More")&&morePage.equals("Contacts"))render();}

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==50){if(result==RESULT_OK&&data!=null&&data.getData()!=null&&pendingExportText!=null){try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new java.io.IOException("Cannot open export file");out.write(pendingExportText.getBytes(java.nio.charset.StandardCharsets.UTF_8));Toast.makeText(this,"Transcript exported",Toast.LENGTH_SHORT).show();}catch(Exception e){error(e.getMessage());}}pendingExportText=null;pendingExportName=null;return;}
        if(request==4){boolean resume=scanAfterPermission;scanAfterPermission=false;if(result==RESULT_OK&&resume)scan();return;}
        com.google.zxing.integration.android.IntentResult scanned=com.google.zxing.integration.android.IntentIntegrator.parseActivityResult(request,result,data);if(scanned==null||scanned.getContents()==null)return;
        try{JSONObject q=new JSONObject(scanned.getContents());if("network".equals(q.optString("transport")))PairingCode.validateNetwork(q.optString("app"),q.optInt("v"),q.optString("host"),q.optInt("port"),q.optString("token"),q.optLong("expires"),System.currentTimeMillis());else PairingCode.validate(q.optString("app"),q.optInt("v"),q.optString("address"),q.optString("token"),q.optLong("expires"),System.currentTimeMillis());String name=q.optString("name","Your PC");if(name.length()>80)throw new IllegalArgumentException("Invalid PC name");boolean network="network".equals(q.optString("transport"));new AlertDialog.Builder(this).setTitle("Connect to "+name+"?").setMessage(network?"This PC will be able to view call history, control calls and synchronize live transcription over your Wi-Fi/network.":"This PC will be able to view call history, control calls and synchronize live transcription. Confirm the matching Bluetooth pairing code on both devices.").setNegativeButton("Cancel",null).setPositiveButton("Connect",(d,w)->{try{if(network){getSharedPreferences("fs",0).edit().putString("networkHost",q.getString("host")).putInt("networkPort",q.getInt("port")).putString("qrToken",q.getString("token")).putLong("qrExpires",q.getLong("expires")).putString("networkToken",q.getString("token")).putString("transport","network").apply();pendingQr=null;if(BluetoothService.instance!=null)BluetoothService.instance.disconnect();startForegroundService(new Intent(this,NetworkService.class));}else{pendingQr=q;BluetoothDevice pc=getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(q.getString("address"));if(pc.getBondState()==BluetoothDevice.BOND_NONE&&!pc.createBond())throw new IllegalStateException("Pairing could not start. Open Bluetooth settings on the PC and try again.");continuePairing();}}catch(Exception e){pendingQr=null;error(e.getMessage());}}).show();}catch(Exception e){error("Cannot use this QR code. "+e.getMessage());}
    }

    private void continuePairing(){if(pendingQr==null)return;try{if(System.currentTimeMillis()>pendingQr.getLong("expires")){pendingQr=null;error("Code expired. Choose Add a device on your PC and scan the new code.");return;}BluetoothDevice pc=getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(pendingQr.getString("address"));if(pc.getBondState()!=BluetoothDevice.BOND_BONDED)return;if(BluetoothService.instance!=null)BluetoothService.instance.disconnect();getSharedPreferences("fs",0).edit().putString("trusted",pc.getAddress()).putString("qrToken",pendingQr.getString("token")).putLong("qrExpires",pendingQr.getLong("expires")).putString("transport","bluetooth").apply();pendingQr=null;startCompanion();}catch(SecurityException e){pendingQr=null;error("Nearby devices permission is required.");}catch(Exception e){pendingQr=null;error(e.getMessage());}}

    private void reconnectSavedPc(){android.content.SharedPreferences p=getSharedPreferences("fs",0);if("network".equals(p.getString("transport",""))&&!p.getString("networkToken","").isEmpty()){startForegroundService(new Intent(this,NetworkService.class));return;}if(!p.getString("trusted","").isEmpty()){startCompanion();return;}error("No saved PC pairing. Scan the QR code from your PC once to save this device.");}
    private void startCompanion(){try{if(!bluetoothPermission()){if(Build.VERSION.SDK_INT>=31){scanAfterPermission=true;requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},1);}else error("Nearby devices permission is required.");return;}BluetoothAdapter a=getSystemService(BluetoothManager.class).getAdapter();if(a==null||!a.isEnabled()){startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));return;}startForegroundService(new Intent(this,BluetoothService.class));if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},5);}catch(Exception e){error(e.getMessage());}}

    private void options(){String[] choices={"Bluetooth settings","Approve an already paired PC","Share contacts permission","Disconnect control link","Forget approved PC","Clear companion call history"};new AlertDialog.Builder(this).setTitle("Connection options").setItems(choices,(d,n)->{switch(n){case 0:startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));break;case 1:approve();break;case 2:requestPermissions(new String[]{Manifest.permission.READ_CONTACTS},6);break;case 3:stopService(new Intent(this,BluetoothService.class));stopService(new Intent(this,NetworkService.class));break;case 4:pendingQr=null;stopService(new Intent(this,BluetoothService.class));stopService(new Intent(this,NetworkService.class));getSharedPreferences("fs",0).edit().remove("trusted").remove("qrToken").remove("qrExpires").remove("networkToken").remove("networkHost").remove("networkPort").remove("transport").apply();render();break;case 5:new AlertDialog.Builder(this).setTitle("Clear local history?").setMessage("This removes companion history on this phone.").setNegativeButton("Cancel",null).setPositiveButton("Clear",(x,y)->{new Journal(this).clear();render();}).show();break;}}).show();}
    private void approve(){try{BluetoothAdapter a=getSystemService(BluetoothManager.class).getAdapter();ArrayList<BluetoothDevice> devices=new ArrayList<>(a.getBondedDevices());String[] names=devices.stream().map(d->d.getName()+"\n"+d.getAddress()).toArray(String[]::new);if(devices.isEmpty()){error("No paired devices. Scan the QR code from your PC.");return;}new AlertDialog.Builder(this).setTitle("Approve call access for a PC").setItems(names,(d,n)->new AlertDialog.Builder(this).setTitle("Allow call access?").setMessage(names[n]).setNegativeButton("Cancel",null).setPositiveButton("Approve",(x,y)->{if(BluetoothService.instance!=null)BluetoothService.instance.disconnect();getSharedPreferences("fs",0).edit().putString("trusted",devices.get(n).getAddress()).remove("qrToken").remove("qrExpires").putString("transport","bluetooth").apply();startCompanion();}).show()).show();}catch(SecurityException e){error("Grant Nearby devices permission first.");}catch(Exception e){error(e.getMessage());}}

    private JSONArray currentCalls(){return CallService.instance==null?new JSONArray():CallService.instance.snapshot();}
    private void placeNumber(String input){try{RoleManagerCheck.require(this);if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.CALL_PHONE},3);return;}String number=input.replaceAll("[ ()-]","");if(!number.matches("[+0-9*#]{1,32}"))throw new IllegalArgumentException("Enter a valid phone number.");getSystemService(android.telecom.TelecomManager.class).placeCall(Uri.fromParts("tel",number,null),new Bundle());}catch(Exception e){error(e.getMessage());}}
    private void callCommand(JSONObject call,String action,JSONObject fields){try{if(CallService.instance==null)throw new IllegalStateException("Call no longer available");JSONObject command=fields==null?new JSONObject():fields;command.put("type",action).put("callId",call.getString("id"));CallService.instance.command(command);}catch(Exception e){error(e.getMessage());}}
    private JSONObject field(String key,Object value){JSONObject obj=new JSONObject();try{obj.put(key,value);}catch(Exception ignored){}return obj;}

    private void refreshCalls(){JSONArray calls=currentCalls();StringBuilder signature=new StringBuilder();for(int i=0;i<calls.length();i++){JSONObject c=calls.optJSONObject(i);signature.append(c.optString("id")).append(c.optString("state")).append(c.optString("audioDevice"));TextView clock=callClocks.get(c.optString("id"));if(clock!=null)clock.setText(c.optString("state").equals("RINGING")?"Incoming call":callTime(c));}if(CallService.instance!=null&&CallService.instance.getCallAudioState()!=null)signature.append(CallService.instance.getCallAudioState().isMuted()).append(CallService.instance.getCallAudioState().getRoute());String key=signature.toString();if(!key.equals(lastCalls)){lastCalls=key;render();}}

    private long startOfToday(){Calendar c=Calendar.getInstance();c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private long endOfDay(long time){Calendar c=Calendar.getInstance();c.setTimeInMillis(time);c.set(Calendar.HOUR_OF_DAY,23);c.set(Calendar.MINUTE,59);c.set(Calendar.SECOND,59);c.set(Calendar.MILLISECOND,999);return c.getTimeInMillis();}
    private long shiftDays(long time,int days){Calendar c=Calendar.getInstance();c.setTimeInMillis(time);c.add(Calendar.DAY_OF_YEAR,days);return c.getTimeInMillis();}
    private long startOfDay(long time){Calendar c=Calendar.getInstance();c.setTimeInMillis(time);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private String shortDuration(long seconds){if(seconds<60)return seconds+"s";if(seconds<3600)return (seconds/60)+"m "+(seconds%60)+"s";return (seconds/3600)+"h "+((seconds%3600)/60)+"m";}

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getData()!=null){dialText=intent.getData().getSchemeSpecificPart();phoneTab="Calls";callsMode="Keypad";}render();}
    @Override public void onResume(){super.onResume();handler.removeCallbacks(update);handler.post(update);}
    @Override public void onPause(){handler.removeCallbacks(update);super.onPause();}
    @Override public void onDestroy(){try{unregisterReceiver(bonds);}catch(Exception ignored){}super.onDestroy();}
}
