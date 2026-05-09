package com.example.testing;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * DeepSeek API 统一客户端
 * API Key 由用户自行配置，存储在 SharedPreferences 中。
 */
public class DeepSeekClient {

    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String PREFS_NAME = "deepseek_prefs";
    private static final String KEY_API_KEY = "api_key";

    private static Context appContext;

    public static void init(Context context) {
        appContext = context.getApplicationContext();
    }

    public static boolean isConfigured() {
        if (appContext == null) return false;
        String key = getApiKey();
        return key != null && key.startsWith("sk-") && key.length() > 10;
    }

    public static String getApiKey() {
        if (appContext == null) return null;
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_API_KEY, null);
    }

    public static void saveApiKey(String key) {
        if (appContext == null) return;
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_API_KEY, key.trim()).apply();
    }

    public static void clearApiKey() {
        if (appContext == null) return;
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_API_KEY).apply();
    }

    public interface SimpleCallback {
        void onSuccess(String reply);
        void onError(String error);
    }

    public interface StreamCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String error);
    }

    public static void chat(JSONArray messages, double temperature, SimpleCallback callback) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            AppExecutors.getInstance().mainThread().execute(
                    () -> callback.onError("NO_API_KEY"));
            return;
        }
        AppExecutors.getInstance().networkIO().execute(() -> {
            try {
                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "deepseek-chat");
                requestBody.put("messages", messages);
                requestBody.put("stream", false);
                requestBody.put("temperature", temperature);

                String responseStr = postBlocking(requestBody, apiKey);
                JSONObject responseJson = new JSONObject(responseStr);
                String reply = responseJson.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");

                AppExecutors.getInstance().mainThread().execute(() -> callback.onSuccess(reply));
            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> callback.onError(e.getMessage()));
            }
        });
    }

    public static void chatStream(JSONArray messages, StreamCallback callback) {
        String apiKey = getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            AppExecutors.getInstance().mainThread().execute(
                    () -> callback.onError("NO_API_KEY"));
            return;
        }
        AppExecutors.getInstance().networkIO().execute(() -> {
            StringBuilder fullText = new StringBuilder();
            try {
                JSONObject requestBody = new JSONObject();
                requestBody.put("model", "deepseek-chat");
                requestBody.put("messages", messages);
                requestBody.put("stream", true);

                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
                }

                if (conn.getResponseCode() != 200) {
                    throw new Exception("HTTP " + conn.getResponseCode());
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) continue;
                        String data = line.substring(6).trim();
                        if (data.equals("[DONE]")) break;

                        JSONObject chunk = new JSONObject(data);
                        JSONObject delta = chunk.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("delta");
                        if (delta.has("content")) {
                            String token = delta.getString("content");
                            fullText.append(token);
                            AppExecutors.getInstance().mainThread().execute(() -> callback.onToken(token));
                        }
                    }
                }

                final String result = fullText.toString();
                AppExecutors.getInstance().mainThread().execute(() -> callback.onComplete(result));

            } catch (Exception e) {
                AppExecutors.getInstance().mainThread().execute(() -> callback.onError(e.getMessage()));
            }
        });
    }

    private static String postBlocking(JSONObject requestBody, String apiKey) throws Exception {
        URL url = new URL(API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(requestBody.toString().getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() != 200) {
            throw new Exception("HTTP " + conn.getResponseCode());
        }

        try (Scanner scanner = new Scanner(conn.getInputStream(), "UTF-8").useDelimiter("\\A")) {
            return scanner.hasNext() ? scanner.next() : "";
        }
    }
}
