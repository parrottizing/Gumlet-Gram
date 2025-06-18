package org.telegram.messenger;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TTSManager {
    
    private static final String TAG = "TTSManager";
    private static final String GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent";
    
    private static TTSManager instance;
    private MediaPlayer currentMediaPlayer;
    private ExecutorService executor;
    
    private TTSManager() {
        executor = Executors.newSingleThreadExecutor();
    }
    
    public static TTSManager getInstance() {
        if (instance == null) {
            instance = new TTSManager();
        }
        return instance;
    }
    
    public void readAloud(String text, Context context) {
        Log.d(TAG, "readAloud called with text length: " + (text != null ? text.length() : 0));
        
        if (TextUtils.isEmpty(text)) {
            Log.w(TAG, "readAloud: text is empty or null");
            return;
        }
        
        if (TextUtils.isEmpty(BuildVars.GEMINI_API_KEY)) {
            Log.e(TAG, "readAloud: GEMINI_API_KEY is empty or null");
            return;
        }
        
        Log.d(TAG, "readAloud: Starting TTS for text: " + text.substring(0, Math.min(text.length(), 100)) + "...");
        
        // Ensure executor is available
        ensureExecutor();
        
        // Clean up any existing playback
        stopPlayback();
        
        // Call Gemini API for TTS
        callGeminiTTS(text, context);
    }
    
    private void ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            Log.d(TAG, "Creating new executor (previous was " + (executor == null ? "null" : "shutdown") + ")");
            executor = Executors.newSingleThreadExecutor();
        }
    }
    
    private void callGeminiTTS(String text, Context context) {
        Log.d(TAG, "callGeminiTTS: Starting executor task");
        executor.execute(() -> {
            try {
                Log.d(TAG, "callGeminiTTS: Creating JSON request");
                
                // Create JSON request for Gemini TTS
                JSONObject requestJson = new JSONObject();
                
                // Contents array with text input
                JSONArray contentsArray = new JSONArray();
                JSONObject contentObject = new JSONObject();
                JSONArray partsArray = new JSONArray();
                JSONObject partObject = new JSONObject();
                partObject.put("text", text);
                partsArray.put(partObject);
                contentObject.put("parts", partsArray);
                contentsArray.put(contentObject);
                requestJson.put("contents", contentsArray);
                
                // Generation config with audio response modality
                JSONObject generationConfig = new JSONObject();
                JSONArray responseModalities = new JSONArray();
                responseModalities.put("AUDIO");
                generationConfig.put("responseModalities", responseModalities);
                
                // Speech config with voice
                JSONObject speechConfig = new JSONObject();
                JSONObject voiceConfig = new JSONObject();
                JSONObject prebuiltVoiceConfig = new JSONObject();
                prebuiltVoiceConfig.put("voiceName", "Charon"); // Use Charon voice (Informative)
                voiceConfig.put("prebuiltVoiceConfig", prebuiltVoiceConfig);
                speechConfig.put("voiceConfig", voiceConfig);
                generationConfig.put("speechConfig", speechConfig);
                
                requestJson.put("generationConfig", generationConfig);
                
                Log.d(TAG, "callGeminiTTS: JSON request created: " + requestJson.toString());
                
                // Setup HTTP connection
                URL url = new URL(GEMINI_API_URL + "?key=" + BuildVars.GEMINI_API_KEY);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(45000);
                
                Log.d(TAG, "callGeminiTTS: Connection configured, sending request");
                
                // Send request
                String jsonInputString = requestJson.toString();
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonInputString.getBytes("utf-8");
                    os.write(input, 0, input.length);
                }
                
                Log.d(TAG, "callGeminiTTS: Request sent, getting response");
                
                // Check response
                int responseCode = connection.getResponseCode();
                Log.d(TAG, "callGeminiTTS: Response code: " + responseCode);
                
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "callGeminiTTS: Success response, reading JSON");
                    
                    // Read response as JSON
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try (InputStream is = connection.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        int bytesRead;
                        while ((bytesRead = is.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                    }
                    
                    String responseJson = baos.toString("utf-8");
                    Log.d(TAG, "callGeminiTTS: Response JSON received, length: " + responseJson.length());
                    
                    // Parse JSON response to extract audio data
                    JSONObject response = new JSONObject(responseJson);
                    JSONArray candidates = response.getJSONArray("candidates");
                    if (candidates.length() > 0) {
                        JSONObject firstCandidate = candidates.getJSONObject(0);
                        JSONObject content = firstCandidate.getJSONObject("content");
                        JSONArray parts = content.getJSONArray("parts");
                        
                        if (parts.length() > 0) {
                            JSONObject firstPart = parts.getJSONObject(0);
                            if (firstPart.has("inlineData")) {
                                JSONObject inlineData = firstPart.getJSONObject("inlineData");
                                String base64AudioData = inlineData.getString("data");
                                
                                // Decode base64 audio data
                                byte[] audioData = android.util.Base64.decode(base64AudioData, android.util.Base64.DEFAULT);
                                Log.d(TAG, "callGeminiTTS: Audio data decoded, size: " + audioData.length + " bytes");
                                
                                AndroidUtilities.runOnUIThread(() -> playAudioStream(audioData));
                            } else {
                                Log.e(TAG, "No inline audio data found in response");
                            }
                        } else {
                            Log.e(TAG, "No parts found in response");
                        }
                    } else {
                        Log.e(TAG, "No candidates found in response");
                    }
                    
                } else {
                    Log.e(TAG, "Gemini API error: " + responseCode);
                    
                    // Read error response
                    try (InputStream errorStream = connection.getErrorStream()) {
                        if (errorStream != null) {
                            ByteArrayOutputStream errorBaos = new ByteArrayOutputStream();
                            byte[] buffer = new byte[1024];
                            int bytesRead;
                            while ((bytesRead = errorStream.read(buffer)) != -1) {
                                errorBaos.write(buffer, 0, bytesRead);
                            }
                            String errorResponse = errorBaos.toString("utf-8");
                            Log.e(TAG, "API error response: " + errorResponse);
                        }
                    } catch (IOException e) {
                        Log.e(TAG, "Error reading error response", e);
                    }
                    
                    AndroidUtilities.runOnUIThread(() -> {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.e("TTSManager: API call failed with code: " + responseCode);
                        }
                    });
                }
                
                connection.disconnect();
                
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error calling Gemini TTS API", e);
                AndroidUtilities.runOnUIThread(() -> {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("TTSManager: Failed to generate speech - " + e.getMessage());
                    }
                });
            }
        });
    }
    
    private void playAudioStream(byte[] audioData) {
        Log.d(TAG, "playAudioStream: Starting audio playback with " + audioData.length + " bytes");
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // Create temporary file for audio data (Convert PCM to WAV)
                java.io.File tempFile = java.io.File.createTempFile("tts_audio", ".wav", ApplicationLoader.applicationContext.getCacheDir());
                Log.d(TAG, "playAudioStream: Created temp file: " + tempFile.getAbsolutePath());
                
                // Convert raw PCM to WAV format with proper headers
                byte[] wavData = convertPcmToWav(audioData, 24000, 1, 16);
                
                // Write WAV data to file
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(wavData);
                    Log.d(TAG, "playAudioStream: WAV data written to file");
                }
                
                Log.d(TAG, "playAudioStream: Initializing MediaPlayer");
                // Initialize MediaPlayer
                currentMediaPlayer = new MediaPlayer();
                currentMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                currentMediaPlayer.setDataSource(tempFile.getAbsolutePath());
                
                currentMediaPlayer.setOnCompletionListener(mp -> {
                    Log.d(TAG, "playAudioStream: Audio playback completed");
                    stopPlayback();
                    // Clean up temp file
                    if (tempFile.exists()) {
                        tempFile.delete();
                    }
                });
                
                currentMediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                    stopPlayback();
                    return true;
                });
                
                currentMediaPlayer.prepare();
                currentMediaPlayer.start();
                Log.d(TAG, "playAudioStream: Audio started playing");
                
            } catch (Exception e) {
                Log.e(TAG, "Error playing audio", e);
                stopPlayback();
            }
        });
    }
    
    /**
     * Convert raw PCM data to WAV format with proper headers
     * Gemini returns PCM data: 24kHz, mono, 16-bit
     */
    private byte[] convertPcmToWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        Log.d(TAG, "convertPcmToWav: Converting " + pcmData.length + " bytes of PCM to WAV");
        
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        
        byte[] wavHeader = new byte[44];
        
        // WAV file header
        // "RIFF" chunk descriptor
        wavHeader[0] = 'R'; wavHeader[1] = 'I'; wavHeader[2] = 'F'; wavHeader[3] = 'F';
        
        // File size - 8 bytes (will be filled later)
        int fileSize = 36 + pcmData.length;
        wavHeader[4] = (byte) (fileSize & 0xff);
        wavHeader[5] = (byte) ((fileSize >> 8) & 0xff);
        wavHeader[6] = (byte) ((fileSize >> 16) & 0xff);
        wavHeader[7] = (byte) ((fileSize >> 24) & 0xff);
        
        // "WAVE" format
        wavHeader[8] = 'W'; wavHeader[9] = 'A'; wavHeader[10] = 'V'; wavHeader[11] = 'E';
        
        // "fmt " sub-chunk
        wavHeader[12] = 'f'; wavHeader[13] = 'm'; wavHeader[14] = 't'; wavHeader[15] = ' ';
        
        // Sub-chunk size (16 for PCM)
        wavHeader[16] = 16; wavHeader[17] = 0; wavHeader[18] = 0; wavHeader[19] = 0;
        
        // Audio format (1 for PCM)
        wavHeader[20] = 1; wavHeader[21] = 0;
        
        // Number of channels
        wavHeader[22] = (byte) channels; wavHeader[23] = 0;
        
        // Sample rate
        wavHeader[24] = (byte) (sampleRate & 0xff);
        wavHeader[25] = (byte) ((sampleRate >> 8) & 0xff);
        wavHeader[26] = (byte) ((sampleRate >> 16) & 0xff);
        wavHeader[27] = (byte) ((sampleRate >> 24) & 0xff);
        
        // Byte rate
        wavHeader[28] = (byte) (byteRate & 0xff);
        wavHeader[29] = (byte) ((byteRate >> 8) & 0xff);
        wavHeader[30] = (byte) ((byteRate >> 16) & 0xff);
        wavHeader[31] = (byte) ((byteRate >> 24) & 0xff);
        
        // Block align
        wavHeader[32] = (byte) blockAlign; wavHeader[33] = 0;
        
        // Bits per sample
        wavHeader[34] = (byte) bitsPerSample; wavHeader[35] = 0;
        
        // "data" sub-chunk
        wavHeader[36] = 'd'; wavHeader[37] = 'a'; wavHeader[38] = 't'; wavHeader[39] = 'a';
        
        // Data size
        wavHeader[40] = (byte) (pcmData.length & 0xff);
        wavHeader[41] = (byte) ((pcmData.length >> 8) & 0xff);
        wavHeader[42] = (byte) ((pcmData.length >> 16) & 0xff);
        wavHeader[43] = (byte) ((pcmData.length >> 24) & 0xff);
        
        // Combine header and data
        byte[] wavData = new byte[wavHeader.length + pcmData.length];
        System.arraycopy(wavHeader, 0, wavData, 0, wavHeader.length);
        System.arraycopy(pcmData, 0, wavData, wavHeader.length, pcmData.length);
        
        Log.d(TAG, "convertPcmToWav: WAV file created, total size: " + wavData.length + " bytes");
        return wavData;
    }
    
    public void stopPlayback() {
        if (currentMediaPlayer != null) {
            if (currentMediaPlayer.isPlaying()) {
                currentMediaPlayer.stop();
            }
            cleanup();
        }
    }
    
    private void cleanup() {
        if (currentMediaPlayer != null) {
            currentMediaPlayer.release();
            currentMediaPlayer = null;
        }
    }
    
    public void onDestroy() {
        Log.d(TAG, "onDestroy called");
        stopPlayback();
        if (executor != null && !executor.isShutdown()) {
            Log.d(TAG, "Shutting down executor");
            executor.shutdown();
        }
    }
} 