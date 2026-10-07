# Jarvis Desktop Assistant

Jarvis is a JavaFX desktop assistant with an optional local AI backend, offline
speech controls, a read-only catalog of sibling projects, and multi-format file
text extraction. Its configurable automation runner executes Java, JavaScript,
Playwright, and Python suites without invoking a shell.

## Build and launch

Requirements: JDK 21 or newer and Maven 3.9 or newer.

```powershell
mvn verify
mvn javafx:run
```

The checked-in project Maven configuration is `.mvn/settings.xml`. It contains
only public Maven Central configuration; do not place credentials in it. Maven
repository credentials, if ever needed, belong in a user-owned Maven settings
file or environment-backed CI secret. Maven build parallelism is selected by the
build command (`-T 1C` in CI); `settings.xml` does not support configuring Maven's
thread count.

## AI API

The default integration targets the local Claw-compatible HTTP API:

- Health check: `GET http://127.0.0.1:5000/api/status`
- Prompt: `POST http://127.0.0.1:5000/api/prompt`
- Request: `{"prompt":"...","model":"local-gemini"}`
- Success response: JSON with an `output` field (the client also accepts `reply`)
- Optional authentication: `Authorization: Bearer ...`

Configure it in `src/main/resources/application.yaml` or override it with:

| Environment variable | Purpose |
| --- | --- |
| `JARVIS_API_ENDPOINT` | Prompt endpoint URL |
| `JARVIS_API_HEALTH_ENDPOINT` | Health-check URL |
| `JARVIS_API_PROTOCOL` | `claw`, `openai-compatible`, or `anthropic` |
| `JARVIS_API_MODEL` | Model identifier sent with each prompt |
| `JARVIS_API_KEY` | Optional token override; never save it in YAML |
| `JARVIS_PROJECTS_ROOT` | Root folder whose immediate project directories are cataloged |

For `openai-compatible`, configure the provider's `/v1/chat/completions`
endpoint and set `OPENAI_API_KEY` (or `JARVIS_API_KEY`). For Anthropic's
Messages API, configure `/v1/messages` and set `ANTHROPIC_API_KEY` (or
`JARVIS_API_KEY`). Provider requests, authentication headers, and response
parsing are selected by `JARVIS_API_PROTOCOL`.

The existing client used `/chat` and expected a `reply` field, which did not
match the current local dashboard's `/api/prompt` contract. The endpoint and
model are now configurable, requests run asynchronously so the UI remains
responsive, and HTTP/service errors are shown instead of being replaced with a
generic success-shaped answer. No provider API key is included in the repository.

## Read files and code

**Read file** uses Apache Tika's type detection and text extraction. This
includes source files such as Java, JavaScript/TypeScript and Python; plain
text, Markdown, JSON, XML and HTML; Microsoft Office/RTF documents; PDFs; and
image formats accepted by Tika. Extracted text is inserted into the prompt so
Jarvis can analyze it with the configured AI service.

Scanned PDFs, images, and images embedded in documents require the Tesseract
OCR executable and the configured language data to be installed on the
machine. OCR is enabled for PDF extraction in `application.yaml` and can be
disabled there. If Tika does not return text for a PDF or common image format,
the optional Python fallback uses PyMuPDF, Pillow, and pytesseract. Install
`tools/python/requirements.txt`, Python 3.11+, Tesseract, and the requested
language data to enable it. The fallback is bounded by file, page, pixel,
character, and process-time limits. The default upload cap is 32 MiB and
extracted text is capped at 200,000 characters. Unsupported, encrypted,
oversized, or text-empty files return a visible error; no desktop app can
reliably extract content from every possible file format.

## Polyglot testing and reports

`src/main/resources/automation.yaml` defines shell-free Java, JavaScript,
Playwright, and Python suites with timeouts and bounded output. Use **Run tests**
in the desktop tool to select a suite. Each execution writes a JSON report under
`${user.home}/.jarvis/reports/automation`; captured output is capped and provider
credentials are stripped from the child-process environment. Node Playwright
tests use the API request runner and a local mock health endpoint, so they do
not require a browser download or a live AI service.

Install and run the language-specific checks:

```powershell
npm ci --prefix tools/javascript
npm test --prefix tools/javascript
npm run test:e2e --prefix tools/javascript
python -m pip install -r tools/python/requirements.txt
python -m unittest discover -s tools/python/tests -v
mvn --settings .mvn/settings.xml -T 1C verify
```

## Project discovery and reuse

`workspace-projects.yaml` records the read-only integration map for projects
found in the sibling `IdeaProjects` folder. **Scan projects** counts supported
Java, Kotlin, JavaScript, TypeScript, Python, Rust, and Go files while skipping
build output, vendored libraries, virtual environments, and worktree checkouts.
It reports an inventory only; source files and credentials are not copied into
Jarvis, and the current Jarvis project is excluded from its sibling inventory.
Use **Read file** to intentionally attach a specific source or document.

The survey informed original integrations for configurable providers and
document/OCR workflows. The Claw Code repository identifies some source as
leaked third-party code; Jarvis interoperates only at the HTTP API boundary and
does not include or reproduce that source.

## Local data and privacy

Chat history is stored under `${user.home}/.jarvis` in a SQLite database and a
plain-text export. Prompts, including text extracted from selected files, are
sent only to the endpoint configured by the user. Avoid sending confidential
documents to a cloud endpoint unless authorized to do so.
