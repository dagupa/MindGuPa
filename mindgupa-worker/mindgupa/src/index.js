const MAX_BODY_BYTES = 6 * 1024 * 1024;
const MAX_IMAGE_B64 = 5 * 1024 * 1024;
const EMAIL_RE = /^[^\s@<>()]+@[^\s@<>()]+\.[^\s@<>()]+$/;

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}

function safeEqual(a, b) {
  const enc = new TextEncoder();
  const x = enc.encode(a);
  const y = enc.encode(b);
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) {
    diff |= (x[i] || 0) ^ (y[i] || 0);
  }
  return diff === 0;
}

export default {
  async fetch(request, env) {
    if (request.method !== "POST") return json({ error: "method_not_allowed" }, 405);

    const key = request.headers.get("X-MindGuPa-Key") || "";
    if (!env.MINDGUPA_KEY || !safeEqual(key, env.MINDGUPA_KEY)) {
      return json({ error: "unauthorized" }, 401);
    }

    const declared = Number(request.headers.get("Content-Length") || 0);
    if (declared > MAX_BODY_BYTES) return json({ error: "payload_too_large" }, 413);

    let body;
    try {
      body = await request.json();
    } catch (e) {
      return json({ error: "invalid_json" }, 400);
    }

    const rawTo = Array.isArray(body.to) ? body.to : [body.to];
    const to = rawTo
      .filter((v) => typeof v === "string")
      .map((v) => v.trim())
      .filter((v) => EMAIL_RE.test(v));
    if (to.length < 1 || to.length > 3) return json({ error: "invalid_recipient" }, 400);

    const text = typeof body.text === "string" ? body.text.trim() : "";
    const image = typeof body.image === "string" ? body.image : "";
    if (!text && !image) return json({ error: "empty_message" }, 400);
    if (text.length > 50000) return json({ error: "text_too_long" }, 413);

    let subject = typeof body.subject === "string" ? body.subject : "MindGuPa";
    subject = subject.replace(/[\r\n]+/g, " ").trim().slice(0, 200) || "MindGuPa";

    const payload = {
      sender: { name: env.SENDER_NAME || "MindGuPa", email: env.SENDER_EMAIL },
      to: to.map((email) => ({ email })),
      subject,
      textContent: text || "Foto adjunta enviada desde MindGuPa.",
    };

    if (image) {
      if (image.length > MAX_IMAGE_B64 || !/^[A-Za-z0-9+/=]+$/.test(image)) {
        return json({ error: "invalid_image" }, 400);
      }
      payload.attachment = [{ content: image, name: "mindgupa-" + Date.now() + ".jpg" }];
    }

    let res;
    try {
      res = await fetch("https://api.brevo.com/v3/smtp/email", {
        method: "POST",
        headers: {
          "api-key": env.BREVO_API_KEY,
          "Content-Type": "application/json",
          Accept: "application/json",
        },
        body: JSON.stringify(payload),
      });
    } catch (e) {
      return json({ error: "upstream_unreachable" }, 502);
    }

    if (!res.ok) return json({ error: "brevo_error", status: res.status }, 502);
    return json({ ok: true }, 200);
  },
};
