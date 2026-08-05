package com.codexwatch.pet;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final long REFRESH_INTERVAL_MS = 300_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private CodexWatchView watchView;
    private UsageClient usageClient;
    private AppUpdateManager updateManager;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshUsage();
            scheduleNextRefresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        watchView = new CodexWatchView(this, UserSettings.load(this));
        watchView.setTapHandler(new Runnable() {
            @Override
            public void run() {
                refreshUsage();
                scheduleNextRefresh();
            }
        });
        usageClient = new UsageClient(getString(R.string.codex_usage_urls), getString(R.string.codex_watch_token));
        updateManager = new AppUpdateManager(
                this,
                getString(R.string.codex_update_metadata_url),
                getString(R.string.codex_watch_token),
                new AppUpdateManager.Listener() {
                    @Override
                    public void onStatus(String label, String detail, boolean error) {
                        watchView.setUpdateStatus(label, detail, error);
                    }
                }
        );
        watchView.setUpdateTapHandler(new Runnable() {
            @Override
            public void run() {
                updateManager.checkForUpdate();
            }
        });
        setContentView(watchView);

        refreshUsage();
        scheduleNextRefresh();
        if (updateManager != null) {
            updateManager.onResume();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUsage();
        scheduleNextRefresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        if (updateManager != null) {
            updateManager.close();
        }
        super.onDestroy();
    }

    private void scheduleNextRefresh() {
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS);
    }

    private void refreshUsage() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                UsageData next = usageClient.fetch();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        watchView.setUsage(next);
                    }
                });
            }
        });
    }

    private static final class UsageClient {
        private static final String TAG = "CodexWatch";
        private static final String USB_ENDPOINT = "http://127.0.0.1:8765/usage";
        private final List<String> endpoints;
        private final String token;
        private UsageData lastGood;

        UsageClient(String endpointConfig, String token) {
            this.endpoints = parseEndpoints(endpointConfig);
            this.token = token == null ? "" : token.trim();
        }

        UsageData fetch() {
            UsageData latestOffline = null;
            for (String endpoint : endpoints) {
                UsageData next = fetchFrom(endpoint);
                if (next.online) {
                    remember(next);
                    return next;
                }
                latestOffline = next;
            }
            UsageData fallback = latestOffline == null ? offline("OFFLINE") : latestOffline;
            remember(fallback);
            return fallback;
        }

        private static List<String> parseEndpoints(String config) {
            List<String> parsed = new ArrayList<>();
            String source = config == null ? "" : config;
            String[] parts = source.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !parsed.contains(trimmed)) {
                    parsed.add(trimmed);
                }
            }
            if (!parsed.contains(USB_ENDPOINT)) {
                parsed.add(USB_ENDPOINT);
            }
            return parsed;
        }

        private UsageData fetchFrom(String urlText) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(urlText);
                connection = (HttpURLConnection) url.openConnection();
                connection.setUseCaches(false);
                boolean usbFallback = "127.0.0.1".equals(url.getHost());
                connection.setConnectTimeout(usbFallback ? 1_000 : 12_000);
                connection.setReadTimeout(usbFallback ? 1_500 : 20_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("Pragma", "no-cache");
                if (!token.isEmpty()) {
                    connection.setRequestProperty("X-Codex-Watch-Token", token);
                }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String body = readFully(stream);
                if (code < 200 || code >= 300) {
                    Log.w(TAG, "Usage HTTP " + code + " from " + url.getHost());
                    return offline("SERVER " + code);
                }
                UsageData data = UsageData.fromJson(new JSONObject(body));
                Log.i(TAG, "Usage synced from " + url.getHost());
                return data;
            } catch (Exception error) {
                Log.w(TAG, "Usage request failed: " + error.getClass().getSimpleName());
                return offline("OFFLINE");
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private void remember(UsageData data) {
            if (data.online && data.hasRealData) {
                lastGood = data.copy();
            }
        }

        private UsageData offline(String label) {
            UsageData fallback = lastGood == null ? UsageData.empty() : lastGood.copy();
            fallback.online = false;
            fallback.resetLabel = label;
            fallback.petState = "failed";
            return fallback;
        }

        private static String readFully(InputStream stream) throws Exception {
            if (stream == null) {
                return "";
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(stream), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
            return builder.toString();
        }
    }

    private static final class UsageData {
        int sessionLeft = 89;
        int sessionUsed = 11;
        int weeklyLeft = 98;
        String today = "403K";
        String sevenDays = "2.5M";
        String model = "--";
        String resetLabel = "RESET 1h52m";
        String resetCardLabel = "--";
        String resetCardDetail = "NOT SET";
        boolean resetCardUrgent = false;
        final List<String> resetCardDetails = new ArrayList<>();
        final List<Boolean> resetCardUrgencies = new ArrayList<>();
        String petState = "idle";
        boolean online = true;
        boolean stale = false;
        boolean hasRealData = false;

        static UsageData demo() {
            return new UsageData();
        }

        static UsageData empty() {
            UsageData data = new UsageData();
            data.sessionLeft = 0;
            data.sessionUsed = 0;
            data.weeklyLeft = 0;
            data.today = "--";
            data.sevenDays = "--";
            data.model = "--";
            data.resetLabel = "OFFLINE";
            data.resetCardLabel = "--";
            data.resetCardDetail = "OFFLINE";
            data.petState = "failed";
            data.online = false;
            data.hasRealData = false;
            return data;
        }

        UsageData copy() {
            UsageData data = new UsageData();
            data.sessionLeft = sessionLeft;
            data.sessionUsed = sessionUsed;
            data.weeklyLeft = weeklyLeft;
            data.today = today;
            data.sevenDays = sevenDays;
            data.model = model;
            data.resetLabel = resetLabel;
            data.resetCardLabel = resetCardLabel;
            data.resetCardDetail = resetCardDetail;
            data.resetCardUrgent = resetCardUrgent;
            data.resetCardDetails.addAll(resetCardDetails);
            data.resetCardUrgencies.addAll(resetCardUrgencies);
            data.petState = petState;
            data.online = online;
            data.stale = stale;
            data.hasRealData = hasRealData;
            return data;
        }

        static UsageData fromJson(JSONObject root) {
            UsageData data = new UsageData();
            JSONObject session = root.optJSONObject("session");
            JSONObject weekly = root.optJSONObject("weekly");
            JSONObject tokens = root.optJSONObject("tokens");
            JSONObject pet = root.optJSONObject("pet");
            JSONObject sync = root.optJSONObject("sync");
            JSONObject resetCard = root.optJSONObject("reset_card");
            JSONObject resetCards = root.optJSONObject("reset_cards");
            boolean syncFresh = sync == null || sync.optBoolean("fresh", true);
            String syncAge = sync == null ? "unknown" : sync.optString("source_age_label", "unknown");
            data.hasRealData = root.optBoolean("available", true);

            if (session != null) {
                data.sessionLeft = clamp(session.optInt("remaining_percent", data.sessionLeft));
                data.sessionUsed = clamp(session.optInt("used_percent", 100 - data.sessionLeft));
                data.stale = session.optBoolean("stale", false) || !syncFresh;
                data.resetLabel = data.stale
                        ? "STALE " + compactSyncAge(syncAge)
                        : "RESET " + compactReset(session.optString("resets_in", ""));
            }
            if (weekly != null) {
                data.weeklyLeft = clamp(weekly.optInt("remaining_percent", data.weeklyLeft));
            }
            if (tokens != null) {
                data.today = tokens.optString("today_label", data.today);
                data.sevenDays = tokens.optString("last_7_days_label", data.sevenDays);
            }
            data.model = root.optString("model", data.model);
            if (resetCards != null && resetCards.optBoolean("available", false)) {
                int usableCount = Math.max(0, resetCards.optInt("usable_count", 0));
                data.resetCardLabel = cardCountLabel(usableCount);
                data.resetCardUrgent = resetCards.optBoolean("urgent", false);
                JSONArray cards = resetCards.optJSONArray("cards");
                if (cards != null) {
                    for (int i = 0; i < cards.length(); i++) {
                        JSONObject card = cards.optJSONObject(i);
                        if (card == null || !card.optBoolean("available", false)) {
                            continue;
                        }
                        int count = Math.max(1, card.optInt("count", 1));
                        String date = card.optString("expires_date_label", "--");
                        String time = card.optString("expires_time_label", "--");
                        boolean expired = card.optBoolean("expired", false);
                        data.resetCardDetails.add(expired
                                ? count + "x " + date + " EXPIRED"
                                : count + "x " + date + " " + time);
                        data.resetCardUrgencies.add(card.optBoolean("urgent", false));
                    }
                }
                data.resetCardDetail = data.resetCardDetails.isEmpty()
                        ? cardCountLabel(usableCount)
                        : data.resetCardDetails.get(0);
            } else if (resetCard != null && resetCard.optBoolean("available", false)) {
                int count = Math.max(1, resetCard.optInt("count", 1));
                String date = resetCard.optString("expires_date_label", resetCard.optString("expires_label", "--"));
                String time = resetCard.optString("expires_time_label", "--");
                boolean expired = resetCard.optBoolean("expired", false);
                data.resetCardLabel = cardCountLabel(expired ? 0 : count);
                data.resetCardDetail = expired ? count + "x " + date + " EXPIRED" : count + "x " + date + " " + time;
                data.resetCardUrgent = resetCard.optBoolean("urgent", false);
                data.resetCardDetails.add(data.resetCardDetail);
                data.resetCardUrgencies.add(data.resetCardUrgent);
            }
            if (pet != null) {
                data.petState = pet.optString("state", data.petState);
            }
            data.online = root.optBoolean("available", true);
            return data;
        }

        int resetCardGroupCount() {
            return resetCardDetails.size();
        }

        String resetCardDetailAt(int index) {
            if (resetCardDetails.isEmpty()) {
                return resetCardDetail;
            }
            int safeIndex = Math.max(0, Math.min(resetCardDetails.size() - 1, index));
            return resetCardDetails.get(safeIndex);
        }

        boolean resetCardUrgentAt(int index) {
            if (resetCardUrgencies.isEmpty()) {
                return resetCardUrgent;
            }
            int safeIndex = Math.max(0, Math.min(resetCardUrgencies.size() - 1, index));
            return resetCardUrgencies.get(safeIndex);
        }

        private static String cardCountLabel(int count) {
            return count + (count == 1 ? " CARD" : " CARDS");
        }

        private static int clamp(int value) {
            return Math.max(0, Math.min(100, value));
        }

        private static String compactReset(String value) {
            if (value == null || value.trim().isEmpty() || "unknown".equalsIgnoreCase(value.trim())) {
                return "--";
            }
            String trimmed = value.trim();
            if (trimmed.contains("Codex")) {
                return "due";
            }
            String normalized = trimmed
                    .replace("\u5c0f\u65f6", "h")
                    .replace("\u5206\u949f", "m")
                    .replace("\u5929", "d")
                    .replace(" ", "");
            normalized = normalized.replaceAll("[^0-9a-zA-Z]+", "");
            return normalized.isEmpty() ? "--" : normalized;
        }

        private static String compactSyncAge(String value) {
            if (value == null || value.trim().isEmpty() || "unknown".equalsIgnoreCase(value.trim())) {
                return "--";
            }
            return value.trim();
        }
    }

    private static final class UserSettings {
        private static final String PREFS = "codex_watch_settings";
        private static final String KEY_PET = "pet";

        int petIndex;

        static UserSettings load(Context context) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            UserSettings settings = new UserSettings();
            settings.petIndex = prefs.contains(KEY_PET) ? Math.max(0, prefs.getInt(KEY_PET, 0)) : -1;
            prefs.edit().remove("model").remove("effort").apply();
            return settings;
        }

        void save(Context context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_PET, petIndex)
                    .apply();
        }

        void clampPet(int size) {
            petIndex = clampIndex(petIndex, Math.max(1, size));
        }

        void nextPet(int size) {
            petIndex = (petIndex + 1) % Math.max(1, size);
        }

        private static int clampIndex(int value, int size) {
            return Math.max(0, Math.min(size - 1, value));
        }
    }

    private static final class PetOption {
        final String id;
        final String displayName;
        final String spritesheetPath;
        final int spriteVersionNumber;

        PetOption(String id, String displayName, String spritesheetPath, int spriteVersionNumber) {
            this.id = id;
            this.displayName = displayName;
            this.spritesheetPath = spritesheetPath;
            this.spriteVersionNumber = spriteVersionNumber;
        }
    }

    private static final class PetCatalog {
        final List<PetOption> pets;

        PetCatalog(List<PetOption> pets) {
            this.pets = pets;
        }

        static PetCatalog load(Context context) {
            List<PetOption> loaded = new ArrayList<>();
            loaded.add(new PetOption("builtin", "Built-in", "", 1));
            AssetManager assets = context.getAssets();
            try {
                String[] ids = assets.list("pets");
                if (ids != null) {
                    Arrays.sort(ids, String.CASE_INSENSITIVE_ORDER);
                    for (String id : ids) {
                        PetOption option = loadPet(assets, id);
                        if (option != null) {
                            loaded.add(option);
                        }
                    }
                }
            } catch (Exception ignored) {
                loaded.subList(1, loaded.size()).clear();
            }
            return new PetCatalog(loaded);
        }

        int size() {
            return pets.size();
        }

        PetOption selected(UserSettings settings) {
            settings.clampPet(pets.size());
            return pets.get(settings.petIndex);
        }

        int defaultPetIndex() {
            for (int i = 0; i < pets.size(); i++) {
                if ("builtin".equals(pets.get(i).id)) {
                    return i;
                }
            }
            return 0;
        }

        private static PetOption loadPet(AssetManager assets, String id) {
            String displayName = readableName(id);
            String spritesheetPath = "spritesheet.webp";
            int spriteVersionNumber = 1;
            try (InputStream stream = assets.open("pets/" + id + "/pet.json")) {
                JSONObject json = new JSONObject(readAssetText(stream));
                displayName = cleanLabel(json.optString("displayName", displayName), displayName);
                spritesheetPath = json.optString("spritesheetPath", spritesheetPath);
                spriteVersionNumber = Math.max(1, json.optInt("spriteVersionNumber", 1));
            } catch (Exception ignored) {
                displayName = readableName(id);
            }
            try (InputStream ignored = assets.open("pets/" + id + "/" + spritesheetPath)) {
                return new PetOption(id, displayName, spritesheetPath, spriteVersionNumber);
            } catch (Exception ignored) {
                return null;
            }
        }

        private static String readAssetText(InputStream stream) throws Exception {
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new BufferedInputStream(stream), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
            }
            return builder.toString();
        }

        private static String cleanLabel(String value, String fallback) {
            String trimmed = value == null ? "" : value.trim();
            if (trimmed.isEmpty()
                    || trimmed.indexOf('\ufffd') >= 0
                    || trimmed.contains("?")
                    || !isMostlyPrintable(trimmed)
                    || trimmed.length() > 16) {
                return fallback;
            }
            return trimmed;
        }

        private static boolean isMostlyPrintable(String value) {
            for (int i = 0; i < value.length(); i++) {
                if (Character.isISOControl(value.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        private static String readableName(String id) {
            return id == null || id.trim().isEmpty() ? "pet" : id.trim();
        }
    }

    private static final class CodexWatchView extends View {
        private static final int DESIGN = 392;
        private static final int PAGE_USAGE = 0;
        private static final int PAGE_PLAY = 1;
        private static final int PAGE_SETTINGS = 2;
        private static final int PAGE_COUNT = 3;
        private static final float SWIPE_THRESHOLD = 46f;
        private static final String[] PLAY_ACTIONS = {"IDLE", "RUN RIGHT", "RUN LEFT", "WAVE", "JUMP", "FAILED", "WAITING", "WORKING", "REVIEW"};
        private static final int[] PLAY_ROWS = {0, 1, 2, 3, 4, 5, 6, 7, 8};
        private static final int[] PLAY_FRAMES = {6, 8, 8, 4, 5, 8, 6, 6, 6};
        private static final int COL_BG = Color.rgb(3, 6, 7);
        private static final int COL_PANEL = Color.rgb(7, 16, 20);
        private static final int COL_TEXT = Color.rgb(237, 248, 255);
        private static final int COL_MUTED = Color.rgb(127, 161, 173);
        private static final int COL_BLUE = Color.rgb(46, 156, 255);
        private static final int COL_GREEN = Color.rgb(90, 226, 154);
        private static final int COL_RED = Color.rgb(255, 92, 107);

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final RectF temp = new RectF();
        private final Path clip = new Path();
        private final UserSettings settings;
        private final PetCatalog catalog;
        private Bitmap sprite;
        private UsageData usage = UsageData.demo();
        private Runnable tapHandler;
        private Runnable updateTapHandler;
        private int page = PAGE_USAGE;
        private int playActionIndex = 0;
        private int resetCardGroupIndex = 0;
        private String loadedPetId = "";
        private float downX;
        private float downY;
        private String updateLabel = "CHECK";
        private String updateDetail = "Tap to check";
        private boolean updateError;

        CodexWatchView(Context context, UserSettings settings) {
            super(context);
            this.settings = settings;
            this.catalog = PetCatalog.load(context);
            if (this.settings.petIndex < 0) {
                this.settings.petIndex = catalog.defaultPetIndex();
            }
            this.settings.clampPet(catalog.size());
            setKeepScreenOn(true);
            spritePaint.setFilterBitmap(true);
            loadSprite();
        }

        void setUsage(UsageData usage) {
            this.usage = usage;
            int groups = usage.resetCardGroupCount();
            resetCardGroupIndex = groups == 0 ? 0 : Math.min(resetCardGroupIndex, groups - 1);
            invalidate();
        }

        void setTapHandler(Runnable tapHandler) {
            this.tapHandler = tapHandler;
        }

        void setUpdateTapHandler(Runnable updateTapHandler) {
            this.updateTapHandler = updateTapHandler;
        }

        void setUpdateStatus(String label, String detail, boolean error) {
            updateLabel = label;
            updateDetail = detail;
            updateError = error;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    performClick();
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (Math.abs(dx) > SWIPE_THRESHOLD && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        changePage(dx > 0 ? 1 : -1);
                    } else {
                        handleTap(event.getX(), event.getY());
                    }
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.BLACK);

            float side = Math.min(getWidth(), getHeight());
            float left = (getWidth() - side) / 2f;
            float top = (getHeight() - side) / 2f;
            float scale = side / DESIGN;

            canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            clip.reset();
            clip.addCircle(DESIGN / 2f, DESIGN / 2f, DESIGN / 2f, Path.Direction.CW);
            canvas.clipPath(clip);
            drawFace(canvas);
            canvas.restore();

            postInvalidateOnAnimation();
        }

        private void drawFace(Canvas canvas) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COL_BG);
            canvas.drawCircle(196, 196, 196, paint);

            if (page == PAGE_PLAY) {
                drawPlayFace(canvas);
            } else if (page == PAGE_SETTINGS) {
                drawSettingsFace(canvas);
            } else {
                drawUsageFace(canvas);
            }
            drawPageDots(canvas);
        }

        private void drawUsageFace(Canvas canvas) {
            drawTitle(canvas, "Codex");
            drawUsageArc(canvas);
            drawHero(canvas);
            drawChips(canvas);
            drawPet(canvas);
            drawMetricGrid(canvas);
        }

        private void drawPlayFace(Canvas canvas) {
            drawTitle(canvas, "Codex Pet");

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(42, 46, 156, 255));
            canvas.drawCircle(196, 178, 124, paint);
            paint.setColor(Color.argb(42, 90, 226, 154));
            temp.set(95, 276, 297, 302);
            canvas.drawOval(temp, paint);

            int row = PLAY_ROWS[playActionIndex];
            int frames = PLAY_FRAMES[playActionIndex];
            drawPetFrame(canvas, row, frames, 84, 70, 308, 314);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(16);
            paint.setColor(COL_GREEN);
            drawCenteredText(canvas, PLAY_ACTIONS[playActionIndex], 333, paint);
        }

        private void drawSettingsFace(Canvas canvas) {
            drawTitle(canvas, "Settings");
            drawPetFrame(canvas, 3, 4, 160, 43, 232, 115);
            drawSettingRow(canvas, 120, "PET", catalog.selected(settings).displayName, COL_GREEN);
            drawSettingRow(canvas, 168, "MODEL (AUTO)", usage.model, COL_BLUE);
            int groups = usage.resetCardGroupCount();
            String resetLabel = groups > 1
                    ? "RESET CARDS " + (resetCardGroupIndex + 1) + "/" + groups
                    : "RESET CARDS";
            drawResetCardRow(
                    canvas,
                    216,
                    resetLabel,
                    usage.resetCardDetailAt(resetCardGroupIndex),
                    usage.resetCardUrgentAt(resetCardGroupIndex) ? COL_RED : COL_GREEN
            );
            drawResetCardRow(
                    canvas,
                    264,
                    "UPDATE " + updateLabel,
                    updateDetail,
                    updateError ? COL_RED : COL_GREEN
            );
        }

        private void drawTitle(Canvas canvas, String title) {
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(18);
            paint.setColor(COL_BLUE);
            drawCenteredText(canvas, title, 35, paint);
        }

        private void drawUsageArc(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(5);
            temp.set(92, 50, 300, 218);
            paint.setColor(Color.argb(36, 255, 255, 255));
            canvas.drawArc(temp, 180, 180, false, paint);
            paint.setColor(COL_RED);
            canvas.drawArc(temp, 180, 180f * usage.sessionUsed / 100f, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private void drawHero(Canvas canvas) {
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(13);
            paint.setColor(COL_MUTED);
            drawCenteredText(canvas, "5H LEFT", 65, paint);

            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setTextSize(68);
            paint.setColor(COL_TEXT);
            String main = usage.hasRealData ? String.valueOf(usage.sessionLeft) : "--";
            float mainWidth = paint.measureText(main);
            paint.setTextSize(26);
            String suffix = usage.hasRealData ? "%" : "";
            float percentWidth = paint.measureText(suffix);
            float start = 196 - (mainWidth + percentWidth + 6) / 2f;

            paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            paint.setTextSize(68);
            paint.setColor(COL_TEXT);
            canvas.drawText(main, start, 128, paint);

            paint.setTextSize(26);
            paint.setColor(COL_MUTED);
            canvas.drawText(suffix, start + mainWidth + 6, 126, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(13);
            paint.setColor(usage.online && !usage.stale ? COL_GREEN : COL_RED);
            drawCenteredText(canvas, usage.resetLabel, 151, paint);
        }

        private void drawChips(Canvas canvas) {
            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(10);
            paint.setColor(COL_MUTED);
            canvas.drawText("MODEL", 54, 149, paint);
            canvas.drawText("RESET CARD", 274, 149, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(12);
            paint.setColor(Color.rgb(191, 231, 255));
            canvas.drawText(fit(usage.model, 12), 54, 166, paint);
            paint.setColor(usage.resetCardUrgent ? COL_RED : Color.rgb(191, 231, 255));
            canvas.drawText(fit(usage.resetCardLabel, 8), 291, 166, paint);
        }

        private void drawPet(Canvas canvas) {
            drawPetFrame(canvas, petRow(usage.petState), petFrames(usage.petState), 161, 168, 231, 244);
        }

        private void drawMetricGrid(Canvas canvas) {
            float tileW = 116;
            float tileH = 42;
            float gap = 8;
            float x1 = 76;
            float x2 = x1 + tileW + gap;
            float y1 = 252;
            float y2 = y1 + tileH + gap;

            drawTile(canvas, x1, y1, tileW, tileH, "5H USED", usage.sessionUsed + "%", usage.sessionUsed, COL_RED);
            drawTile(canvas, x2, y1, tileW, tileH, "WEEK", usage.weeklyLeft + "%", usage.weeklyLeft, COL_BLUE);
            drawTile(canvas, x1, y2, tileW, tileH, "TODAY", usage.today, 40, COL_BLUE);
            drawTile(canvas, x2, y2, tileW, tileH, "7D", usage.sevenDays, 72, COL_BLUE);
        }

        private void drawTile(Canvas canvas, float x, float y, float width, float height, String label, String value, int percent, int accent) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COL_PANEL);
            temp.set(x, y, x + width, y + height);
            canvas.drawRoundRect(temp, 8, 8, paint);

            paint.setColor(accent);
            temp.set(x, y, x + 3, y + height);
            canvas.drawRoundRect(temp, 2, 2, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(11);
            paint.setColor(COL_MUTED);
            canvas.drawText(label, x + 9, y + 18, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(14);
            paint.setColor(COL_TEXT);
            String compactValue = fit(value, 7);
            canvas.drawText(compactValue, x + width - 9 - paint.measureText(compactValue), y + 18, paint);

            float barX = x + 9;
            float barY = y + 30;
            float barW = width - 18;
            paint.setColor(Color.rgb(16, 40, 50));
            temp.set(barX, barY, barX + barW, barY + 7);
            canvas.drawRoundRect(temp, 4, 4, paint);

            paint.setColor(accent);
            temp.set(barX, barY, barX + Math.max(7, barW * percent / 100f), barY + 7);
            canvas.drawRoundRect(temp, 4, 4, paint);
        }

        private void drawSettingRow(Canvas canvas, float y, String label, String value, int accent) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COL_PANEL);
            temp.set(56, y, 336, y + 44);
            canvas.drawRoundRect(temp, 8, 8, paint);

            paint.setColor(accent);
            temp.set(56, y, 60, y + 44);
            canvas.drawRoundRect(temp, 2, 2, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(11);
            paint.setColor(COL_MUTED);
            canvas.drawText(label, 74, y + 18, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(18);
            paint.setColor(COL_TEXT);
            String compactValue = fit(value, 9);
            canvas.drawText(compactValue, 326 - paint.measureText(compactValue), y + 31, paint);
        }

        private void drawResetCardRow(Canvas canvas, float y, String label, String value, int accent) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(COL_PANEL);
            temp.set(56, y, 336, y + 44);
            canvas.drawRoundRect(temp, 8, 8, paint);

            paint.setColor(accent);
            temp.set(56, y, 60, y + 44);
            canvas.drawRoundRect(temp, 2, 2, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL));
            paint.setTextSize(10);
            paint.setColor(COL_MUTED);
            canvas.drawText(label, 74, y + 16, paint);

            paint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            paint.setTextSize(14);
            paint.setColor(COL_TEXT);
            String compactValue = fit(value, 16);
            canvas.drawText(compactValue, 326 - paint.measureText(compactValue), y + 34, paint);
        }

        private void drawPageDots(Canvas canvas) {
            float start = 178;
            for (int i = 0; i < PAGE_COUNT; i++) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(i == page ? COL_BLUE : Color.argb(70, 237, 248, 255));
                canvas.drawCircle(start + i * 18, 365, i == page ? 4.5f : 3.4f, paint);
            }
        }

        private void drawPetFrame(Canvas canvas, int row, int frames, float left, float top, float right, float bottom) {
            if (sprite == null) {
                drawBuiltInPet(canvas, row, left, top, right, bottom);
                return;
            }
            int safeFrames = Math.max(1, frames);
            int frame = (int) ((SystemClock.uptimeMillis() / 180L) % safeFrames);
            src.set(frame * 192, row * 208, frame * 192 + 192, row * 208 + 208);
            dst.set(left, top, right, bottom);
            canvas.drawBitmap(sprite, src, dst, spritePaint);
        }

        private void drawBuiltInPet(Canvas canvas, int row, float left, float top, float right, float bottom) {
            float width = right - left;
            float height = bottom - top;
            float cx = (left + right) / 2f;
            float tick = SystemClock.uptimeMillis() / 260f;
            float bob = row == 1 || row == 2 ? (float) Math.sin(tick) * height * 0.04f : (float) Math.sin(tick) * height * 0.015f;
            float y = top + bob;
            int accent = row == 5 ? COL_RED : row == 8 ? COL_BLUE : row == 7 ? COL_GREEN : Color.rgb(191, 231, 255);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(46, 90, 226, 154));
            temp.set(left + width * 0.16f, bottom - height * 0.12f, right - width * 0.16f, bottom - height * 0.03f);
            canvas.drawOval(temp, paint);

            paint.setColor(Color.rgb(18, 37, 44));
            temp.set(cx - width * 0.27f, y + height * 0.38f, cx + width * 0.27f, y + height * 0.84f);
            canvas.drawRoundRect(temp, width * 0.14f, width * 0.14f, paint);

            paint.setColor(accent);
            temp.set(cx - width * 0.34f, y + height * 0.14f, cx + width * 0.34f, y + height * 0.57f);
            canvas.drawOval(temp, paint);

            paint.setColor(Color.rgb(237, 248, 255));
            temp.set(cx - width * 0.20f, y + height * 0.29f, cx - width * 0.10f, y + height * 0.39f);
            canvas.drawOval(temp, paint);
            temp.set(cx + width * 0.10f, y + height * 0.29f, cx + width * 0.20f, y + height * 0.39f);
            canvas.drawOval(temp, paint);

            paint.setColor(COL_BG);
            if (row == 5) {
                paint.setStrokeWidth(Math.max(2f, width * 0.035f));
                canvas.drawLine(cx - width * 0.20f, y + height * 0.34f, cx - width * 0.10f, y + height * 0.34f, paint);
                canvas.drawLine(cx + width * 0.10f, y + height * 0.34f, cx + width * 0.20f, y + height * 0.34f, paint);
            } else {
                temp.set(cx - width * 0.16f, y + height * 0.32f, cx - width * 0.12f, y + height * 0.36f);
                canvas.drawOval(temp, paint);
                temp.set(cx + width * 0.12f, y + height * 0.32f, cx + width * 0.16f, y + height * 0.36f);
                canvas.drawOval(temp, paint);
            }

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(Math.max(2f, width * 0.035f));
            paint.setColor(Color.rgb(237, 248, 255));
            float mouthY = y + height * 0.45f;
            if (row == 8) {
                canvas.drawLine(cx - width * 0.08f, mouthY, cx + width * 0.08f, mouthY, paint);
            } else if (row == 5) {
                temp.set(cx - width * 0.10f, mouthY, cx + width * 0.10f, mouthY + height * 0.10f);
                canvas.drawArc(temp, 200, 140, false, paint);
            } else {
                temp.set(cx - width * 0.10f, mouthY - height * 0.05f, cx + width * 0.10f, mouthY + height * 0.06f);
                canvas.drawArc(temp, 25, 130, false, paint);
            }

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
        }

        private void drawCenteredText(Canvas canvas, String text, float baseline, Paint p) {
            canvas.drawText(text, 196 - p.measureText(text) / 2f, baseline, p);
        }

        private void loadSprite() {
            PetOption selected = catalog.selected(settings);
            if (selected.id.equals(loadedPetId)) {
                return;
            }
            if (selected.spritesheetPath == null || selected.spritesheetPath.trim().isEmpty()) {
                sprite = null;
                loadedPetId = selected.id;
                return;
            }
            try (InputStream stream = getContext().getAssets().open("pets/" + selected.id + "/" + selected.spritesheetPath)) {
                sprite = BitmapFactory.decodeStream(stream);
                loadedPetId = selected.id;
            } catch (Exception ignored) {
                sprite = null;
                loadedPetId = selected.id;
            }
        }

        private int petRow(String state) {
            if ("running".equals(state)) {
                return 7;
            }
            if ("waiting".equals(state)) {
                return 6;
            }
            if ("review".equals(state)) {
                return 8;
            }
            if ("failed".equals(state)) {
                return 5;
            }
            return 0;
        }

        private int petFrames(String state) {
            if ("running".equals(state)) {
                return 6;
            }
            if ("failed".equals(state)) {
                return 8;
            }
            return 6;
        }

        private void changePage(int direction) {
            page = (page + direction + PAGE_COUNT) % PAGE_COUNT;
        }

        private void handleTap(float rawX, float rawY) {
            if (page == PAGE_USAGE) {
                if (tapHandler != null) {
                    tapHandler.run();
                }
                return;
            }
            if (page == PAGE_PLAY) {
                playActionIndex = (playActionIndex + 1) % PLAY_ACTIONS.length;
                return;
            }
            handleSettingsTap(toDesignY(rawY));
        }

        private void handleSettingsTap(float y) {
            boolean changed = true;
            if (y >= 116 && y <= 166) {
                settings.nextPet(catalog.size());
                loadSprite();
            } else if (y >= 212 && y <= 262 && usage.resetCardGroupCount() > 1) {
                resetCardGroupIndex = (resetCardGroupIndex + 1) % usage.resetCardGroupCount();
            } else if (y >= 264 && y <= 316 && updateTapHandler != null) {
                updateTapHandler.run();
            } else {
                changed = false;
            }
            if (changed) {
                settings.save(getContext());
            }
        }

        private float toDesignY(float rawY) {
            float side = Math.min(getWidth(), getHeight());
            if (side <= 0) {
                return 0;
            }
            float top = (getHeight() - side) / 2f;
            return (rawY - top) * DESIGN / side;
        }

        private String fit(String value, int max) {
            if (value == null) {
                return "";
            }
            return value.length() <= max ? value : value.substring(0, max);
        }
    }
}
