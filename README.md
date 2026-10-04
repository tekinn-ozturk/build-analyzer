# Build Analyzer

> **Note for an AI assistant / new session:** this README is the single source of truth
> for understanding the project without reading every file. It is kept in sync with the
> code by hand. Read it fully first; only open individual files when you need to change them.

AI-assisted **Jenkins build failure analyzer** for a Turkish-speaking QA team. A REST API that

1. pulls a Jenkins build's console log,
2. distills it into a small, structured context (status, scenario, failing step, full exception
   block, failing code location, failing UI command/locator, relevant log lines, last 200 lines),
3. classifies the error family,
4. builds a short, directive **Turkish** prompt (structured evidence first), and
5. asks **OpenAI GPT-5 mini** for a short root-cause analysis.

The response contains the extracted fields, the prompt, the model's answer (`aiAnalysis`) and the
usage statistics of the call (provider, model, tokens, duration, estimated cost). SUCCESS builds are
answered instantly without calling the LLM.

## Tech stack
- Java 21, Spring Boot 3.3.5, Maven, Lombok
- Architecture: **Hexagonal (Ports & Adapters)**, **LLM-provider independent**
- Jenkins access: Spring `RestClient` (HTTP Basic auth, credentials from environment)
- LLM: **OpenAI Responses API** (`POST /v1/responses`), default model **`gpt-5-mini`**, called with Spring `RestClient`
  (JDK `HttpClient` underneath). The provider sits behind the `LlmProvider` port; OpenAI is the only
  implementation today.
- springdoc-openapi (Swagger UI)
- **PostgreSQL** (Spring Data JPA, schema by **Flyway**) for the analysis history — one table, see [Database](#database).
- A small React frontend in `frontend/` (see [Frontend](#frontend)).

## Running locally

Prerequisites: a Jenkins on `http://localhost:8080` and an **OpenAI API key**. The app listens on
**port 8090**.

Secrets come **only from environment variables** — never from `application.yml`:

| Env var | Required | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | **yes** | OpenAI API key. Missing → the app refuses to start (`OPENAI_API_KEY environment variable is not set`). |
| `JENKINS_USERNAME` | yes for this Jenkins | anonymous access answers 403 |
| `JENKINS_API_TOKEN` | yes for this Jenkins | Jenkins → user menu → *Security* → *API Token* |
| `DB_PASSWORD` | **yes** | password of the PostgreSQL user |
| `DB_URL` | no | default `jdbc:postgresql://localhost:5432/build_analyzer` |
| `DB_USERNAME` | no | default `build_analyzer_user` |

- **IntelliJ (how the app is normally run):** Run configuration `BuildAnalyzerApplication` →
  *Edit Configurations…* → *Environment variables*:
  `OPENAI_API_KEY=<key>;JENKINS_USERNAME=<user>;JENKINS_API_TOKEN=<token>;DB_PASSWORD=<db password>` → restart the app
  (env is read at startup only).
- **First start:** Flyway creates the schema; the history starts empty. Nothing has to be set up by hand.
- **Terminal (PowerShell):**
  ```powershell
  $env:OPENAI_API_KEY = "<key>"
  $env:JENKINS_USERNAME = "<user>"
  $env:JENKINS_API_TOKEN = "<token>"
  mvn spring-boot:run
  ```
  (cmd.exe: `set "OPENAI_API_KEY=<key>"` etc.)

An OpenAI key is created at https://platform.openai.com/api-keys. Never commit it.

### Trying the endpoint
- **Swagger UI:** http://localhost:8090/swagger-ui/index.html → `POST /api/v1/analysis/build` → *Try it out*.
- **Postman:** `POST http://localhost:8090/api/v1/analysis/build`, Body → raw → JSON.

### Tests
`mvn test` — **149 unit tests, all green**, no external service or API key needed (OpenAI is stubbed with an
in-process HTTP server, Jenkins is mocked, the controller test mocks the repository). Plus two opt-in groups,
skipped by default:
- `AnalysisRepositoryTest` (3 tests: stored columns, history list, detail without prompt) runs against the
  **real local PostgreSQL** when `DB_PASSWORD` is set; every test is rolled back, nothing stays in the database;
- the live OpenAI test over 4 logs (`-Dopenai.live=true`).

After deleting/renaming classes run `mvn clean test` so stale `.class` files in `target/` are not picked up.

## Database

One table, **`analyses`** — one analysed Jenkins build. Schema by Flyway (`src/main/resources/db/migration`);
Hibernate only validates it (`ddl-auto: validate`).

- `V1__init_schema.sql` created users / projects / project_members / pipelines / analyses;
  `V2__drop_projects_and_pipelines.sql` removed everything but `analyses` (V1 product decision: no users,
  projects or pipelines — the build URL alone identifies an analysis). Never edit an applied migration; add a new one.
- Columns: Jenkins (`build_url, job_name, job_path, build_number, build_status`), history list
  (`analysis_status, error_category, exception_type, headline`), result (`error_reason, result_json,
  relevant_logs, last_200_lines`), LLM (`model, total_tokens, estimated_cost_usd`), `created_at, analyzed_at`.
- `result_json` (TEXT, Jackson) is the analysis response without the prompt and the logs; the logs have their
  own columns. **The prompt and the full console log are not stored** (the log stays on Jenkins).
- `analysis_status`: `COMPLETED` · `SKIPPED` (SUCCESS build, no LLM call) · `UNSTRUCTURED` (model answer not parseable).
- No authentication: everybody who can reach the app sees the whole history.

## Frontend

`frontend/` — React 19, TypeScript, Vite, MUI, React Router (backend calls with `fetch`). Kept deliberately
small (lean V1); the long frontend design document is the product vision, not the implemented scope.
All backend calls are in `src/api/analysisApi.ts`.

```
cd frontend
npm install
npm run dev      # http://localhost:5173 — /api is proxied to the backend on :8090 (no CORS needed)
npm test         # Vitest: URL parser, response mapping, AnalyzeForm, AiAnalysis render rules
npm run build    # type check + production bundle in frontend/dist
```

Flow: `DashboardPage` (URL → `AnalyzeForm` → "Build analiz ediliyor..." → `POST /api/v1/analysis/build`) →
`AnalysisPage` (`/analyses/:id` → `GET /api/v1/analyses/{id}`, tabs AI Analysis / Relevant Logs) ·
`HistoryList` (`GET /api/v1/analyses`) on the dashboard and on `/history`.

- **AI Analysis** = Hata Sebebi → where it failed → Çözüm Önerileri.
- **The only render rule for "where" is `testContext`** (`types/analysis.ts`): not null → IDE-like view
  (`TestContextView`: feature file, scenario, failed step and its line); null → plain error summary card
  (Maven / Jenkins / pipeline / infrastructure failures). The frontend knows no test framework; every
  `testContext` field is optional. The backend sets it when the log shows a failing scenario or step.
- The URL parser (`utils/jenkinsUrl.ts`) supports nested folders: `/job/Team/job/UI-Test/125/` → `Team/UI-Test`.

## API

`POST /api/v1/analysis/build`

```json
{ "buildUrl": "http://localhost:8080/job/Mini-UI-Automation/7/" }
```
`buildUrl` is parsed by `domain/model/JenkinsBuildUrl` into the job path (Jenkins full name, folders joined
with "/": `/job/Team/job/UI-Test/125/` → `Team/UI-Test`) and the build number; trailing parts like `/console`
are ignored. Blank → 400; not a Jenkins build URL (no `/job/<name>/<number>/`, Blue Ocean, `lastFailedBuild`)
→ **400 before anything runs**. The log is always fetched from the configured `JENKINS_URL`; the URL's host is
not used for that. The analysis is stored and returned with its `id`.

| Endpoint | Returns |
|---|---|
| `POST /api/v1/analysis/build` | runs + stores the analysis; the response below (with `generatedPrompt`) |
| `GET /api/v1/analyses` | the history, newest first: `id, jobName, jobPath, buildNumber, buildUrl, buildStatus, analysisStatus, errorCategory, headline, analyzedAt` (no logs) |
| `GET /api/v1/analyses/{id}` | one stored analysis in the same shape as the POST response, **without `generatedPrompt`** (not stored); 404 if missing |

Response for the real failing build #7 (long values shortened; usage values from a real run on 2026-09-27):
```json
{
  "id": 1,
  "jobName": "Mini-UI-Automation",
  "buildNumber": 7,
  "buildUrl": "http://localhost:8080/job/Mini-UI-Automation/7/",
  "analyzedAt": "2026-10-04T09:42:00Z",
  "testContext": { "scenario": "Open Google", "step": "Arama kutusuna tıkla.", "featureFile": "src/test/resources/features/Example.feature", "featureLine": 6 },
  "buildStatus": "FAILURE",
  "failedScenario": "Open Google",
  "featureFile": "src/test/resources/features/Example.feature",
  "featureLine": 4,
  "failedStepText": "Arama kutusuna tıkla.",
  "failedStepLine": 6,
  "exceptionType": "org.openqa.selenium.NoSuchElementException",
  "errorCategory": "SELENIUM",
  "exceptionFile": "ExampleSteps.java",
  "exceptionLine": 28,
  "failedStepDefinition": "stepdefinitions.ExampleSteps.clickSearchBox",
  "automationTool": "Selenium",
  "failedCommand": "findElement",
  "locatorType": "name",
  "locatorValue": "qqqqqqqq",
  "stackTrace": "org.openqa.selenium.NoSuchElementException: no such element ... Build info ... Driver info ... Command ... Capabilities ... Session ID ... at ...",
  "relevantLogSnippet": "Scenario: Open Google ... * Arama kutusuna tıkla. ... [INFO] BUILD FAILURE ... Finished: FAILURE",
  "last200Lines": "<raw log tail>",
  "generatedPrompt": "Sen bir Jenkins build hatası karar destek sistemisin. ...",
  "aiAnalysis": "🚨 KÖK NEDEN\nname=qqqqqqqq locator'ı bulunamadı; org.openqa.selenium.NoSuchElementException atıldı.\n📍 KONUM\nExampleSteps.java:28\nclickSearchBox()\n✅ AKSİYON\n- ExampleSteps.java satır 28'deki findElement(using=name, value=qqqqqqqq) locator değerini düzelt.\n- ...",
  "rootCauseAnalysis": {
    "rootCause": "name=qqqqqqqq locator'ı bulunamadı; org.openqa.selenium.NoSuchElementException atıldı.",
    "file": "ExampleSteps.java",
    "line": 28,
    "method": "clickSearchBox",
    "actions": ["ExampleSteps.java satır 28'deki findElement(using=name, value=qqqqqqqq) locator değerini düzelt.", "..."]
  },
  "provider": "OPENAI",
  "model": "gpt-5-mini-2025-08-07",
  "promptTokens": 5880,
  "completionTokens": 574,
  "totalTokens": 6454,
  "responseTimeMs": 5968,
  "estimatedCostUsd": 0.002618
}
```

- Every extracted field is best-effort: `null` means "not found", never a guess.
- `aiAnalysis` is the short, ready-to-show text (format below), rendered by the backend from the model's
  JSON answer. `rootCauseAnalysis` carries the same content as fields for clients that render it
  themselves. If the model's answer cannot be parsed, `aiAnalysis` is the raw answer and
  `rootCauseAnalysis` is `null`.
- `model` is what OpenAI reports (a dated snapshot of the configured model).
- `promptTokens` / `completionTokens` / `totalTokens` are the Responses API's `input_tokens` / `output_tokens` / `total_tokens`; `completionTokens` includes GPT-5's hidden reasoning tokens (billed as output).
- `estimatedCostUsd` = promptTokens × input price + completionTokens × output price (configured list
  prices per 1M tokens, 6 decimals). Cached-input discounts are ignored, so it is an upper estimate.
- `responseTimeMs` is the wall-clock time of the LLM call only (not the Jenkins fetch).
- **SUCCESS build:** no prompt is built and the LLM is not called — `errorCategory` = `NONE`,
  error fields `null`, `generatedPrompt`, `rootCauseAnalysis` and all usage fields = `null`,
  `aiAnalysis` = "Build başarıyla tamamlandı. Analiz gerektiren bir hata tespit edilmedi."
- **Errors:** 400 validation; Jenkins non-2xx → same status (e.g. 401/403 = bad/missing credentials,
  404 = no such job/build); Jenkins unreachable → 502; OpenAI unreachable / timeout / non-2xx (e.g. 401
  wrong key, 429 rate limit/quota) / empty or refused answer → 502 `{"error": "AI analysis failed: ..."}`.

### Output philosophy and format
Users (QA Engineer, QA Lead, SDET, Developer, Team Lead) must answer *"Neden patladı? Nerede patladı?
Ne yapmalıyım?"* in ~10 seconds. The model acts as a **decision-support system, not a report writer**:
no technical explanation section, no tutorials ("Selenium nedir"), no speculation the evidence does not
show ("iframe olabilir", "cache", "wait eklenmeli"), a specific root cause ("name=qqqqqqqq locator'ı
bulunamadı", not "Element bulunamadı"), actions derived directly from the evidence. No confidence level
(dropped by product decision).

1. The model answers **JSON only**, enforced by the provider (OpenAI: strict structured output):
   ```json
   {"rootCause": "...", "file": "ExampleSteps.java", "line": 28, "method": "clickSearchBox", "actions": ["..."]}
   ```
   Unknown location parts are `null`.
2. `RootCauseParser` normalises it (file name only — path/package/`:line` removed; simple method name;
   ≤ 3 non-blank actions; one line per value) and `RootCauseAnalysis.withFallbackLocation` fills a missing
   file/line/method from the parser's evidence.
3. `RootCauseFormatter` renders it — **at most 10 lines** (at most 9 in practice); sections with nothing
   proven are omitted:
   ```
   🚨 KÖK NEDEN
   name=qqqqqqqq locator'ı bulunamadı; org.openqa.selenium.NoSuchElementException atıldı.
   📍 KONUM
   ExampleSteps.java:28
   clickSearchBox()
   ✅ AKSİYON
   - ExampleSteps.java satır 28'deki findElement(using=name, value=qqqqqqqq) locator değerini düzelt.
   - stepdefinitions.ExampleSteps.clickSearchBox metotunda kullanılan name locator'ı geçerli name değeriyle güncelle.
   ```

**Measured on 2026-09-27** (gpt-5-mini, `OpenAiLiveBuild7Test`): build #7 plus three synthetic sample logs
(`src/test/resources/logs/samples/`: Selenium timeout, REST assertion 201≠500, Maven compile error). All four
gave the right file:line, a specific root cause in Turkish, 2–3 actions, no speculation; 3–6 s and
$0.0011–0.0026 per analysis. Weak spot: a third action is sometimes generic ("… kontrol et").

## How a request flows

```
POST /api/v1/analysis/build
  → BuildAnalysisController
    → JenkinsBuildUrl.parse(buildUrl) → job path + build number   (400 if not a Jenkins build URL)
    → AnalyzeBuildUseCase (impl: AnalyzeBuildService)
        → BuildSourcePort (out) → JenkinsBuildSourceAdapter → JenkinsHttpClient
              → GET /job/{job}/{build}/consoleText  (raw bytes → ConsoleLogDecoder)
        → BuildContextBuilder → BuildAnalysisContext (domain)
              1. build status        (SUCCESS → category NONE, stop here)
              2. ExceptionBlockExtractor   exception type, FULL exception block, first application frame
              3. ScenarioExtractor         failing scenario (clean name, feature file, line)
              4. StepExtractor             failing step (text, feature file, line)
              5. InteractionExtractor[]    failing UI command + locator (Selenium, ...)
              6. ErrorClassifier           error category
              7. RelevantLogExtractor      relevant log snippet (noise removed)
              8. last 200 raw lines
        → SUCCESS? → fixed message, no prompt, no LLM call
        → PromptBuilder → prompt (Turkish: rules → evidence → snippet → last 200 lines → JSON answer format)
        → LlmProvider (out port) → OpenAiProvider → RestClient → POST {openai.url}/responses  [LlmRequest: prompt + RootCauseSchema]
              ← LlmCompletion (JSON text, provider, model, tokens, estimated cost)
        → measures the call → LlmMetrics
        → RootCauseParser (JSON → RootCauseAnalysis, normalised) → withFallbackLocation(evidence)
        → RootCauseFormatter → aiAnalysis (🚨 KÖK NEDEN / 📍 KONUM / ✅ AKSİYON, ≤ 10 lines)
        → BuildAnalysisResult (domain: context + prompt + aiAnalysis + rootCauseAnalysis + llmMetrics)
    → BuildAnalysisResponseMapper.toEntity → AnalysisRepository.save → PostgreSQL (analyses)
    → BuildAnalysisResponseMapper.toResponse → AnalyzeBuildResponse (DTO, flattened, with the stored id)

GET /api/v1/analyses        → AnalysisRepository.findAllByOrderByAnalyzedAtDesc()  (list columns only)
GET /api/v1/analyses/{id}   → AnalysisRepository.findById(id) → mapper.toResponse(entity)  (404 if missing)
```

The analysis core (`application`, `domain`) does not know the database; storing happens in the controller
after `AnalyzeBuildService` returns.

## LLM provider architecture

```
application (knows only the port)          infrastructure (one adapter per provider)
┌──────────────────────────────┐           ┌──────────────────────────────────────────┐
│ AnalyzeBuildService          │           │ llm/openai/OpenAiConfiguration           │
│   └─ LlmProvider (port)  ◄───┼───────────┤   @ConditionalOnProperty llm.provider=   │
│        complete(LlmRequest)  │           │     OPENAI (default)                     │
│        → LlmCompletion       │           │   → OpenAiProvider + OpenAiProperties    │
└──────────────────────────────┘           │ llm/TokenPricing (shared cost estimate)  │
                                           └──────────────────────────────────────────┘
```

- `llm.provider` (`LLM_PROVIDER`, default `OPENAI`, case-insensitive) selects the adapter. Exactly one
  `LlmProvider` bean exists; an inactive provider's properties (API key) are neither bound nor required.
- Application, domain and API never import a provider; `ArchitectureBoundaryTest` enforces this.
- **Adding a provider (e.g. Anthropic, or Ollama again)** = one new package
  `infrastructure/llm/<provider>/` with:
  1. `<Provider>Provider implements LlmProvider` — calls the API, returns `LlmCompletion`, wraps every
     transport error in `LlmAnalysisException` (never let `RestClientResponseException` escape: the API
     layer maps that type to Jenkins errors);
  2. `<Provider>Properties` (`@ConfigurationProperties("<provider>")`, `@Validated`, key from env only);
  3. `<Provider>Configuration` — `@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "<NAME>")`
     + `@EnableConfigurationProperties`, declares the provider bean;
  4. a `<provider>:` block in `application.yml` and an adapter test with an in-process HTTP stub.
  Nothing in application, domain, api or `PromptBuilder` changes.

## Package / class map
Base package: `com.company.buildanalyzer`

### domain (pure Java — no Spring, no framework imports)
- `model/BuildAnalysisContext` — record: buildStatus, `FailedScenario failedScenario`,
  `FailedStep failedStep`, exceptionType, `ErrorCategory errorCategory`, stackTrace (full block),
  `FailureLocation failureLocation`, `FailedInteraction failedInteraction`,
  relevantLogSnippet (LLM's secondary source), last200Lines (raw tail, LLM's supporting context).
  `isSuccessful()` / static `isSuccessStatus(String)` is the single source of truth for
  "SUCCESS, no analysis needed".
- `model/FailedScenario` — name, featureFile, featureLine. E.g. `Open Google`, `src/test/resources/features/Example.feature`, `4`.
- `model/FailedStep` — text, featureFile, featureLine. E.g. `Arama kutusuna tıkla.`, `Example.feature`, `6`.
- `model/FailureLocation` — stepDefinition, file, line. E.g. `stepdefinitions.ExampleSteps.clickSearchBox`, `ExampleSteps.java`, `28`.
- `model/FailedInteraction` — tool-neutral "command against a target": tool, command, locatorType,
  locatorValue. E.g. `Selenium`, `findElement`, `name`, `qqqqqqqq`
  (Playwright would be `toBeVisible` + `getByRole` ..., Cypress `get` + `#submit`).
- `model/LlmMetrics` — provider, model, promptTokens, completionTokens, totalTokens, responseTimeMs,
  estimatedCostUsd (`BigDecimal`). Token fields `null` when the provider does not report them.
- `model/BuildAnalysisResult` — `BuildAnalysisContext context`, generatedPrompt, aiAnalysis,
  `RootCauseAnalysis rootCauseAnalysis`, `LlmMetrics llmMetrics` (both `null` for SUCCESS). What the use case returns.
- `model/RootCauseAnalysis` — rootCause, file, line, method, actions (never null, immutable, max 3).
- `model/JenkinsBuildUrl` — `parse(url)` → jobPath (folders joined with "/"), buildNumber, normalised url;
  `IllegalArgumentException` for anything else (the controller answers 400).
  `withFallbackLocation(FailureLocation)` fills a missing file/line/method from the evidence.
- `model/ErrorCategory` — SELENIUM, MAVEN, CUCUMBER, JENKINS, INFRA, UNKNOWN, NONE (successful build).

### application (use cases + parsing/prompt logic — may use Spring stereotypes, depends only on domain and ports)
**Use case & ports**
- `usecase/AnalyzeBuildUseCase` — inbound port: `BuildAnalysisResult analyze(String jobName, int buildNumber)`.
- `usecase/AnalyzeBuildService` (@Service) — owns the flow; injects `BuildSourcePort`, `BuildContextBuilder`,
  `PromptBuilder`, `LlmProvider`, `RootCauseParser`, `RootCauseFormatter`. Log → context → SUCCESS short-circuit →
  prompt → `llmProvider.complete(new LlmRequest(prompt, RootCauseSchema.ROOT_CAUSE))`
  (timed) → `LlmMetrics` → parse + location fallback → render → result. No HTTP here, no provider knowledge.
- `port/out/BuildSourcePort` — `String fetchConsoleLog(String jobName, int buildNumber)`.
- `port/out/LlmProvider` — `LlmCompletion complete(LlmRequest request)` (+ `complete(String)` shortcut). The only LLM contract.
- `port/out/LlmRequest` — prompt + optional `LlmResponseSchema`; `port/out/LlmResponseSchema` — name + JSON schema as a
  plain map. Provider-neutral way to demand structured output; each adapter enforces it its own way.
- `port/out/LlmCompletion` — provider-neutral answer: text, provider, model, promptTokens,
  completionTokens, totalTokens, estimatedCostUsd.
- `port/out/LlmAnalysisException` — framework-free exception every LLM adapter throws for any failure, so
  no HTTP-client type leaks into application/api.

**Context extraction (`context/`)**
- `BuildContextBuilder` (@Service) — orchestrates the extractors below. Build status first (Jenkins
  `Finished: X`, last occurrence, wins over Maven `BUILD X`). SUCCESS → `NONE`, no error parsing,
  classifier not called (green logs contain harmless warnings such as Selenium's "Unable to find CDP
  implementation" that used to be classified SELENIUM). Blank log → status/category UNKNOWN. Keeps the
  last **200** raw lines.
- `ExceptionBlockExtractor` (package-private, static) — header = first line with a fully-qualified
  `...Exception|Error`. Keeps the **full** block, unclipped: all message lines (Selenium: Session info,
  Build info, System info, Driver info, Command, Capabilities, Session ID; max 30 as a runaway guard) and
  **every** frame (`at`, `Caused by:`, `Suppressed:`, `... n more`). Ends at a blank line, a new log
  record (`[INFO]`, `[ERROR]`, `[Pipeline]`, ...) or the first non-frame after frames. The **first
  application frame** (package not in `FRAMEWORK_PACKAGES`: java., jdk., org.openqa., io.cucumber.,
  org.junit., ...; source `X.java:N`) → `FailureLocation`. Fallback: Cucumber pretty step comment
  `# pkg.Class.method()` above the header (step definition only).
- `ScenarioExtractor` (package-private, static) — prefers the `Failed scenarios:` list; else the **last**
  `Scenario:` line before the exception (multi-scenario runs); else the first. Splits
  `Scenario: <name>  # <feature>:<line>` so `failedScenario` is only the name (`Open Google`, never
  `Open Google     # src/...feature:4`). Strips Cucumber's ANSI colour codes (coloured pretty output
  otherwise hid the `#` from the regex), accepts feature paths with spaces / `classpath:` / Windows
  paths, and `cleanName` removes any leftover location comment while keeping a `#` inside the name
  (`Order #12`). Scenario Outline/Template and Turkish `Senaryo` accepted.
- `StepExtractor` (package-private, static) — Cucumber-JVM's step pseudo-frame in the stack trace
  `at ✽.<step text>(<uri>/<file>.feature:<line>)` (`✽` often arrives as `?`) first; else the nearest
  pretty-formatter step line above the exception (`* / Given / When / Then / And / But` + Turkish
  `Diyelim ki / Eğer ki / O zaman / Ve / Fakat / Ama`), never crossing a Scenario header. No BDD output →
  null. Step text is kept verbatim (trailing "." included) so it can be searched in the feature file.
- `InteractionExtractor` (interface, strategy) — `Optional<FailedInteraction> extract(exceptionBlock)`.
  All implementations are injected as `List<InteractionExtractor>`; the first non-empty result wins.
  **Adding a tool = adding an implementation**; domain, prompt and API stay unchanged.
- `SeleniumInteractionExtractor` (@Component) — only for blocks containing `org.openqa.selenium`.
  `Command: [<session>, findElement {using=name, value=qqqqqqqq}]` → command + locator (value may contain
  commas); no `using=` → command only; no Command line → locator from the message
  `{"method":"css selector","selector":"..."}`.
- `RelevantLogExtractor` (@Service) — builds `relevantLogSnippet`. Technology-agnostic, data-driven rule lists:
  - **NOISE** (always dropped, wins over signals): dependency download/progress, copy/compile, git
    checkout, `[Pipeline]`, prefix-only/separator lines, Maven help footer.
  - **SIGNALS** (kept with 2 lines before + up to 12 after, until a blank line): generic failure words
    incl. CamelCase `...Exception` / `...Error`, fail/fatal/assert/expected/timeout/unable to/...,
    Turkish hata/başarısız, `at ...` and Python `File "...", line n` frames, ✘✖×❌.
  - **MARKERS** (kept alone): Scenario/Feature, `Tests run:`, `Finished:`, `BUILD SUCCESS/FAILURE`, "n passed/failed".
  - `WARN`/`WARNING` lines never trigger on their own, nor do their indented continuation lines unless
    those carry a **strong** signal (`...Exception`/`...Error`, fail, `at` frame, ✘).
  - Deduplicates (ignoring `[LEVEL]` prefixes/whitespace) and skips lines already in the stack-trace
    section; `...` marks gaps; lines clipped at 300 chars (`MAX_LINE_LENGTH`); cap 120 lines (1/3 head +
    2/3 tail); no signal at all → last 40 non-noise lines.
  - Tested on the real Selenium build #7 and on Cypress, Playwright, pytest, NUnit and Karate samples.
- `classifier/ErrorClassifier` (@Service) — rule-list based (no if-else chain): ordered
  `List<Rule{ErrorCategory, keyword Pattern}>`, first match wins, else UNKNOWN. Only called for non-SUCCESS builds.

**Prompt (`prompt/PromptBuilder`, @Service)** — text only, in Turkish, provider-agnostic; short and
directive. Order:
1. Role: Jenkins build failure **decision-support system**, not a report writer; answer "Neden / Nerede /
   Ne yapmalıyım?" readable in 10 s. Turkish, identifiers as-is.
2. **Source priority:** YAPISAL KANIT = primary, İLGİLİ LOG KESİTİ = secondary, SON 200 SATIR = supporting
   context only; on conflict trust the structured evidence.
3. **Rules:** only what the evidence shows — no speculation (examples named: iframe, app changed, cache,
   "wait eklenmeli"), no technology/framework guessing, no invented root cause, no technical explanation or
   tutorials ("Selenium/API/timeout nedir"); root cause must be specific (bad/good example given).
4. `=== 1. YAPISAL KANIT (Structured Evidence) ===` — one `- Etiket: değer` line per **non-null** fact
   (Build Durumu, Hata Kategorisi, Senaryo, Başarısız Adım, Step Definition, Hata Dosyası:Satırı,
   Exception, Otomasyon Aracı, Başarısız Komut, Locator Tipi, Locator Değeri, Kod Dili — derived from
   the exception file extension, not guessed) + the full `Stack Trace`.
5. `=== 2. İLGİLİ LOG KESİTİ (Relevant Log Snippet) ===`
6. `=== 3. JENKINS LOG — SON 200 SATIR ===` — raw tail so nothing the parser missed is lost; only lines
   over 500 chars (base64 screenshots, dumps) are clipped.
7. The JSON answer format — last, so it is what the model reads last: rootCause ≤ 2 sentences in Turkish;
   file = file name only, line = number, method = method name only, taken from the evidence, else `null`;
   ≤ 3 imperative actions derived from the evidence, no generic "incele / doğrula / tekrar çalıştır".
Empty sections are skipped. Build #7's prompt is ~20k chars (~6k tokens).

**Answer handling (`analysis/`)**
- `RootCauseSchema` — `LlmResponseSchema` of the answer: all five fields required,
  `additionalProperties: false`, `file`/`line`/`method` nullable (what strict structured outputs need).
  No `maxItems`: not every provider supports it, the parser enforces the limit.
- `RootCauseParser` (@Component) — JSON → `RootCauseAnalysis`; tolerant of a ```` ```json ```` fence or text
  around the object; normalises values (see *Output philosophy*). No JSON / no rootCause → empty
  (the service then shows the raw answer).
- `RootCauseFormatter` (@Component) — renders the ≤ 10-line text; `method()` gets `()` appended; sections
  without evidence are omitted.

### api (inbound adapter — Spring web)
- `controller/BuildAnalysisController` — the three endpoints (see "How a request flows").
- `dto/request/AnalyzeBuildRequest` — record `{ buildUrl }` + validation.
- `dto/response/AnalyzeBuildResponse` — flat record (see the API example above). Tool-neutral and
  provider-neutral names; `rootCauseAnalysis` is a nested `RootCauseAnalysisResponse` (rootCause, file, line,
  method, actions).
- `mapper/BuildAnalysisResponseMapper` (@Component) — flattens the domain value objects
  (`FailedScenario`, `FailedStep`, `FailureLocation`, `FailedInteraction`, `LlmMetrics`), maps
  `RootCauseAnalysis` to the nested DTO and converts
  `ErrorCategory` via `.name()`. Decides `testContext` (scenario or step found → set, else `null`).
  `toEntity` builds the `analyses` row (build URL, headline, analysis status, `result_json`);
  `toResponse(Analysis)` rebuilds the response from `result_json` + the row's columns.
- `error/GlobalExceptionHandler` (@RestControllerAdvice) — `MethodArgumentNotValidException` → 400,
  `RestClientResponseException` → upstream status (Jenkins), `ResourceAccessException` → 502 (Jenkins
  unreachable), `LlmAnalysisException` → 502 (AI analysis failed), `ResponseStatusException` → its status
  (400 invalid build URL, 404 unknown analysis).

### infrastructure (outbound adapters — all Jenkins/OpenAI/HTTP/database detail lives here)
- `persistence/Analysis` — JPA entity of the `analyses` table.
- `persistence/AnalysisRepository` — Spring Data repository; `Summary` is the history-list projection (reads
  only the list columns, never the logs or `result_json`). Used directly by the controller.
- `jenkins/JenkinsBuildSourceAdapter` (@Component) — implements `BuildSourcePort`.
- `jenkins/JenkinsHttpClient` (@Component) — `RestClient` with base URL; Basic auth only when a username
  is configured (warns if the token is missing). Builds `/job/<a>/job/<b>/<n>/consoleText` from the job full name
  (`a/b`, each segment encoded). Reads `consoleText` as **raw bytes** and decodes it with
  `ConsoleLogDecoder`.
- `jenkins/ConsoleLogDecoder` (package-private) — strict UTF-8; if that fails, line by line: valid UTF-8
  lines as UTF-8, others with the fallback charset (windows-1254). Reason: the Turkish Windows test JVM
  writes in cp1254 while Jenkins' own lines are UTF-8.
- `jenkins/JenkinsProperties` (`jenkins.*`, @Validated) — url (required), username, apiToken, logCharset,
  logFallbackCharset.
- `llm/TokenPricing` — record (input/output USD per 1M tokens) → `estimateUsd(prompt, completion)`,
  6 decimals, `null` if a price or count is unknown. Shared by all provider adapters.
- `llm/openai/OpenAiConfiguration` — active when `llm.provider=OPENAI` or unset; binds
  `OpenAiProperties` and declares the `OpenAiProvider` bean.
- `llm/openai/OpenAiProvider` — implements `LlmProvider` using **only the Responses API**. `RestClient`
  over JDK `HttpClient` with connect + read timeouts, `Authorization: Bearer <key>`, `POST /responses`.
  Answer = all `output_text` parts of the `message` items in `output[]` (the `reasoning` item is skipped).
  Usage mapping: `input_tokens` → promptTokens, `output_tokens` → completionTokens, `total_tokens` →
  totalTokens; cost via `TokenPricing`. Error handling: HTTP errors, timeouts, unreadable replies,
  `status=failed` (with `error.code/message`), empty answers, `refusal` parts and "all tokens spent on
  reasoning" (`status=incomplete`, `incomplete_details.reason=max_output_tokens`, no text) →
  `LlmAnalysisException`; a truncated answer that has text is returned with a warning.
- `llm/openai/OpenAiResponsesRequest` — `{model, input:<prompt>, max_output_tokens, reasoning:{effort}?,
  text:{verbosity?, format?}, store:false}; a request schema becomes `text.format =
  {type:"json_schema", name, schema, strict:true}` (structured outputs: the answer always matches the schema)` with `@JsonInclude(NON_NULL)`. **No `temperature`**: GPT-5 models reject
  non-default values. `store:false` so build logs are not retained on OpenAI's side.
- `llm/openai/OpenAiResponsesResponse` — reads `model`, `status`, `output[]` (typed items/content parts),
  `incomplete_details`, `error`, `usage`; `outputText()` / `refusal()` helpers.
- `llm/openai/OpenAiProperties` (`openai.*`, @Validated) — url, apiKey (**required**, env only), model,
  timeoutSeconds, connectTimeoutSeconds, maxOutputTokens (required, no Java defaults), reasoningEffort,
  verbosity, pricing.{inputUsdPerMillion, outputUsdPerMillion}.

## Configuration (`src/main/resources/application.yml`)
Every value can be overridden by the environment variable shown. **No secret is stored in the file.**

| Property | Env var | Default | Notes |
|---|---|---|---|
| `server.port` | — | `8090` | 8080 is the local Jenkins |
| `jenkins.url` | `JENKINS_URL` | `http://localhost:8080` | |
| `jenkins.username` | `JENKINS_USERNAME` | empty | empty = anonymous (this Jenkins then answers 403) |
| `jenkins.api-token` | `JENKINS_API_TOKEN` | empty | |
| `jenkins.log-charset` | `JENKINS_LOG_CHARSET` | `UTF-8` | |
| `jenkins.log-fallback-charset` | `JENKINS_LOG_FALLBACK_CHARSET` | `windows-1254` | for lines that are not valid UTF-8 |
| `llm.provider` | `LLM_PROVIDER` | `OPENAI` | only `OPENAI` is implemented |
| `openai.api-key` | `OPENAI_API_KEY` | — (**required**) | env only; startup fails without it |
| `openai.url` | `OPENAI_URL` | `https://api.openai.com/v1` | e.g. a company proxy |
| `openai.model` | `OPENAI_MODEL` | `gpt-5-mini` | |
| `openai.timeout-seconds` | `OPENAI_TIMEOUT_SECONDS` | `120` | read timeout for the whole answer |
| `openai.connect-timeout-seconds` | `OPENAI_CONNECT_TIMEOUT_SECONDS` | `10` | |
| `openai.max-output-tokens` | `OPENAI_MAX_OUTPUT_TOKENS` | `4000` | `max_output_tokens`; includes hidden reasoning tokens |
| `openai.reasoning-effort` | `OPENAI_REASONING_EFFORT` | `low` | `reasoning.effort`: minimal / low / medium / high; empty = not sent |
| `openai.verbosity` | `OPENAI_VERBOSITY` | `low` | `text.verbosity`: low / medium / high; empty = not sent |
| `openai.pricing.input-usd-per-million` | `OPENAI_INPUT_USD_PER_MILLION` | `0.25` | gpt-5-mini list price |
| `openai.pricing.output-usd-per-million` | `OPENAI_OUTPUT_USD_PER_MILLION` | `2.00` | gpt-5-mini list price |

Update the two pricing values if OpenAI changes its prices or another model is configured.

## Architectural rules (keep these)
- **Dependency direction:** infrastructure → application → domain. Domain imports nothing
  framework-related; application never imports `infrastructure.*` (enforced by `ArchitectureBoundaryTest`).
- **Provider independence:** the application layer knows only `LlmProvider` / `LlmCompletion`; provider
  names, wire formats, keys and prices live only in `infrastructure/llm/<provider>` + config.
- Controllers return DTOs, never domain models; conversion goes through the mapper.
- Jenkins/OpenAI/auth/HTTP details live only in `infrastructure` + config.
- Prefer rule lists / data-driven structures over long if-else chains (ErrorClassifier,
  RelevantLogExtractor, FRAMEWORK_PACKAGES, language map).
- Keep parsing **tool-neutral** in the domain; tool-specific knowledge goes into strategy
  implementations (`InteractionExtractor`).
- Extracted fields are `null` when not found — never guessed. The prompt only lists non-null facts.
- Secrets come from the environment, never committed.

## Conventions & gotchas
- **JDK 23 is the machine default**, the project targets Java 21. `pom.xml` lists Lombok explicitly in
  `annotationProcessorPaths` because JDK 23 no longer runs annotation processors implicitly — keep it.
- **Stale classes:** `target/` is not cleaned automatically; after removing classes run `mvn clean`.
- **Stale process after `mvn spring-boot:run`:** the forked JVM keeps holding 8090 after a naive kill:
  ```powershell
  Get-NetTCPConnection -LocalPort 8090 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  ```
- **401/403 from the endpoint** = missing/wrong Jenkins credentials, not a code bug.
  **502 "OpenAI returned HTTP 401"** = wrong/revoked `OPENAI_API_KEY`; **429** = rate limit or no quota.
- **GPT-5 specifics:** no custom `temperature`; `max_output_tokens` also covers reasoning tokens, so a
  too-small value can yield an empty answer (reported as a clear 502).
- **Tests and the real environment:** tests that bind configuration either replace the OS environment
  (`EnvironmentConfigBindingTest`) or set values explicitly, because an `OPENAI_API_KEY` on the developer
  machine would otherwise leak into them.
- **Manual test job:** `Mini-UI-Automation` — builds #1–#5 are SUCCESS; **#7 is a real Selenium failure**
  (locator `name=qqqqqqqq`, `ExampleSteps.java:28`). Its full console log (200 lines) is the test fixture
  `src/test/resources/logs/build-7-selenium-failure.log`.
- `OpenAiProviderTest` uses an in-process `com.sun.net.httpserver.HttpServer` stub (`StubOpenAiServer`,
  realistic Responses API replies incl. the `reasoning` item); `Build7OpenAiEndToEndTest` runs build #7 through
  the whole pipeline against that stub and checks request, schema, rendered output, tokens and cost;
  `OpenAiLiveBuild7Test` runs build #7 and the three `logs/samples/` logs
  against the real API, only with `mvn test -Dtest=OpenAiLiveBuild7Test -Dopenai.live=true` (needs
  `OPENAI_API_KEY`, costs ~$0.007, sends the logs to OpenAI; answers are written to `target/live-analyses.txt`);
  `LlmProviderSelectionTest` checks provider selection; `EnvironmentConfigBindingTest` binds the real
  `application.yml` against a simulated environment.
- ⚠️ **Security:** an earlier Jenkins API token was committed in `application.yml` (initial commit). It is
  gone from the file but still in git history — it must be revoked in Jenkins.

## Status & roadmap
**Done**
- Jenkins fetch with env-only credentials and cp1254-aware log decoding.
- Context extraction: SUCCESS short-circuit (`NONE`, no LLM call); clean scenario name + feature file/line;
  failing step text + line; full exception block; first application frame → file/line/step definition;
  tool-neutral failed interaction (Selenium command + locator); error category; relevant log snippet;
  last 200 lines.
- Provider-independent LLM port; OpenAI GPT-5 mini adapter; short Turkish prompt with evidence priority
  and anti-hallucination rules.
- Usage statistics in the response: provider, model, tokens, response time, estimated cost.
- Decision-support output: JSON answer (strict schema) → parsed → ≤ 10-line 🚨/📍/✅ text + `rootCauseAnalysis`.
- 128 unit tests + an opt-in live test over build #7 and three sample logs.

**Next**
- Run build #7 through the running app (real Jenkins + OpenAI). Already verified without Jenkins (live test,
  new format): 5,880 input + 574 output tokens, ~6 s, $0.002618, correct root cause and location.
- Collect real failed builds (not only synthetic samples) as an evaluation set; tighten the prompt where a
  third action is still generic.
- Add change context: the commits/changed files of the build from the Jenkins API and, if possible, the
  source line at `exceptionFile:exceptionLine`.
- More `InteractionExtractor` implementations (Playwright, Cypress) when those logs arrive.
- Further providers (Anthropic, Ollama) as separate adapters when needed.

**Open side-tasks**
- Revoke the old Jenkins token (see Security).
- Add a `.gitignore` (`target/`, IDE files, secrets) — there is none yet, so `target/` shows up in git.
