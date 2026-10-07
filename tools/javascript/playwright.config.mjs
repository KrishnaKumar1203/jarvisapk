import { defineConfig } from "@playwright/test";

const baseURL = process.env.JARVIS_TOOLKIT_URL ?? "http://127.0.0.1:8080";
const parsedBaseURL = new URL(baseURL);

if (!["http:", "https:"].includes(parsedBaseURL.protocol)) {
  throw new Error("JARVIS_TOOLKIT_URL must use HTTP or HTTPS.");
}

export default defineConfig({
  testDir: "./test/e2e",
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  reporter: [
    ["list"],
    ["html", { outputFolder: "../../target/playwright-report", open: "never" }],
    ["json", { outputFile: "../../target/playwright-results.json" }],
  ],
  use: {
    baseURL: parsedBaseURL.origin,
    extraHTTPHeaders: { Accept: "application/json" },
    trace: "retain-on-failure",
  },
});
