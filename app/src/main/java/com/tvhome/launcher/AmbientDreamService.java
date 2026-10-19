package com.tvhome.launcher;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AmbientDreamService extends DreamService {
    private static final long WEATHER_INTERVAL = 30 * 60 * 1000L;
    private static final long LOCATION_INTERVAL = 24 * 60 * 60 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService background;
    private SharedPreferences prefs;
    private View cards;
    private final View[] forecastDays = new View[5];

    private final Runnable refreshTick = new Runnable() {
        @Override
        public void run() {
            showCachedWeather();
            refreshWeather();
            handler.postDelayed(this, WEATHER_INTERVAL);
        }
    };

    private final Runnable moveTick = new Runnable() {
        private int position;

        @Override
        public void run() {
            int[] offset = {0, 5, 0, -5};
            cards.animate().translationX(offset[position]).translationY(offset[(position + 1) % 4])
                    .setDuration(2000).start();
            position = (position + 1) % 4;
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.ambient_dream);
        cards = findViewById(R.id.ambient_cards);
        forecastDays[0] = findViewById(R.id.ambient_day1);
        forecastDays[1] = findViewById(R.id.ambient_day2);
        forecastDays[2] = findViewById(R.id.ambient_day3);
        forecastDays[3] = findViewById(R.id.ambient_day4);
        forecastDays[4] = findViewById(R.id.ambient_day5);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        background = Executors.newSingleThreadExecutor();
        showCachedWeather();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.post(refreshTick);
        handler.post(moveTick);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(moveTick);
        cards.animate().cancel();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(refreshTick);
        handler.removeCallbacks(moveTick);
        background.shutdownNow();
        super.onDetachedFromWindow();
    }

    private void showCachedWeather() {
        String json = prefs.getString("weatherJson", null);
        if (json == null) return;
        try {
            JSONObject weather = new JSONObject(json);
            JSONObject current = weather.getJSONObject("current");
            JSONObject daily = weather.getJSONObject("daily");
            int code = current.getInt("weather_code");

            ((TextView) findViewById(R.id.ambient_temp)).setText(
                    String.valueOf(Math.round(current.getDouble("temperature_2m"))));
            ((TextView) findViewById(R.id.ambient_unit)).setText(
                    prefs.getBoolean("weatherFahrenheit", true) ? "°F" : "°C");
            ((TextView) findViewById(R.id.ambient_icon)).setText(
                    MainActivity.weatherGlyph(code, current.optInt("is_day", 1) == 1));
            ((TextView) findViewById(R.id.ambient_condition)).setText(MainActivity.weatherText(code));
            ((TextView) findViewById(R.id.ambient_place)).setText(prefs.getString("place", ""));

            JSONArray dates = daily.getJSONArray("time");
            SimpleDateFormat input = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat output = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int index = 0; index < forecastDays.length && index + 1 < dates.length(); index++) {
                View day = forecastDays[index];
                Date date = input.parse(dates.getString(index + 1));
                ((TextView) day.findViewById(R.id.day)).setText(date == null ? "" : output.format(date));
                ((TextView) day.findViewById(R.id.icon)).setText(
                        MainActivity.weatherGlyph(daily.getJSONArray("weather_code").getInt(index + 1), true));
                ((TextView) day.findViewById(R.id.hi)).setText(
                        Math.round(daily.getJSONArray("temperature_2m_max").getDouble(index + 1)) + "°");
                ((TextView) day.findViewById(R.id.lo)).setText(
                        Math.round(daily.getJSONArray("temperature_2m_min").getDouble(index + 1)) + "°");
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshWeather() {
        long now = System.currentTimeMillis();
        boolean fahrenheit = prefs.getBoolean("fahrenheit", true);
        if (prefs.contains("weatherJson") && now - prefs.getLong("weatherAt", 0) < WEATHER_INTERVAL
                && fahrenheit == prefs.getBoolean("weatherFahrenheit", true)) return;
        background.execute(() -> {
            try {
                if (!prefs.getBoolean("manualLocation", false)
                        && now - prefs.getLong("locationAt", 0) > LOCATION_INTERVAL) {
                    JSONObject location = MainActivity.getJson("https://ipapi.co/json/");
                    prefs.edit().putFloat("lat", (float) location.getDouble("latitude"))
                            .putFloat("lon", (float) location.getDouble("longitude"))
                            .putString("place", location.optString("city", ""))
                            .putLong("locationAt", now).apply();
                }
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + prefs.getFloat("lat", 0)
                        + "&longitude=" + prefs.getFloat("lon", 0)
                        + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto"
                        + (fahrenheit ? "&temperature_unit=fahrenheit" : "");
                JSONObject weather = MainActivity.getJson(url);
                prefs.edit().putString("weatherJson", weather.toString())
                        .putBoolean("weatherFahrenheit", fahrenheit)
                        .putLong("weatherAt", now).apply();
                handler.post(this::showCachedWeather);
            } catch (Exception error) {
                handler.post(() -> {
                    if (prefs.getString("weatherJson", null) == null) {
                        ((TextView) findViewById(R.id.ambient_condition)).setText("Weather unavailable");
                    }
                });
            }
        });
    }
}
