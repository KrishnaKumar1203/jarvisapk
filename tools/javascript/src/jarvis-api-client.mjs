export class JarvisApiClient {
  constructor(baseUrl = process.env.JARVIS_TOOLKIT_URL ?? "http://127.0.0.1:8080", fetchImpl = fetch) {
    let parsed;
    try {
      parsed = new URL(baseUrl);
    } catch (error) {
      throw new TypeError("Jarvis API base URL must be an absolute HTTP(S) URL.", { cause: error });
    }
    if (!["http:", "https:"].includes(parsed.protocol) || !parsed.hostname) {
      throw new TypeError("Jarvis API base URL must use HTTP or HTTPS.");
    }
    if (typeof fetchImpl !== "function") {
      throw new TypeError("A Fetch-compatible transport is required.");
    }
    this.baseUrl = parsed;
    this.fetch = fetchImpl;
  }

  async health({ signal, timeoutMs = 10_000 } = {}) {
    if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) {
      throw new RangeError("timeoutMs must be a positive safe integer.");
    }
    const timeoutSignal = AbortSignal.timeout(timeoutMs);
    const requestSignal = signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal;
    const endpoint = new URL("/api/health", this.baseUrl);
    const response = await this.fetch(endpoint, {
      method: "GET",
      headers: { Accept: "application/json" },
      signal: requestSignal,
    });
    const body = await response.text();
    let parsed;
    try {
      parsed = body ? JSON.parse(body) : {};
    } catch (error) {
      throw new Error(`Jarvis API returned invalid JSON (HTTP ${response.status}).`, { cause: error });
    }
    if (!response.ok) {
      const detail = typeof parsed.error === "string" ? parsed.error : response.statusText;
      throw new Error(`Jarvis API health request failed (HTTP ${response.status}): ${detail}`);
    }
    return parsed;
  }
}
