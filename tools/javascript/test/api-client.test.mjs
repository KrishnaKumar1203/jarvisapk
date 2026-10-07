import assert from "node:assert/strict";
import { test } from "node:test";

const clientUrl = new URL("../src/jarvis-api-client.mjs", import.meta.url);
const { JarvisApiClient } = await import(clientUrl);

test("API client returns structured health status", async () => {
  const client = new JarvisApiClient("http://127.0.0.1:8080", async () => new Response(
    JSON.stringify({ status: "ok" }),
    { status: 200, headers: { "content-type": "application/json" } },
  ));

  assert.deepEqual(await client.health(), { status: "ok" });
});

test("API client surfaces non-success response details", async () => {
  const client = new JarvisApiClient("http://127.0.0.1:8080", async () => new Response(
    JSON.stringify({ error: "unavailable" }),
    { status: 503, headers: { "content-type": "application/json" } },
  ));

  await assert.rejects(client.health(), /HTTP 503.*unavailable/);
});

test("API client rejects malformed base URLs before fetch", () => {
  assert.throws(() => new JarvisApiClient("file:///tmp"), /HTTP or HTTPS/);
});
