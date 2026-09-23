package com.fscallingline;

import android.content.Context;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Process-local mirror of the Windows transcription session. The PC remains the
 * recognition engine; authenticated FS Calling Line transport messages update
 * this store so Android can present the same live conversation safely.
 */
public final class TranscriptStore {
    public static final class Entry {
        public final String id, sessionId, speaker, language, languageCode, text, sourceDevice, audioSource;
        public final long time;
        public final double confidence;
        public final boolean uncertain, rtl, isFinal;

        Entry(JSONObject o) {
            id = o.optString("id");
            sessionId = o.optString("sessionId");
            time = o.optLong("time", System.currentTimeMillis());
            speaker = o.optString("speaker", "Speaker");
            language = o.optString("language", "");
            languageCode = o.optString("languageCode", "und");
            text = o.optString("text", "");
            confidence = o.optDouble("confidence", 0d);
            uncertain = o.optBoolean("uncertain", false);
            rtl = o.optBoolean("rtl", false);
            isFinal = o.optBoolean("isFinal", true);
            sourceDevice = o.optString("sourceDevice", "Windows PC");
            audioSource = o.optString("audioSource", "Unknown");
        }
    }

    public static final class Snapshot {
        public final List<Entry> entries;
        public final String status, language, audio, pair, engine, callId;
        public final boolean live, paused;
        public final long revision, stateReceivedAt;

        Snapshot(List<Entry> entries, String status, String language, String audio,
                 String pair, String engine, String callId, boolean live, boolean paused, long revision, long stateReceivedAt) {
            this.entries = entries;
            this.status = status;
            this.language = language;
            this.audio = audio;
            this.pair = pair;
            this.engine = engine;
            this.callId = callId;
            this.live = live;
            this.paused = paused;
            this.revision = revision;
            this.stateReceivedAt = stateReceivedAt;
        }
    }

    private static final Object LOCK = new Object();
    private static final ArrayList<Entry> ENTRIES = new ArrayList<>();
    private static String status = "Waiting for an answered call";
    private static String language = "Detecting language…";
    private static String audio = "Waiting for PC call audio";
    private static String pair = "English + Dari / Farsi · دری / فارسی";
    private static String engine = "PC transcription";
    private static String callId = "";
    private static boolean live, paused;
    private static long revision, stateReceivedAt;

    private TranscriptStore() {}

    public static long revision() {
        synchronized (LOCK) { return revision; }
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(Collections.unmodifiableList(new ArrayList<>(ENTRIES)), status, language,
                    audio, pair, engine, callId, live, paused, revision, stateReceivedAt);
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            ENTRIES.clear();
            revision++;
        }
    }

    public static void setLocalStatus(String value) {
        synchronized (LOCK) {
            status = value == null ? "" : value;
            revision++;
        }
    }

    public static void handle(Context context, JSONObject msg) {
        String type = msg.optString("type");
        synchronized (LOCK) {
            if ("TRANSCRIPT_CLEAR".equals(type)) {
                ENTRIES.clear();
                callId = msg.optString("callId", callId);
                revision++;
                return;
            }

            if ("TRANSCRIPT_STATE".equals(type)) {
                stateReceivedAt = System.currentTimeMillis();
                status = msg.optString("status", status);
                language = msg.optString("language", language);
                audio = msg.optString("audio", audio);
                pair = msg.optString("pair", pair);
                engine = msg.optString("engine", engine);
                callId = msg.optString("callId", callId);
                live = msg.optBoolean("live", live);
                paused = msg.optBoolean("paused", paused);
                revision++;
                return;
            }

            if ("TRANSCRIPT_ENTRY".equals(type)) {
                JSONObject value = msg.optJSONObject("entry");
                if (value == null) return;
                Entry entry = new Entry(value);
                if (entry.text.trim().isEmpty()) return;
                for (Entry prior : ENTRIES) if (!entry.id.isEmpty() && entry.id.equals(prior.id)) return;
                ENTRIES.add(entry);
                while (ENTRIES.size() > 1000) ENTRIES.remove(0);
                revision++;
            }
        }
    }
}
