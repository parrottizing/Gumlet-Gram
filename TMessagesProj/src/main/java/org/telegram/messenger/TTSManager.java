package org.telegram.messenger;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

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
    private MessageObject currentTTSMessageObject;
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
                                
                                AndroidUtilities.runOnUIThread(() -> playAudioAsVoiceMessage(audioData, text));
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
    
    private void playAudioAsVoiceMessage(byte[] audioData, String originalText) {
        Log.d(TAG, "playAudioAsVoiceMessage: Starting audio playback with " + audioData.length + " bytes");
        AndroidUtilities.runOnUIThread(() -> {
            try {
                // Get current account
                int currentAccount = UserConfig.selectedAccount;
                
                // Create temporary OGG file for audio data
                java.io.File tempFile = java.io.File.createTempFile("tts_audio", ".ogg", ApplicationLoader.applicationContext.getCacheDir());
                Log.d(TAG, "playAudioAsVoiceMessage: Created temp file: " + tempFile.getAbsolutePath());
                
                // Convert raw PCM to OGG/Opus format
                byte[] oggData = convertPcmToOgg(audioData, 24000, 1, 16);
                
                // Write OGG data to file
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile)) {
                    fos.write(oggData);
                    Log.d(TAG, "playAudioAsVoiceMessage: OGG data written to file");
                }
                
                // Create a MessageObject for TTS audio to integrate with voice message player
                TLRPC.TL_document document = new TLRPC.TL_document();
                document.id = 0;
                document.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
                document.mime_type = "audio/ogg";
                document.size = oggData.length;
                document.dc_id = 0;
                document.file_reference = new byte[0];
                
                // Add audio attribute
                TLRPC.TL_documentAttributeAudio audioAttribute = new TLRPC.TL_documentAttributeAudio();
                audioAttribute.voice = true;
                audioAttribute.duration = estimateAudioDuration(audioData.length, 24000, 1, 16);
                audioAttribute.title = "TTS: " + (originalText.length() > 50 ? originalText.substring(0, 50) + "..." : originalText);
                
                // Generate waveform for the audio
                audioAttribute.waveform = generateWaveformFromPcm(audioData, 24000);
                if (audioAttribute.waveform != null) {
                    audioAttribute.flags |= 4;
                }
                
                document.attributes.add(audioAttribute);
                
                // Create MessageObject with proper structure like voice messages
                TLRPC.TL_message message = new TLRPC.TL_message();
                message.id = (int) (System.currentTimeMillis() / 1000); // Unique ID based on timestamp
                message.date = document.date;
                message.message = "";
                message.out = true;
                message.attachPath = tempFile.getAbsolutePath();
                
                // Set proper peer IDs for TTS (use "Saved Messages" dialog)
                long currentUserId = UserConfig.getInstance(currentAccount).getClientUserId();
                message.dialog_id = currentUserId; // Use self dialog for TTS
                message.peer_id = new TLRPC.TL_peerUser();
                message.peer_id.user_id = currentUserId;
                message.from_id = new TLRPC.TL_peerUser();
                message.from_id.user_id = currentUserId;
                
                // Set up media
                message.media = new TLRPC.TL_messageMediaDocument();
                message.media.document = document;
                message.media.voice = true; // Mark as voice message
                
                // Set proper flags
                message.flags |= TLRPC.MESSAGE_FLAG_HAS_MEDIA | TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
                
                currentTTSMessageObject = new MessageObject(currentAccount, message, false, false);
                
                // Mark message object properties for voice message behavior
                currentTTSMessageObject.attachPathExists = true;
                currentTTSMessageObject.isOutOwnerCached = true;
                
                Log.d(TAG, "playAudioAsVoiceMessage: Playing with MediaController as voice message");
                
                // Use MediaController to play as voice message (will use existing voice message player)
                boolean success = MediaController.getInstance().playMessage(currentTTSMessageObject);
                
                if (!success) {
                    Log.e(TAG, "Failed to play TTS audio with MediaController");
                    // Fallback: clean up the temp file
                    tempFile.delete();
                    currentTTSMessageObject = null;
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error playing TTS audio as voice message", e);
            }
        });
    }
    
    /**
     * Convert raw PCM data to OGG/Opus format
     * This is a simplified approach - ideally we'd use native Opus encoding
     * For now, we'll create a basic OGG container with the PCM data
     */
    private byte[] convertPcmToOgg(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        Log.d(TAG, "convertPcmToOgg: Converting " + pcmData.length + " bytes of PCM to OGG/Opus");
        
        // For now, we'll use WAV format but change the extension to .ogg
        // This is a temporary solution - proper Opus encoding would require native code
        return convertPcmToWav(pcmData, sampleRate, channels, bitsPerSample);
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
    
    /**
     * Estimate audio duration from PCM data
     */
    private double estimateAudioDuration(int dataSize, int sampleRate, int channels, int bitsPerSample) {
        int bytesPerSample = bitsPerSample / 8;
        int totalSamples = dataSize / (channels * bytesPerSample);
        return (double) totalSamples / sampleRate;
    }
    
    /**
     * Generate a simple waveform from PCM data for voice message visualization
     */
    private byte[] generateWaveformFromPcm(byte[] pcmData, int sampleRate) {
        try {
            // Convert bytes to 16-bit samples
            short[] samples = new short[pcmData.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) (((pcmData[i * 2 + 1] & 0xff) << 8) | (pcmData[i * 2] & 0xff));
            }
            
            // Use MediaController's native waveform generation
            return MediaController.getInstance().getWaveform2(samples, samples.length);
        } catch (Exception e) {
            Log.e(TAG, "Error generating waveform", e);
            return null;
        }
    }
    
    public void stopPlayback() {
        if (currentTTSMessageObject != null) {
            // Stop using MediaController only if this is our TTS message
            MessageObject currentlyPlaying = MediaController.getInstance().getPlayingMessageObject();
            if (currentlyPlaying != null && currentlyPlaying == currentTTSMessageObject) {
                MediaController.getInstance().cleanupPlayer(true, true);
            }
            
            // Clean up temp file with delay to prevent crashes during speed changes
            String attachPath = currentTTSMessageObject.messageOwner.attachPath;
            if (attachPath != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        java.io.File tempFile = new java.io.File(attachPath);
                        if (tempFile.exists()) {
                            tempFile.delete();
                            Log.d(TAG, "Cleaned up TTS temp file: " + attachPath);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error cleaning up temp file", e);
                    }
                }, 1000); // Delay cleanup by 1 second to allow playback to finish
            }
            
            currentTTSMessageObject = null;
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