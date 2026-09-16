package org.telegram.messenger;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class AiProxyClient {
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private AiProxyClient() {}

    static boolean isConfigured() {
        return BuildVars.AI_PROXY_URL != null && !BuildVars.AI_PROXY_URL.trim().isEmpty();
    }

    static String postJson(String path, JSONObject payload) throws Exception {
        HttpURLConnection connection = open(path, "application/json; charset=utf-8");
        try {
            connection.setDoOutput(true);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            return response(connection);
        } finally {
            connection.disconnect();
        }
    }

    static String postFile(File file, String contentType) throws Exception {
        HttpURLConnection connection = open("/v1/transcriptions", contentType);
        try {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(file.length());
            try (OutputStream output = connection.getOutputStream();
                 FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            return response(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(String path, String contentType) throws Exception {
        String base = BuildVars.AI_PROXY_URL.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", contentType);
        connection.setRequestProperty("Accept", "application/json");
        return connection;
    }

    private static String response(HttpURLConnection connection) throws Exception {
        int code = connection.getResponseCode();
        String body = read(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (code < 200 || code >= 300) {
            throw new IllegalStateException("AI proxy HTTP " + code);
        }
        return body;
    }

    private static String read(@Nullable InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
            return result.toString();
        }
    }
}
