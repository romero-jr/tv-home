package com.tvhome.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final long WEATHER_INTERVAL = 30 * 60 * 1000L;
    private static final long AUTO_LOCATION_MAX_AGE = 24 * 60 * 60 * 1000L;
    private static final float FOCUS_SCALE = 1.12f;

    static class App {
        String label;
        ComponentName component;
        String launchCategory;
        Drawable banner;
        Drawable icon;
    }

    private final List<App> allApps = new ArrayList<>();
    private final List<App> apps = new ArrayList<>();
    private final List<App> hiddenApps = new ArrayList<>();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService appLoader = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private GridView grid;
    private AppAdapter adapter;
    private TextView weatherTemp;
    private TextView weatherUnit;
    private TextView weatherIcon;
    private TextView weatherCondition;
    private TextView weatherFeels;
    private TextView weatherPlace;
    private final View[] forecastDays = new View[5];
    private View selectedTile;
    private boolean appsShown;
    private boolean appsDirty = true;
    private boolean appsLoading;

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather(false);
            ui.postDelayed(this, WEATHER_INTERVAL);
        }
    };

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);

        weatherTemp = findViewById(R.id.weather_temp);
        weatherUnit = findViewById(R.id.weather_unit);
        weatherIcon = findViewById(R.id.weather_icon);
        weatherCondition = findViewById(R.id.weather_condition);
        weatherFeels = findViewById(R.id.weather_feels);
        weatherPlace = findViewById(R.id.weather_place);
        forecastDays[0] = findViewById(R.id.day1);
        forecastDays[1] = findViewById(R.id.day2);
        forecastDays[2] = findViewById(R.id.day3);
        forecastDays[3] = findViewById(R.id.day4);
        forecastDays[4] = findViewById(R.id.day5);
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());

        grid = findViewById(R.id.apps);
        adapter = new AppAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> launch(apps.get(pos)));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            showAppOptions(apps.get(pos));
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                highlight(grid.hasFocus() ? view : null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                highlight(null);
            }
        });
        grid.setOnFocusChangeListener((v, hasFocus) -> highlight(hasFocus ? grid.getSelectedView() : null));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packagesChanged, filter);

        showCachedWeather();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        showCachedWeather();
        ui.post(weatherTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(weatherTick);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packagesChanged);
        appLoader.shutdownNow();
        bg.shutdownNow();
        super.onDestroy();
    }

    // A home screen has nowhere to go back to.
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && grid.hasFocus()) {
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0 && pos < apps.size()) showAppOptions(apps.get(pos));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── Apps ──

    private void loadApps() {
        if (!appsDirty || appsLoading) return;
        appsDirty = false;
        appsLoading = true;
        appLoader.execute(() -> {
            try {
                List<App> loaded = queryApps();
                ui.post(() -> {
                    if (isDestroyed()) return;
                    appsLoading = false;
                    allApps.clear();
                    allApps.addAll(loaded);
                    applyAppVisibility();
                    loadApps();
                });
            } catch (RuntimeException error) {
                ui.post(() -> {
                    if (isDestroyed()) return;
                    appsLoading = false;
                    appsDirty = true;
                    if (apps.isEmpty()) Toast.makeText(this, "Couldn't load apps", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private List<App> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> byPackage = new LinkedHashMap<>();
        Map<String, String> categories = new LinkedHashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo ri : pm.queryIntentActivities(intent, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (!pkg.equals(getPackageName()) && !byPackage.containsKey(pkg)) {
                    byPackage.put(pkg, ri);
                    categories.put(pkg, category);
                }
            }
        }
        List<App> loaded = new ArrayList<>();
        for (ResolveInfo ri : byPackage.values()) {
            App app = new App();
            app.label = ri.loadLabel(pm).toString();
            app.component = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            app.launchCategory = categories.get(ri.activityInfo.packageName);
            app.banner = ri.activityInfo.loadBanner(pm);
            if (app.banner == null) app.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (app.banner == null) app.icon = ri.loadIcon(pm);
            loaded.add(app);
        }
        Collator collator = Collator.getInstance();
        Collections.sort(loaded, (first, second) -> collator.compare(first.label, second.label));
        return loaded;
    }

    private void applyAppVisibility() {
        int previousPosition = grid.getSelectedItemPosition();
        ComponentName previous = previousPosition >= 0 && previousPosition < apps.size()
                ? apps.get(previousPosition).component : null;
        Set<String> hidden = prefs.getStringSet("hidden", Collections.emptySet());
        apps.clear();
        hiddenApps.clear();
        for (App app : allApps) {
            (hidden.contains(app.component.getPackageName()) ? hiddenApps : apps).add(app);
        }
        highlight(null);
        adapter.notifyDataSetChanged();
        if (apps.isEmpty()) {
            appsShown = false;
            return;
        }
        if (!appsShown) {
            appsShown = true;
            grid.requestFocus();
            grid.setSelection(0);
            return;
        }
        int position = Math.max(0, Math.min(previousPosition, apps.size() - 1));
        for (int index = 0; index < apps.size(); index++) {
            if (apps.get(index).component.equals(previous)) {
                position = index;
                break;
            }
        }
        grid.setSelection(position);
    }

    private void launch(App app) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(app.launchCategory)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't open " + app.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void setHidden(String pkg, boolean hide) {
        Set<String> hidden = new HashSet<>(prefs.getStringSet("hidden", Collections.emptySet()));
        if (hide) hidden.add(pkg);
        else hidden.remove(pkg);
        prefs.edit().putStringSet("hidden", hidden).apply();
        applyAppVisibility();
    }

    private void showAppOptions(App app) {
        new AlertDialog.Builder(this)
                .setTitle(app.label)
                .setItems(new String[]{"Open", "Hide from home screen", "App info / uninstall"}, (d, which) -> {
                    if (which == 0) launch(app);
                    else if (which == 1) setHidden(app.component.getPackageName(), true);
                    else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", app.component.getPackageName(), null)));
                })
                .show();
    }

    private void highlight(View tile) {
        if (selectedTile == tile) return;
        View previous = selectedTile;
        selectedTile = tile;
        if (previous != null) {
            previous.animate().cancel();
            previous.animate().scaleX(1f).scaleY(1f).setDuration(100).start();
        }
        if (tile != null) {
            tile.animate().cancel();
            tile.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE).setDuration(100).start();
        }
    }

    private static class TileHolder {
        final ImageView banner;
        final View iconGroup;
        final ImageView icon;
        final TextView label;

        TileHolder(View view) {
            banner = view.findViewById(R.id.banner);
            iconGroup = view.findViewById(R.id.icon_group);
            icon = view.findViewById(R.id.icon);
            label = view.findViewById(R.id.label);
        }
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int pos) {
            return apps.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View view, ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(MainActivity.this).inflate(R.layout.tile, parent, false);
                view.setClipToOutline(true); // round the banner corners to match the tile
                view.setTag(new TileHolder(view));
            }
            App app = apps.get(pos);
            view.setContentDescription(app.label);
            TileHolder holder = (TileHolder) view.getTag();
            if (app.banner != null) {
                holder.banner.setImageDrawable(app.banner);
                holder.banner.setVisibility(View.VISIBLE);
                holder.icon.setImageDrawable(null);
                holder.iconGroup.setVisibility(View.GONE);
            } else {
                holder.icon.setImageDrawable(app.icon);
                holder.label.setText(app.label);
                holder.banner.setImageDrawable(null);
                holder.banner.setVisibility(View.GONE);
                holder.iconGroup.setVisibility(View.VISIBLE);
            }
            // Recycled views may still carry the focus highlight.
            boolean selected = grid.hasFocus() && pos == grid.getSelectedItemPosition();
            view.animate().cancel();
            view.setScaleX(selected ? FOCUS_SCALE : 1f);
            view.setScaleY(selected ? FOCUS_SCALE : 1f);
            if (selected) selectedTile = view;
            else if (selectedTile == view) selectedTile = null;
            return view;
        }
    }

    // ── Settings ──

    private boolean useFahrenheit() {
        String country = Locale.getDefault().getCountry();
        boolean localeDefault = country.equals("US") || country.equals("LR") || country.equals("MM");
        return prefs.getBoolean("fahrenheit", localeDefault);
    }

    private void showSettings() {
        String place = prefs.getBoolean("manualLocation", false) ? prefs.getString("place", "") : "Automatic";
        String[] items = {
                "Weather location: " + place,
                "Temperature: " + (useFahrenheit() ? "°F" : "°C"),
                "Hidden apps (" + hiddenApps.size() + ")",
                "Android settings",
        };
        new AlertDialog.Builder(this)
                .setTitle("TV Home")
                .setItems(items, (d, which) -> {
                    if (which == 0) askCity();
                    else if (which == 1) {
                        prefs.edit().putBoolean("fahrenheit", !useFahrenheit()).apply();
                        refreshWeather(true);
                    } else if (which == 2) showHiddenApps();
                    else startActivity(new Intent(Settings.ACTION_SETTINGS));
                })
                .show();
    }

    private void showHiddenApps() {
        if (hiddenApps.isEmpty()) {
            Toast.makeText(this, "No hidden apps", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[hiddenApps.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = hiddenApps.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("Select an app to show it again")
                .setItems(labels, (d, which) -> setHidden(hiddenApps.get(which).component.getPackageName(), false))
                .show();
    }

    private void askCity() {
        EditText input = new EditText(this);
        input.setHint("City, e.g. Austin");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Weather location")
                .setMessage("Leave empty to detect automatically.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> setCity(input.getText().toString().trim()))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void setCity(String city) {
        if (city.isEmpty()) {
            prefs.edit().putBoolean("manualLocation", false).remove("locationAt").apply();
            refreshWeather(true);
            return;
        }
        bg.execute(() -> {
            try {
                JSONObject res = getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(city, "UTF-8"));
                JSONArray results = res.optJSONArray("results");
                if (results == null || results.length() == 0) throw new IOException("not found");
                JSONObject r = results.getJSONObject(0);
                prefs.edit()
                        .putBoolean("manualLocation", true)
                        .putFloat("lat", (float) r.getDouble("latitude"))
                        .putFloat("lon", (float) r.getDouble("longitude"))
                        .putString("place", r.getString("name"))
                        .apply();
                ui.post(() -> refreshWeather(true));
            } catch (Exception e) {
                ui.post(() -> Toast.makeText(this, "Couldn't find \"" + city + "\"", Toast.LENGTH_SHORT).show());
            }
        });
    }

    // ── Weather (Open-Meteo, no API key needed) ──

    // The raw forecast is cached so the cards render instantly on the next launch.
    private void showCachedWeather() {
        String json = prefs.getString("weatherJson", null);
        if (json == null) return;
        try {
            JSONObject w = new JSONObject(json);
            boolean fahrenheit = prefs.getBoolean("weatherFahrenheit", true);
            JSONObject cur = w.getJSONObject("current");
            JSONObject daily = w.getJSONObject("daily");
            int code = cur.getInt("weather_code");

            weatherTemp.setText(String.valueOf(Math.round(cur.getDouble("temperature_2m"))));
            weatherUnit.setText(fahrenheit ? "°F" : "°C");
            weatherIcon.setText(weatherGlyph(code, cur.optInt("is_day", 1) == 1));
            weatherCondition.setText(weatherText(code));
            weatherFeels.setText("Feels like " + Math.round(cur.getDouble("apparent_temperature")) + "°");
            String place = prefs.getString("place", "");
            weatherPlace.setText(place.isEmpty() ? "" : "◎ " + place);

            // Index 0 is today; the card shows the next five days.
            JSONArray dates = daily.getJSONArray("time");
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < forecastDays.length && i + 1 < dates.length(); i++) {
                View day = forecastDays[i];
                Date date = in.parse(dates.getString(i + 1));
                ((TextView) day.findViewById(R.id.day)).setText(date != null ? out.format(date) : "");
                ((TextView) day.findViewById(R.id.icon)).setText(
                        weatherGlyph(daily.getJSONArray("weather_code").getInt(i + 1), true));
                ((TextView) day.findViewById(R.id.hi)).setText(
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i + 1)) + "°");
                ((TextView) day.findViewById(R.id.lo)).setText(
                        Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i + 1)) + "°");
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshWeather(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && prefs.contains("weatherJson") && now - prefs.getLong("weatherAt", 0) < WEATHER_INTERVAL) return;
        boolean fahrenheit = useFahrenheit();
        bg.execute(() -> {
            try {
                boolean manual = prefs.getBoolean("manualLocation", false);
                if (!manual && now - prefs.getLong("locationAt", 0) > AUTO_LOCATION_MAX_AGE) {
                    // Approximate location from the box's IP address.
                    JSONObject loc = getJson("https://ipapi.co/json/");
                    prefs.edit()
                            .putFloat("lat", (float) loc.getDouble("latitude"))
                            .putFloat("lon", (float) loc.getDouble("longitude"))
                            .putString("place", loc.optString("city", ""))
                            .putLong("locationAt", now)
                            .apply();
                }
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + prefs.getFloat("lat", 0) + "&longitude=" + prefs.getFloat("lon", 0)
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto"
                        + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
                JSONObject w = getJson(url);
                prefs.edit()
                        .putString("weatherJson", w.toString())
                        .putBoolean("weatherFahrenheit", fahrenheit)
                        .putLong("weatherAt", now)
                        .apply();
                ui.post(this::showCachedWeather);
            } catch (Exception e) {
                ui.post(() -> {
                    if (prefs.getString("weatherJson", null) == null) weatherCondition.setText("Weather unavailable");
                });
            }
        });
    }

    // WMO weather interpretation codes
    static String weatherText(int code) {
        if (code == 0) return "Clear sky";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "";
    }

    static String weatherGlyph(int code, boolean day) {
        if (code <= 1) return day ? "☀️" : "🌙";
        if (code == 2) return day ? "⛅" : "☁️";
        if (code == 3) return "☁️";
        if (code == 45 || code == 48) return "🌫️";
        if (code >= 51 && code <= 67) return "🌧️";
        if (code >= 71 && code <= 77) return "❄️";
        if (code >= 80 && code <= 82) return "🌦️";
        if (code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "";
    }

    static JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "TVHome/1.0");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
            }
        } finally {
            conn.disconnect();
        }
    }
}
