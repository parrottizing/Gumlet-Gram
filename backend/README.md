# Gumlet Gram AI proxy

This small Node.js service keeps `GEMINI_API_KEY` and `GROQ_API_KEY` off the Android APK.

1. Copy `backend/.env.example` to a private environment file and set both provider keys.
2. Start it with `node backend/server.mjs`.
3. Set `AI_PROXY_URL` in the Android build environment, for example `https://ai.example.com`.

The service accepts `POST /v1/tts` and `POST /v1/transcriptions`. It limits request sizes and requests per IP, and never logs request bodies. Put it behind HTTPS and a production rate-limit/authentication layer before exposing it publicly.
