import http from "node:http";

const port = Number(process.env.PORT || 8787);
const host = process.env.HOST || "127.0.0.1";
const geminiKey = process.env.GEMINI_API_KEY;
const groqKey = process.env.GROQ_API_KEY;
const maxJsonBytes = 64 * 1024;
const maxAudioBytes = 25 * 1024 * 1024;
const rateLimit = new Map();
const windowMs = 60_000;
const maxRequestsPerWindow = 30;

if (!geminiKey || !groqKey) {
  throw new Error("GEMINI_API_KEY and GROQ_API_KEY are required");
}

setInterval(() => {
  const now = Date.now();
  for (const [key, entry] of rateLimit) {
    if (now - entry.startedAt >= windowMs) {
      rateLimit.delete(key);
    }
  }
}, windowMs).unref();

function clientAddress(request) {
  return request.socket.remoteAddress || "unknown";
}

function allowed(request) {
  const now = Date.now();
  const key = clientAddress(request);
  const entry = rateLimit.get(key);
  if (!entry || now - entry.startedAt >= windowMs) {
    rateLimit.set(key, {startedAt: now, count: 1});
    return true;
  }
  entry.count += 1;
  return entry.count <= maxRequestsPerWindow;
}

function send(response, status, body) {
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(JSON.stringify(body));
}

async function body(request, limit) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > limit) {
      throw new Error("request too large");
    }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

async function providerRequest(url, init) {
  const result = await fetch(url, init);
  const text = await result.text();
  if (!result.ok) {
    console.error("AI provider returned", result.status);
    throw new Error("provider request failed");
  }
  return JSON.parse(text);
}

async function transcribe(request, response) {
  const audio = await body(request, maxAudioBytes);
  if (!audio.length) {
    return send(response, 400, {error: "empty audio"});
  }
  const contentType = request.headers["content-type"] === "video/mp4" ? "video/mp4" : "audio/ogg";
  const form = new FormData();
  form.append("model", "whisper-large-v3-turbo");
  form.append("response_format", "json");
  form.append("file", new Blob([audio], {type: contentType}), contentType === "video/mp4" ? "video.mp4" : "voice.ogg");
  const result = await providerRequest("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: {Authorization: `Bearer ${groqKey}`},
    body: form
  });
  send(response, 200, {text: String(result.text || "").trim()});
}

async function tts(request, response) {
  let input;
  try {
    input = JSON.parse((await body(request, maxJsonBytes)).toString("utf8"));
  } catch {
    return send(response, 400, {error: "invalid JSON"});
  }
  if (typeof input.text !== "string" || !input.text.trim() || input.text.length > 10_000) {
    return send(response, 400, {error: "text must be 1-10000 characters"});
  }
  const result = await providerRequest("https://generativelanguage.googleapis.com/v1beta/interactions", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "x-goog-api-key": geminiKey,
      "Api-Revision": "2026-05-20"
    },
    body: JSON.stringify({
      model: "gemini-3.1-flash-tts-preview",
      input: "Прочитай естественно и ясно: " + input.text,
      response_format: {type: "audio"},
      generation_config: {speech_config: [{voice: "Kore"}]}
    })
  });
  send(response, 200, result);
}

const server = http.createServer(async (request, response) => {
  try {
    if (request.method !== "POST") {
      return send(response, 405, {error: "method not allowed"});
    }
    if (!allowed(request)) {
      return send(response, 429, {error: "rate limit exceeded"});
    }
    if (request.url === "/v1/transcriptions") {
      return await transcribe(request, response);
    }
    if (request.url === "/v1/tts") {
      return await tts(request, response);
    }
    send(response, 404, {error: "not found"});
  } catch (error) {
    if (error.message === "request too large") {
      return send(response, 413, {error: "request too large"});
    }
    console.error("AI proxy request failed", error.message);
    send(response, 502, {error: "AI provider unavailable"});
  }
});

server.listen(port, host, () => {
  console.log(`AI proxy listening on http://${host}:${port}`);
});
