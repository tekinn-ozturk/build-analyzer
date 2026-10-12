# Build Analyzer

> **Note for an AI assistant / new session:** this README is the single source of truth for understanding
> the project without reading every file. It is kept in sync with the code by hand. Read it fully first; only
> open individual files when you need to change them.

AI-assisted **Jenkins build failure analyzer** for a Turkish-speaking QA team (about 4 users, one maintainer).
The user pastes a **Jenkins build URL**; the app fetches the build's console log, finds out **which tests
failed and why**, asks an LLM for a short, evidence-based root cause and concrete fixes, stores the result in
PostgreSQL and shows it in a small web UI.

```
Jenkins Build URL → console log → failures extracted & grouped → AI analysis → PostgreSQL → Analysis / History
```

- **One build = one analysis = one database row**, whether 1 or 500 tests failed.
- **One LLM call per build** in the normal case — never one call per test. Several failed tests are grouped by
  error signature first; only when the groups do not fit into one prompt are they split into a few calls
  (at most 3 by default).
- **SUCCESS builds** are answered instantly without calling the LLM.
- Nothing is invented: a value the log does not show stays `null` (no guessed file, line or test count).
- No users, projects, pipelines or login (removed on purpose, see [Database](#database)).

**Guiding rule (owner's decision): keep the codebase small enough that one developer can follow a bug through it
in 10–15 minutes.** No new layers, generic abstractions, extra endpoints, dependencies or "future-proofing"
without a real need; backend changes are agreed with the owner first.

## Contents
- [Tech stack](#tech-stack)
- [Running locally](#running-locally)
- [Using the app](#using-the-app)
- [How an analysis works](#how-an-analysis-works)
- [API](#api)
- [Database](#database)
- [Frontend](#frontend)
- [Architecture](#architecture)
- [Package / class map](#package--class-map)
- [Configuration](#configuration-srcmainresourcesapplicationyml)
- [Tests](#tests)
- [Architectural rules](#architectural-rules-keep-these)
- [Conventions & gotchas](#conventions--gotchas)
- [Status & roadmap](#status--roadmap)

## Tech stack
- **Backend:** Java 21, Spring Boot 3.3.5, Maven, Lombok; **Hexagonal (Ports & Adapters)**, LLM-provider independent.
- **Jenkins access:** Spring `RestClient` (HTTP Basic auth, credentials from the environment); only `consoleText` is read.
- **LLM:** **OpenAI Responses API** (`POST /v1/responses`), default model **`gpt-5-mini`**, strict structured
  output (JSON schema). The provider sits behind the `LlmProvider` port; OpenAI is the only implementation today.
- **Database:** **PostgreSQL** (Spring Data JPA, schema by **Flyway**) — one table, `analyses`.
- **Frontend:** React 19 + TypeScript + Vite + MUI 9 + React Router 8 in `frontend/` (lean V1, no state library).
- springdoc-openapi (Swagger UI).

## Running locally

Prerequisites: a Jenkins on `http://localhost:8080`, an **OpenAI API key**, a local **PostgreSQL** (database
`build_analyzer`, user `build_analyzer_user`; PostgreSQL 18 is used) and Node.js for the frontend. The backend
listens on **port 8090**, the frontend dev server on **5173**.

**Everyday start:** run `BuildAnalyzerApplication` in IntelliJ (env vars below) → `cd frontend` → `npm run dev`
→ open http://localhost:5173.

Secrets come **only from environment variables** — never from `application.yml`:

| Env var | Required | Purpose |
|---|---|---|
| `OPENAI_API_KEY` | **yes** | OpenAI API key. Missing → the app refuses to start (`OPENAI_API_KEY environment variable is not set`). |
| `JENKINS_USERNAME` | yes for this Jenkins | anonymous access answers 403 |
| `JENKINS_API_TOKEN` | yes for this Jenkins | Jenkins → user menu → *Security* → *API Token* |
| `DB_PASSWORD` | **yes** | password of the PostgreSQL user |
| `DB_URL` | no | default `jdbc:postgresql://localhost:5432/build_analyzer` |
| `DB_USERNAME` | no | default `build_analyzer_user` |

- **IntelliJ (how the app is normally run):** Run configuration `BuildAnalyzerApplication` → *Edit
  Configurations…* → *Environment variables*:
  `OPENAI_API_KEY=<key>;JENKINS_USERNAME=<user>;JENKINS_API_TOKEN=<token>;DB_PASSWORD=<db password>` → restart
  the app (env is read at startup only).
- **First start:** Flyway creates the schema; the history starts empty. Nothing has to be set up by hand.
- **Terminal (PowerShell):**
  ```powershell
  $env:OPENAI_API_KEY = "<key>"
  $env:JENKINS_USERNAME = "<user>"
  $env:JENKINS_API_TOKEN = "<token>"
  $env:DB_PASSWORD = "<db password>"
  mvn spring-boot:run
  ```
  (cmd.exe: `set "OPENAI_API_KEY=<key>"` etc.)
- **Frontend:**
  ```powershell
  cd frontend
  npm install
  npm run dev      # http://localhost:5173 — /api is proxied to the backend on :8090 (no CORS needed)
  ```

An OpenAI key is created at https://platform.openai.com/api-keys. Never commit it.

**Trying the API without the UI:** Swagger UI at http://localhost:8090/swagger-ui/index.html →
`POST /api/v1/analysis/build` → *Try it out*; or Postman `POST http://localhost:8090/api/v1/analysis/build`
with a raw JSON body.

## Using the app
1. **Dashboard:** paste a Jenkins build URL (e.g. `https://jenkins.company.com/job/Team/job/UI-Test/125/`) →
   *Analyze Build*. The URL is checked instantly (jobs inside folders of any depth are fine). While the
   synchronous request runs (usually 3–15 s) the UI shows "Build analiz ediliyor...". The recent history is
   listed below.
2. **Analysis detail** (`/analyses/:id`): build number, status chip, job, analysis time, "Jenkins'te aç" link
   and — when the log has a test summary — "Testler: 30 toplam · 20 başarılı · 10 başarısız · 0 atlanan".
   Two tabs:
   - **AI Analysis** — what the analysis found (see the three cases below).
   - **Relevant Logs** — the filtered log snippet / the last 200 raw lines; the full log stays on Jenkins.
3. **Build History** (`/history`): every analysis, newest first, Jenkins-style table.

What **AI Analysis** shows:

| Build | View |
|---|---|
| SUCCESS | green "Build başarılı. Analiz edilecek bir hata yok." (no LLM call) |
| **one failed test**, or a non-test failure | **HATA SEBEBİ** (AI root cause) → *where*: the IDE-like view (feature file, scenario, failed step and its line, error) when the failure comes from a test, else a plain **HATA ÖZETİ** card (Maven / Jenkins / pipeline / infrastructure) → **ÇÖZÜM ÖNERİLERİ** (≤ 3 actions) |
| **several failed tests** | **HATA SEBEBİ** = the AI's build summary + "10 başarısız test · 3 hata grubu · 2 grup AI ile ayrıntılı analiz edildi"; an info line when the runner counted more failures than the log itemised; a warning when groups were not analysed. Then **one card per failure group**: group id, exception, test count, error signature, the group's own HATA SEBEBİ / ÇÖZÜM ÖNERİLERİ (or "AI analizi yok: <reason>"), the list of affected tests — selecting one opens it in the same IDE-like view |

The IDE-like view never shows a file or line the log did not have: a plain JUnit test is shown by its name only.
UI labels are English where the spec gave them (Analyze Build, Build History, …); AI content and headings are
Turkish (`HATA SEBEBİ`, `ÇÖZÜM ÖNERİLERİ`; the words "Kök Neden" / "Aksiyon" are not used in the UI).

## How an analysis works

```
POST /api/v1/analysis/build {buildUrl}
  1. JenkinsBuildUrl.parse          job path (folders joined with "/") + build number      — 400 if not a build URL
  2. Jenkins consoleText            raw bytes → ConsoleLogDecoder (UTF-8, cp1254 fallback per line)
  3. BuildContextBuilder            status, test counts, primary failure, every failed test (grouped), log snippet, tail
  4. SUCCESS?                       fixed message, no prompt, no LLM call
  5. ≤ 1 failed test                single-failure prompt → 1 LLM call (RootCauseSchema.ROOT_CAUSE)
     several failed tests           group prompt(s) → normally 1, at most 3 LLM calls (RootCauseSchema.FAILURE_GROUPS)
  6. parse + render                 RootCauseParser / RootCauseFormatter; per-group status ANALYZED / SKIPPED / FAILED
  7. store                          one `analyses` row (result_json) → response with the row id
```

### 1. Reading the log (`BuildContextBuilder`)
Extraction is string/regex matching over well-known Jenkins / Maven / BDD / stack-trace shapes; it is
technology-neutral where it can be (no framework is required) and every field is best-effort (`null` = not found).

- **Build status:** Jenkins `Finished: X` (last occurrence) wins over Maven `BUILD X`. SUCCESS → category `NONE`,
  no error parsing at all (green logs contain harmless warnings that must not be classified).
- **Test counts** (`TestSummaryExtractor`): the runner's own summary line — Surefire/Failsafe totals (preferred:
  they count every test of a Maven module, Cucumber JUnit-runner scenarios included; per-class lines are skipped
  so nothing is counted twice; several modules are added up), else Cucumber `30 Scenarios (10 failed, 20 passed)`,
  Gradle `30 tests completed, 10 failed`, pytest `== 3 failed, 20 passed ==`, Jest `Tests: 2 failed, 28 passed,
  30 total`. A count the format does not print stays `null` (pytest has no total).
- **Error category** (`ErrorClassifier`): one build-level family — SELENIUM, MAVEN, CUCUMBER, JENKINS, INFRA,
  UNKNOWN — from an ordered keyword rule list. The exception types of the single failures live in their groups,
  not in this one field.
- **Primary failure** (the single-failure fields: scenario, step, exception type, full exception block, first
  application frame, failed UI interaction): the log's **first exception** — unless that exception belongs to no
  failed test (a retry a passing scenario logged, a setup warning) while a failed test printed one: then it is
  **the first failed test**. Used by the single-failure prompt and the flat API fields.
- **Relevant log snippet** (`RelevantLogExtractor`): noise dropped (downloads, git, `[Pipeline]`, Maven help),
  signal lines kept with context, markers kept alone, deduplicated, ≤ 120 lines. **Last 200 lines** kept raw.

### 2. Finding every failed test (`FailureExtractor`)
A failed test is an exception block printed inside a test's own output. A test's output starts at
- a Gherkin scenario line — `Scenario:`, `Scenario Outline:`, `Example:`, Turkish `Senaryo:` / `Örnek:` (pretty
  output of Cucumber or any BDD tool), or
- a runner's per-test result header — Surefire/Failsafe `[ERROR] Example.Open Google -- Time elapsed … <<< ERROR!`
  (2.x: `Open Google(Example)  Time elapsed …`), Gradle `LoginTest > invalidPassword() FAILED`,

and ends at the next test or a run summary (`Tests run:`, `30 Scenarios (…)`, `Results:`, `BUILD …`, `Running X`).
Exceptions outside any test (Maven, Jenkins, infrastructure, after the run) are **not** test failures.

- **Which exception is the test's failure:** the error Cucumber prints **indented right under the failed step**;
  an exception the application merely logged during the scenario does not replace it. A scenario without such an
  error (a hook failure, or a passing scenario that logged a handled exception) counts only when something
  confirms it failed — Cucumber's `Failed scenarios:` list or the runner's report — unless the log has neither.
- **Printed twice:** the pretty formatter and the runner's report print the same failure (build #7 prints it
  three times). A report is that scenario again when its name is the scenario's name — `Example.Open Google`,
  `Login.Login with user #2` (repeated names), `Open Google(Example)` — or, failing that, when it carries the same
  error. One report per scenario, so outline rows with one name are still counted apart.
- **Reruns:** a scenario printed again at the same location, or a report repeated under the same name
  (Surefire `rerunFailingTestsCount`), counts once — the last run. Tests Surefire lists under `Flakes:` (failed,
  then passed on rerun) are not failures.
- **`Failed scenarios:` list** (when present) decides which scenarios failed; a listed scenario without a
  printed exception is kept with `null` exception fields. List entries are URL-decoded (`Odeme%20akisi.feature`).
- **Per failure:** scenario / test name, failed step (text, feature file, line — from Cucumber's
  `at ✽.<step>(…feature:N)` pseudo-frame or the pretty step line, never a step of another test), exception type,
  first message line (REST-assured's "1 expectation failed." gets its next line too), full exception block,
  first application frame (`File.java:line`, also Gradle's `at app//…` frames), failed UI interaction
  (Selenium command + locator), and a few lines logged right after the exception (`logExcerpt`, e.g. a response
  body; stops at the next scenario's tags).
- Exception headers are whole fully-qualified `…Exception` / `…Error` / `…Failure` tokens (JUnit 4's
  `ComparisonFailure` included) — never part of a class name such as `GlobalExceptionHandlerTest` or
  `LoginErrorSteps`. ANSI colour codes and Windows line endings are handled. Failures are kept in log order.

### 3. Grouping (`FailureGrouper`)
Failed tests with the same **error signature** form one group:

```
exception type + ": " + first message line (run-specific values masked) + " @ " + failure location
org.openqa.selenium.NoSuchElementException: no such element: … #login-btn … @ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)
```

- Masked are only values that differ between two runs of the same error: session ids, UUIDs, timestamps, ports,
  object hashes, numbers of 10+ digits. Locators, status codes and expected/actual values are kept.
- The location is the first application frame, else the failing step — so the **same exception type at another
  place, or with another message, is another group**. The same type alone is never taken as the same cause.
- Groups are numbered `G1, G2, …`, largest first, then in log order. The first test of a group is its
  **representative**: its evidence goes to the LLM and its full stack trace is stored once.
- Failures without any exception share one group: "Logda exception / stack trace yok".

### 4. Asking the LLM
**At most one failed test** → the **single-failure prompt** (Turkish, short and directive):
1. role: a Jenkins build-failure **decision-support system**, not a report writer — answer "Neden patladı?
   Nerede patladı? Ne yapmalıyım?" in ten seconds; source priority and anti-speculation rules;
2. `=== 1. YAPISAL KANIT ===` — one `- Etiket: değer` line per **non-null** fact (Build Durumu, Hata Kategorisi,
   Test Özeti, Senaryo, Başarısız Adım, Step Definition, Hata Dosyası:Satırı, Exception, Otomasyon Aracı,
   Başarısız Komut, Locator Tipi/Değeri, Kod Dili) + the full stack trace — the primary source;
3. `=== 2. İLGİLİ LOG KESİTİ ===` — secondary; `=== 3. JENKINS LOG — SON 200 SATIR ===` — supporting only
   (lines over 500 chars clipped);
4. the JSON answer format, last: `{"rootCause", "file", "line", "method", "actions"}` (strict schema).

Build #7's prompt is ~20k chars (~6k tokens).

**Several failed tests** → **group prompt(s)** (`PromptBuilder.buildGroupPrompts`):
- the build summary (status, category, test counts, failed tests found / groups), an index line for **every**
  group, then — per group in this request — its signature, the representative's facts (step, step definition,
  file:line, exception, UI interaction), the **counted** list of affected tests (≤ 10 names + "… ve N test daha";
  the count is always complete), **one** representative stack trace (≤ 40 lines) and its log excerpt;
- passed tests are only counted, never listed; no build-wide snippet or raw tail (in a big run those are mostly
  passed scenarios);
- extra rules: the counts and test lists come from the application and must not be changed; the same exception
  type is not the same cause; a common cause is stated only if the evidence shows it, otherwise the model must
  say it cannot be proven; missing evidence is said, not filled;
- answer: `{"summary": "...", "groups": [{"id": "G1", "rootCause": "...", "actions": ["..."]}]}`
  (`RootCauseSchema.FAILURE_GROUPS`, strict) — `rootCause` ≤ 3 sentences (what happened, under which condition,
  on which evidence), ≤ 3 prioritised actions.

**Call budget** (`analysis.*`, see [Configuration](#configuration-srcmainresourcesapplicationyml)): groups are
packed into one prompt while it stays within `max-prompt-chars` (60 000) and `max-groups-per-call` (6); at most
`max-llm-calls` (3) prompts are made. A normal build needs **one** call. Every field that can grow is clipped
(signature, facts, trace lines, log lines), so a prompt stays bounded however big a log is. The limits are
character counts — no token estimate is claimed.

**Per-group status** (`FailureGroupAnalysis`): `ANALYZED`; `SKIPPED` — over the call limit, not sent (still
listed with all its tests); `FAILED` — the call failed, the answer was unreadable or had nothing for the group,
or (single-failure build) the prompt's evidence was another exception. Every non-analysed group carries an
`aiNote`. A failed call loses only its own groups; only when **no** call gets through does the analysis fail
(502, nothing stored). Metrics are the sum of the answered calls (a failed call reports no usage).

**Credentials are masked in every prompt** before it leaves the application: `password=` / `-Dtoken: x` /
`DB_PASSWORD=` / `x-api-key:` values, JSON fields named like credentials (also escaped `\"password\":\"…\"`),
`Authorization: Basic|Bearer …` (also `Authorization=Basic …` of REST-assured logs), bare long bearer tokens,
`user:password@` in URLs, `curl -u user:password`, Gherkin key/value rows `| password | … |`. Not masked: empty
values, `name:value` without a space (`LoginTest.invalidPassword:57` keeps its line number), CSS selectors such as
`#password:focus`. A JSON field named like a credential is masked even when it holds a validation message.

### 5. Answer handling and output format
- `RootCauseParser` tolerates a ```` ```json ```` fence or text around the object and normalises values: file name
  only (path/package/`:line` removed), simple method name, `line <= 0` → `null`, list markers stripped, ≤ 3
  actions, one line per value. `RootCauseAnalysis.withFallbackLocation` fills a missing file/line/method from the
  parser's evidence. An unparseable answer is shown as-is. `parseGroups` drops groups without id or root cause
  (the service reports them as unanswered); unknown ids are ignored.
- `RootCauseFormatter` renders the ready-to-show `aiAnalysis` text, **at most 10 lines**:
  ```
  🚨 KÖK NEDEN                              🚨 BUILD ÖZETİ
  name=qqqqqqqq locator'ı bulunamadı; …      10 başarısız testin 5'i #login-btn …
  📍 KONUM                                   🧩 HATA GRUPLARI
  ExampleSteps.java:28                      - G1 (5 test): #login-btn bulunamadı …
  clickSearchBox()                          - G2 (4 test): pay-now-btn 10 sn içinde …
  ✅ AKSİYON                                 - G3 (1 test): AI analizi yok (…)
  - ExampleSteps.java:28'deki locator'ı …
  ```
- **Output philosophy:** users (QA Engineer, QA Lead, SDET, Developer, Team Lead) must answer *"Neden patladı?
  Nerede patladı? Ne yapmalıyım?"* in about ten seconds. No technical-explanation section, no tutorials
  ("Selenium nedir"), no speculation the evidence does not show ("iframe olabilir", "cache", "wait eklenmeli"),
  a specific root cause ("name=qqqqqqqq locator'ı bulunamadı", not "Element bulunamadı"), actions derived from the
  evidence, no confidence level (dropped by product decision).
- **Measured on 2026-09-27** (gpt-5-mini, `OpenAiLiveBuild7Test`): build #7 plus three synthetic sample logs gave
  the right file:line, a specific Turkish root cause and 2–3 actions in 3–6 s for $0.0011–0.0026 each. Weak spot:
  a third action is sometimes generic.

## API

| Endpoint | Returns |
|---|---|
| `POST /api/v1/analysis/build` `{"buildUrl": "..."}` | runs + stores the analysis; the response below, **with** `generatedPrompt` |
| `GET /api/v1/analyses` | the history, newest first: `id, jobName, jobPath, buildNumber, buildUrl, buildStatus, analysisStatus, errorCategory, headline, analyzedAt` (no logs, no `result_json`) |
| `GET /api/v1/analyses/{id}` | one stored analysis in the same shape as the POST response, **without** `generatedPrompt` (never stored); 404 if missing |

`buildUrl` is parsed by `domain/model/JenkinsBuildUrl`: `/job/Team/job/UI-Test/125/` → job `Team/UI-Test`, build
`125`; trailing parts like `/console`, query and fragment are ignored; a context path and port are kept. Blank →
400; not a Jenkins build URL (no `/job/<name>/<number>/`, Blue Ocean, `lastFailedBuild`, multibranch names with
`/`) → **400 before anything runs**. The log is always fetched from the configured `JENKINS_URL`; the URL's host is
not used for that.

Response for the real failing build #7 (long values shortened):
```json
{
  "id": 1,
  "jobName": "Mini-UI-Automation",
  "buildNumber": 7,
  "buildUrl": "http://localhost:8080/job/Mini-UI-Automation/7/",
  "analyzedAt": "2026-10-04T09:42:00Z",
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
  "stackTrace": "org.openqa.selenium.NoSuchElementException: no such element ... Session ID ... at ...",
  "relevantLogSnippet": "Scenario: Open Google ... * Arama kutusuna tıkla. ... [INFO] BUILD FAILURE ... Finished: FAILURE",
  "last200Lines": "<raw log tail>",
  "generatedPrompt": "Sen bir Jenkins build hatası karar destek sistemisin. ...",
  "aiAnalysis": "🚨 KÖK NEDEN\nname=qqqqqqqq locator'ı bulunamadı; ...\n📍 KONUM\nExampleSteps.java:28\nclickSearchBox()\n✅ AKSİYON\n- ...",
  "rootCauseAnalysis": {
    "rootCause": "name=qqqqqqqq locator'ı bulunamadı; org.openqa.selenium.NoSuchElementException atıldı.",
    "file": "ExampleSteps.java", "line": 28, "method": "clickSearchBox",
    "actions": ["ExampleSteps.java satır 28'deki findElement(using=name, value=qqqqqqqq) locator değerini düzelt."]
  },
  "testContext": { "scenario": "Open Google", "step": "Arama kutusuna tıkla.", "featureFile": "src/test/resources/features/Example.feature", "featureLine": 6 },
  "testSummary": { "total": 1, "passed": 0, "failed": 1, "skipped": 0 },
  "aiSummary": null,
  "failureGroups": [{ "id": "G1", "failureCount": 1, "aiStatus": "ANALYZED", "...": "see below" }],
  "provider": "OPENAI",
  "model": "gpt-5-mini-2025-08-07",
  "promptTokens": 5880,
  "completionTokens": 574,
  "totalTokens": 6454,
  "responseTimeMs": 5968,
  "estimatedCostUsd": 0.002618
}
```

A build with **several failed tests** additionally fills `aiSummary` and one entry per group (and has
`rootCauseAnalysis: null`; `aiAnalysis` is the 🚨 BUILD ÖZETİ / 🧩 HATA GRUPLARI text):
```json
"testSummary": { "total": 30, "passed": 20, "failed": 10, "skipped": 0 },
"aiSummary": "10 başarısız testin 5'i #login-btn ...; gruplar arasında ortak bir neden kanıtlanamadı.",
"failureGroups": [{
  "id": "G1",
  "signature": "org.openqa.selenium.NoSuchElementException: no such element: ... @ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)",
  "failureCount": 5,
  "exceptionType": "org.openqa.selenium.NoSuchElementException", "message": "no such element: ...",
  "exceptionFile": "LoginSteps.java", "exceptionLine": 31,
  "stackTrace": "<the representative's full exception block>", "logExcerpt": null,
  "aiStatus": "ANALYZED", "rootCause": "...", "actions": ["..."], "aiNote": null,
  "failures": [{
    "testContext": { "scenario": "Hatalı giriş 6", "step": "Giriş butonuna tıkla.", "featureFile": "src/test/resources/features/Shop.feature", "featureLine": 30 },
    "exceptionType": "org.openqa.selenium.NoSuchElementException", "message": "no such element: ...",
    "exceptionFile": "LoginSteps.java", "exceptionLine": 31
  }]
}]
```

Field notes:
- Every extracted field is best-effort: `null` means "not found", never a guess.
- The flat single-failure fields (`failedScenario` … `stackTrace`) describe the build's **primary** failure.
- `testContext` (scenario, step, featureFile, featureLine = line of the step) is set when the failure comes from a
  test; `null` for Maven / Jenkins / infrastructure failures — the frontend's only rule for "IDE view or plain
  error card". Each entry of `failureGroups[].failures` carries its own `testContext`.
- `testSummary` is `null` when the log has no summary line; a single count is `null` when the format does not
  print it.
- `failureGroups` is `[]` when no failed test was found and for analyses stored before multi-failure support.
  Only the representative's stack trace and log excerpt are kept (per group), not one per failed test.
- `aiStatus`: `ANALYZED` / `SKIPPED` / `FAILED`, with the reason in `aiNote` (see [Asking the LLM](#4-asking-the-llm)).
- `generatedPrompt` has `@JsonInclude(NON_NULL)`: present in a POST response after an LLM call (all prompts, if
  there were several calls), absent in GET responses and for SUCCESS builds.
- `model` is what OpenAI reports (a dated snapshot). `promptTokens` / `completionTokens` / `totalTokens` are the
  Responses API's `input_tokens` / `output_tokens` / `total_tokens` — output includes GPT-5's hidden reasoning
  tokens — summed over the answered calls. `estimatedCostUsd` = tokens × the configured list prices (6 decimals;
  cached-input discounts ignored, so an upper estimate; calls that failed are not included). `responseTimeMs` is
  the LLM time only.
- **SUCCESS build:** no prompt, no LLM call — `errorCategory` = `NONE`, error fields, `testContext`,
  `rootCauseAnalysis` and usage fields `null`, `failureGroups` `[]`, `aiAnalysis` = "Build başarıyla tamamlandı.
  Analiz gerektiren bir hata tespit edilmedi."; `testSummary` is still read.
- **Errors:** 400 validation / not a build URL; Jenkins non-2xx → same status (401/403 = bad or missing
  credentials, 404 = no such job/build); Jenkins unreachable → 502; OpenAI unreachable / timeout / non-2xx
  (401 wrong key, 429 rate limit or quota) / empty or refused answer → 502 `{"error": "AI analysis failed: ..."}`.
  Failed analyses are not stored.

## Database

One table, **`analyses`** — one analysed Jenkins build. Schema by Flyway (`src/main/resources/db/migration`);
Hibernate only validates it (`ddl-auto: validate`).

- `V1__init_schema.sql` created users / projects / project_members / pipelines / analyses;
  `V2__drop_projects_and_pipelines.sql` removed everything but `analyses` (product decision: the build URL alone
  identifies an analysis). **Never edit an applied migration; add a new one (`V3__…`).**
- Columns: Jenkins (`build_url, job_name, job_path, build_number, build_status`), history list
  (`analysis_status, error_category, exception_type, headline`), result (`error_reason, result_json, relevant_logs,
  last_200_lines`), LLM (`model, total_tokens, estimated_cost_usd`), `created_at, analyzed_at`.
- `result_json` (TEXT, Jackson) is the analysis response without the prompt, the logs and the header fields (they
  have their own columns) — including `testSummary`, `aiSummary` and every failure group with every failed test.
  **The prompt and the full console log are never stored** (the log stays on Jenkins).
- `analysis_status`: `COMPLETED` · `SKIPPED` (SUCCESS build, no LLM call) · `UNSTRUCTURED` (no usable model answer) ·
  `PARTIAL` (some failure groups have no AI answer — over the call limit, a failed call, or evidence not sent).
- `headline` (history label): simple exception name, else the error category; "Build başarılı" for SUCCESS;
  "10 başarısız test, 3 hata grubu" for several failed tests. `exception_type` is the primary failure's type.
- `error_reason`: the root cause, the build summary, or the raw answer.
- Every analysis is a new row — analysing the same build twice gives two rows. No authentication: everybody who
  can reach the app sees the whole history.
- To look at the data: pgAdmin → `build_analyzer` → `analyses`, or
  `psql -h localhost -U build_analyzer_user -d build_analyzer` (psql is in `C:\Program Files\PostgreSQL\18\bin`).

## Frontend

`frontend/` — React 19, TypeScript, Vite, MUI 9, React Router 8; backend calls with the browser's `fetch`.
Deliberately small (lean V1): no Redux/Zustand/TanStack Query — state is `useState`/`useEffect` in the page or
component; flat folders, no feature/domain/mapper layers. The long frontend design document written early on
(kept outside the repo) is the product vision, not the implemented scope.

```
src/
  api/analysisApi.ts        every backend call + toAnalysis() (backend response → what the screens show)
  types/analysis.ts         AnalysisResponse (backend), Analysis (screens), AnalysisSummary (history row),
                            TestContext, TestSummary, FailureGroup, TestFailure
  pages/                    DashboardPage (URL input + history), AnalysisPage (/analyses/:id), HistoryPage (/history)
  components/               AnalyzeForm, HistoryList, AiAnalysis (single view + failure-group cards),
                            TestContextView (IDE-like), LogViewer, StatusChip, Layout, Sidebar
  utils/jenkinsUrl.ts       URL parser for instant form feedback (the backend re-parses; JenkinsBuildUrl is authoritative)
  utils/format.ts           date, test-summary and type-name formatting
  theme.ts                  MUI theme + monospace font
```

```
npm run dev      # http://localhost:5173, /api proxied to :8090
npm test         # Vitest
npm run build    # type check + production bundle in frontend/dist
```

- Flow: `DashboardPage` (URL → `AnalyzeForm` → `POST /api/v1/analysis/build`) → `AnalysisPage`
  (`GET /api/v1/analyses/{id}`, tabs AI Analysis / Relevant Logs) · `HistoryList` (`GET /api/v1/analyses`).
- **Render rules:** `ai === null` → success message. More than one failed test in `failureGroups` → build
  summary + group cards. Otherwise `testContext` decides: not null → IDE-like view, null → HATA ÖZETİ card. The
  frontend knows no test framework; every `testContext` field is optional.
- A group card's test list is a real list (`ul > li > button`); the selected test opens in `TestContextView`.
  Cards are keyed by analysis id + group id, so another analysis starts with its own first test selected.
- `index.html` uses `lang="en"` on purpose: with `tr`, MUI's upper-casing turns "Build" into "BUİLD"; Turkish
  headings are written in upper case in the source ("HATA SEBEBİ").

## Architecture

Hexagonal (Ports & Adapters): **infrastructure → application → domain**, the domain imports nothing
framework-related, the application never imports `infrastructure.*` (enforced by `ArchitectureBoundaryTest`).

```
POST /api/v1/analysis/build
  → BuildAnalysisController (api)
    → JenkinsBuildUrl.parse(buildUrl)                                              (domain; 400 if invalid)
    → AnalyzeBuildUseCase → AnalyzeBuildService (application)
        → BuildSourcePort → JenkinsBuildSourceAdapter → JenkinsHttpClient → GET /job/{a}/job/{b}/{n}/consoleText
        → BuildContextBuilder → BuildAnalysisContext (domain)
              status · TestSummaryExtractor · ExceptionBlockExtractor · ScenarioExtractor · StepExtractor
              · InteractionExtractor[] · ErrorClassifier · RelevantLogExtractor · tail
              · FailureExtractor → FailureGrouper (every failed test, grouped)
        → SUCCESS → fixed message
        → ≤ 1 failed test → PromptBuilder.build → LlmProvider [ROOT_CAUSE] → RootCauseParser → RootCauseFormatter
        → several → PromptBuilder.buildGroupPrompts → LlmProvider [FAILURE_GROUPS] × ≤ 3 → parseGroups → formatGroups
        → BuildAnalysisResult (context, prompt(s), aiAnalysis, rootCauseAnalysis, llmMetrics, aiSummary, groupAnalyses)
    → BuildAnalysisResponseMapper.toEntity → AnalysisRepository.save → PostgreSQL
    → BuildAnalysisResponseMapper.toResponse → AnalyzeBuildResponse (DTO, with the stored id)

GET /api/v1/analyses       → AnalysisRepository.findAllByOrderByAnalyzedAtDesc()  (Summary projection)
GET /api/v1/analyses/{id}  → AnalysisRepository.findById → mapper.toResponse(entity)  (404 if missing)
```

The analysis core (`application`, `domain`) does not know the database; storing happens in the controller after
`AnalyzeBuildService` returns.

**LLM provider independence:**
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
- `llm.provider` (`LLM_PROVIDER`, default `OPENAI`) selects the adapter; exactly one `LlmProvider` bean exists; an
  inactive provider's properties are neither bound nor required.
- **Adding a provider** (e.g. Anthropic, or Ollama again) = one package `infrastructure/llm/<provider>/`:
  1. `<Provider>Provider implements LlmProvider` — returns `LlmCompletion`, wraps every transport error in
     `LlmAnalysisException` (never let `RestClientResponseException` escape: the API layer maps that type to Jenkins
     errors) and enforces `LlmRequest.responseSchema` its own way;
  2. `<Provider>Properties` (`@ConfigurationProperties("<provider>")`, `@Validated`, key from env only);
  3. `<Provider>Configuration` — `@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = "<NAME>")`
     + `@EnableConfigurationProperties`, declares the bean;
  4. a `<provider>:` block in `application.yml` and an adapter test with an in-process HTTP stub.
  Nothing in application, domain, api or `PromptBuilder` changes.

## Package / class map
Base package: `com.company.buildanalyzer`

### domain (pure Java — no Spring, no framework imports)
- `model/BuildAnalysisContext` — buildStatus, `FailedScenario failedScenario`, `FailedStep failedStep`,
  exceptionType, `ErrorCategory errorCategory`, stackTrace (full block), `FailureLocation failureLocation`,
  `FailedInteraction failedInteraction` (the primary failure), relevantLogSnippet, last200Lines,
  `TestSummary testSummary`, `List<FailureGroup> failureGroups`. `isSuccessful()` / static `isSuccessStatus(String)`
  is the single source of truth for "SUCCESS, no analysis needed"; `failureCount()` / `hasMultipleFailures()`
  choose the flow.
- `model/TestSummary` — total, passed, failed, skipped (`Integer`, `null` = not printed).
- `model/TestFailure` — one failed test: `FailedScenario scenario` (a plain test: the runner's test name, no
  file/line), `FailedStep step`, exceptionType, message, stackTrace, `FailureLocation`, `FailedInteraction`,
  logExcerpt. Every field but `scenario` may be `null`.
- `model/FailureGroup` — id (`G1`…), signature, failures (first = representative).
- `model/FailureGroupAnalysis` — groupId, status (`ANALYZED` / `SKIPPED` / `FAILED`), rootCause, actions (≤ 3), note.
- `model/FailedScenario` — name, featureFile, featureLine (`Open Google`, `src/test/resources/features/Example.feature`, `4`).
- `model/FailedStep` — text, featureFile, featureLine (`Arama kutusuna tıkla.`, `Example.feature`, `6`).
- `model/FailureLocation` — stepDefinition, file, line (`stepdefinitions.ExampleSteps.clickSearchBox`, `ExampleSteps.java`, `28`).
- `model/FailedInteraction` — tool-neutral "command against a target": tool, command, locatorType, locatorValue
  (`Selenium`, `findElement`, `name`, `qqqqqqqq`; Playwright would be `toBeVisible` + `getByRole` …).
- `model/LlmMetrics` — provider, model, prompt/completion/total tokens, responseTimeMs, estimatedCostUsd.
- `model/BuildAnalysisResult` — context, generatedPrompt, aiAnalysis, `RootCauseAnalysis rootCauseAnalysis`,
  `LlmMetrics llmMetrics`, aiSummary, `List<FailureGroupAnalysis> groupAnalyses`. What the use case returns.
- `model/RootCauseAnalysis` — rootCause, file, line, method, actions (never null, immutable, max 3);
  `withFallbackLocation(FailureLocation)`.
- `model/JenkinsBuildUrl` — `parse(url)` → jobPath, buildNumber, normalised url; `IllegalArgumentException` otherwise.
- `model/ErrorCategory` — SELENIUM, MAVEN, CUCUMBER, JENKINS, INFRA, UNKNOWN, NONE (successful build).

### application (use cases, parsing, prompts — may use Spring stereotypes; depends only on domain and ports)
**Use case & ports**
- `usecase/AnalyzeBuildUseCase` — inbound port: `BuildAnalysisResult analyze(String jobName, int buildNumber)`.
- `usecase/AnalyzeBuildService` — owns the flow: log → context → SUCCESS short-circuit → single-failure or group
  flow (calls, per-group statuses, summed metrics) → result. No HTTP, no provider knowledge.
- `port/out/BuildSourcePort` — `String fetchConsoleLog(String jobName, int buildNumber)`.
- `port/out/LlmProvider` — `LlmCompletion complete(LlmRequest)`; `LlmRequest` = prompt + optional
  `LlmResponseSchema` (name + JSON schema as a plain map); `LlmCompletion` = text, provider, model, tokens, cost;
  `LlmAnalysisException` — the only error type an adapter throws.

**Context extraction (`context/`)** — the static extractors are package-private
- `BuildContextBuilder` — orchestrates everything below; picks the primary failure; keeps the last 200 lines.
- `ExceptionBlockExtractor` — `isHeader` / `extractAt` / `extract` (first block). A header is a whole fully-qualified
  `…Exception|Error|Failure` token (ANSI codes stripped). Keeps the **full** block unclipped: every message line
  (max 30 as a runaway guard) and every frame (`at`, `Caused by:`, `Suppressed:`, `... n more`); ends at a blank
  line, a new log record (`[INFO]`, `[ERROR]`, `[Pipeline]`, …) or the first non-frame after frames. First
  application frame (package not in `FRAMEWORK_PACKAGES`; `at [loader/][module/]pkg.Class.method(File.java:N)`)
  → `FailureLocation`; fallback: the Cucumber step comment `# pkg.Class.method()` above the header.
- `ScenarioExtractor` — scenario lines (`Scenario`, `Scenario Outline/Template`, `Example`, `Senaryo`, `Senaryo
  taslağı`, `Örnek`; clean name, feature file with spaces / `classpath:` / Windows paths, ANSI stripped;
  `cleanName` keeps a `#` inside a name like `Order #12`) and the `Failed scenarios:` lists (URL-decoded). For the
  primary failure: the first listed entry, else the last scenario before the exception, else the first.
- `StepExtractor` — the step pseudo-frame `at ✽.<step>(…/<file>.feature:<line>)` (`✽` often arrives as `?`) first;
  else the nearest pretty step line above the exception (English + Turkish keywords), never crossing a scenario
  header or, for a failed test, the start of that test's output.
- `TestSummaryExtractor`, `FailureExtractor`, `FailureGrouper` — see [How an analysis works](#how-an-analysis-works).
- `InteractionExtractor` (strategy interface) + `SeleniumInteractionExtractor` — `Command: [<session>, findElement
  {using=name, value=…}]` → command + locator, else the message's `{"method":…,"selector":…}`. Adding a tool =
  adding an implementation.
- `RelevantLogExtractor` — data-driven NOISE / SIGNALS / MARKERS rules; warnings never trigger on their own;
  dedup ignoring `[LEVEL]` prefixes; lines clipped at 300 chars; ≤ 120 lines (1/3 head + 2/3 tail); no signal → last
  40 non-noise lines. Tested on Selenium, Cypress, Playwright, pytest, NUnit and Karate samples.
- `classifier/ErrorClassifier` — ordered `Rule{ErrorCategory, keyword Pattern}` list, first match wins.

**Prompts (`prompt/`)**
- `PromptBuilder` — `build(context)` (single failure) and `buildGroupPrompts(context)` (several); text only, in
  Turkish, provider-agnostic; masks credentials.
- `AnalysisLimits` (`analysis.*`) — `maxPromptChars`, `maxGroupsPerCall`, `maxLlmCalls`.

**Answer handling (`analysis/`)**
- `RootCauseSchema` — `ROOT_CAUSE` and `FAILURE_GROUPS` (strict: every property required, `additionalProperties:
  false`; no `maxItems` — not every provider supports it, the parser enforces the limits).
- `RootCauseParser` — `parse` / `parseGroups`; `RootCauseFormatter` — `format` / `formatGroups`.

### api (inbound adapter — Spring web)
- `controller/BuildAnalysisController` — the three endpoints.
- `dto/request/AnalyzeBuildRequest` — `{ buildUrl }` + validation.
- `dto/response/AnalyzeBuildResponse` — flat record used by POST and GET `/analyses/{id}`; nested
  `RootCauseAnalysisResponse`, `TestContextResponse`, `TestSummaryResponse`, `FailureGroupResponse` (with
  `TestFailureResponse` per failed test). Tool- and provider-neutral names; `failureGroups` is never `null`.
- `mapper/BuildAnalysisResponseMapper` — domain → DTO (flattened), `testContext` rule, failure groups with their AI
  answers, `toEntity` (row: headline, analysis status, `result_json`), `toResponse(Analysis)` (from `result_json` +
  columns).
- `error/GlobalExceptionHandler` — validation → 400, Jenkins `RestClientResponseException` → its status,
  `ResourceAccessException` → 502, `LlmAnalysisException` → 502, `ResponseStatusException` → its status.

### infrastructure (outbound adapters — all Jenkins/OpenAI/HTTP/database detail lives here)
- `persistence/Analysis` (JPA entity) and `persistence/AnalysisRepository` (Spring Data; `Summary` = history-list
  projection, never reads the logs or `result_json`).
- `jenkins/JenkinsBuildSourceAdapter` → `JenkinsHttpClient` (base URL, Basic auth only when a username is set,
  `/job/<a>/job/<b>/<n>/consoleText` from the job full name, body read as **raw bytes**) → `ConsoleLogDecoder`
  (strict UTF-8; else line by line with the windows-1254 fallback — the Turkish Windows test JVM writes cp1254);
  `JenkinsProperties` (`jenkins.*`).
- `llm/TokenPricing` — USD per 1M tokens → `estimateUsd`, `null` if anything is unknown.
- `llm/openai/OpenAiConfiguration`, `OpenAiProvider` (Responses API only; `RestClient` over JDK `HttpClient` with
  connect + read timeouts; answer = the `output_text` parts of the `message` items; usage → tokens; HTTP errors,
  timeouts, unreadable replies, `status=failed`, refusals, empty answers and "all tokens spent on reasoning" →
  `LlmAnalysisException`; a truncated answer with text is returned with a warning), `OpenAiResponsesRequest`
  (`{model, input, max_output_tokens, reasoning.effort?, text.verbosity?, text.format?, store:false}`; a schema
  becomes `text.format = {type:"json_schema", name, schema, strict:true}`; **no `temperature`** — GPT-5 rejects
  non-default values; `store:false` so logs are not retained by OpenAI), `OpenAiResponsesResponse`,
  `OpenAiProperties` (`openai.*`, no Java defaults).

## Configuration (`src/main/resources/application.yml`)
Every value can be overridden by the environment variable shown. **No secret is stored in the file.**

| Property | Env var | Default | Notes |
|---|---|---|---|
| `server.port` | — | `8090` | 8080 is the local Jenkins |
| `spring.datasource.url` | `DB_URL` | `jdbc:postgresql://localhost:5432/build_analyzer` | |
| `spring.datasource.username` | `DB_USERNAME` | `build_analyzer_user` | |
| `spring.datasource.password` | `DB_PASSWORD` | empty | env only; required to start |
| `spring.jpa.hibernate.ddl-auto` | — | `validate` | schema only via Flyway migrations |
| `spring.jpa.open-in-view` | — | `false` | |
| `jenkins.url` | `JENKINS_URL` | `http://localhost:8080` | |
| `jenkins.username` | `JENKINS_USERNAME` | empty | empty = anonymous (this Jenkins then answers 403) |
| `jenkins.api-token` | `JENKINS_API_TOKEN` | empty | |
| `jenkins.log-charset` | `JENKINS_LOG_CHARSET` | `UTF-8` | |
| `jenkins.log-fallback-charset` | `JENKINS_LOG_FALLBACK_CHARSET` | `windows-1254` | for lines that are not valid UTF-8 |
| `analysis.max-prompt-chars` | `ANALYSIS_MAX_PROMPT_CHARS` | `60000` | several failed tests: target size of one group prompt (characters) |
| `analysis.max-groups-per-call` | `ANALYSIS_MAX_GROUPS_PER_CALL` | `6` | failure groups per LLM call (bounds the answer length) |
| `analysis.max-llm-calls` | `ANALYSIS_MAX_LLM_CALLS` | `3` | LLM calls per build at most; groups beyond are listed as not analysed |
| `llm.provider` | `LLM_PROVIDER` | `OPENAI` | only `OPENAI` is implemented |
| `openai.api-key` | `OPENAI_API_KEY` | — (**required**) | env only; startup fails without it |
| `openai.url` | `OPENAI_URL` | `https://api.openai.com/v1` | e.g. a company proxy |
| `openai.model` | `OPENAI_MODEL` | `gpt-5-mini` | |
| `openai.timeout-seconds` | `OPENAI_TIMEOUT_SECONDS` | `120` | read timeout for the whole answer |
| `openai.connect-timeout-seconds` | `OPENAI_CONNECT_TIMEOUT_SECONDS` | `10` | |
| `openai.max-output-tokens` | `OPENAI_MAX_OUTPUT_TOKENS` | `4000` | includes hidden reasoning tokens |
| `openai.reasoning-effort` | `OPENAI_REASONING_EFFORT` | `low` | minimal / low / medium / high; empty = not sent |
| `openai.verbosity` | `OPENAI_VERBOSITY` | `low` | low / medium / high; empty = not sent |
| `openai.pricing.input-usd-per-million` | `OPENAI_INPUT_USD_PER_MILLION` | `0.25` | gpt-5-mini list price |
| `openai.pricing.output-usd-per-million` | `OPENAI_OUTPUT_USD_PER_MILLION` | `2.00` | gpt-5-mini list price |

Update the two pricing values if OpenAI changes its prices or another model is configured.

## Tests

**Backend:** `mvn test` — **226 tests** (220 unit tests + 4 PostgreSQL tests + 2 opt-in live OpenAI tests). No
external service or API key is needed: OpenAI is stubbed with an in-process HTTP server or a mock, Jenkins is
mocked, the controller test mocks the repository, and realistic console logs come from fixtures
(`src/test/resources/logs/`) or the `SyntheticConsoleLog` generator (any number of passed/failed Cucumber
scenarios with Surefire's repeated reports, as in a real run).
- `AnalysisRepositoryTest` (4 tests) runs against the **real local PostgreSQL** when `DB_PASSWORD` is set; every
  test is rolled back, nothing stays in the database.
- `OpenAiLiveBuild7Test` (opt-in, real API, ~$0.01, sends the logs to OpenAI) runs build #7, three sample logs and
  a synthetic 10-failure build: `mvn test -Dtest=OpenAiLiveBuild7Test -Dopenai.live=true` (needs `OPENAI_API_KEY`;
  answers go to `target/live-analyses.txt`).

With `DB_PASSWORD` set: `Tests run: 226, Failures: 0, Errors: 0, Skipped: 2` (only the live tests skipped). On the
developer machine `DB_PASSWORD` is a **Windows user environment variable**, so `mvn test` in any new terminal
picks it up (IntelliJ's run configuration does not pass its env vars to tests or terminals).

Where the multi-failure behaviour is tested:

| Case | Tests |
|---|---|
| one failed test keeps the single flow | `AnalyzeBuildServiceTest`, `Build7OpenAiEndToEndTest`, `MultiFailureContextTest.build7HasExactlyOneFailure` |
| 30 scenarios / 10 failures → 10 found, counts, one row, one LLM call | `MultiFailureContextTest`, `MultiFailureAnalysisTest`, `BuildAnalysisControllerTest` |
| the same error 10× → one group of 10 | `MultiFailureContextTest`, `MultiFailureAnalysisTest`, `FailureGrouperTest` |
| NoSuchElement / Timeout / AssertionError apart | `MultiFailureContextTest`, `FailureGrouperTest` |
| 1500 scenarios, few failures → one call, no passed tests in the prompt | `MultiFailureContextTest`, `MultiFailureAnalysisTest` |
| many groups → bounded calls, nothing lost, partial failure, unreadable answers | `MultiFailureAnalysisTest`, `GroupPromptTest` |
| missing stack trace / location → nothing invented | `MultiFailureContextTest`, `BuildAnalysisResponseMapperTest`, frontend `AiAnalysis.test` |
| Maven / Jenkins / infrastructure → no failure groups, standard card | `MultiFailureContextTest`, frontend `AiAnalysis.test` |
| SUCCESS → no LLM call, counts read | `AnalyzeBuildServiceTest`, `MultiFailureContextTest` |
| persistence: one row, readable via GET, old rows readable | `AnalysisRepositoryTest` (real PostgreSQL), `BuildAnalysisResponseMapperTest`, `BuildAnalysisControllerTest` |
| nested Jenkins URL `/job/Team/job/UI-Test/125/` | `JenkinsBuildUrlTest`, `JenkinsHttpClientTest`, frontend `jenkinsUrl.test` |
| real-world log shapes (runner names, logged exceptions, reruns/flakes, Gradle, colours, encoded paths, REST-assured, huge lines) | `FailureExtractorTest`, `TestSummaryExtractorTest` |
| credential masking, prompt size bounds | `PromptBuilderTest`, `GroupPromptTest` |

**Frontend:** `cd frontend && npm test` — **32 Vitest tests** (URL parser, response mapping, `AnalyzeForm`,
`AiAnalysis` single and multi-failure rendering); `npm run build` also type-checks.

After deleting or renaming classes run `mvn clean test` so stale `.class` files in `target/` are not picked up.

## Architectural rules (keep these)
- **Dependency direction:** infrastructure → application → domain; domain framework-free; application never
  imports `infrastructure.*` (`ArchitectureBoundaryTest`).
- **Provider independence:** the application knows only `LlmProvider` / `LlmCompletion`; provider names, wire
  formats, keys and prices live only in `infrastructure/llm/<provider>` + config.
- Controllers return DTOs, never domain models (exception: the history list returns the repository's `Summary`
  projection, which is already exactly the list row).
- **The analysis core does not know the database:** storing and reading happen in the controller through
  `AnalysisRepository`.
- **One build = one analysis = one row;** never one LLM call per test; detected failures are never dropped because
  they were not sent to the LLM.
- **Schema changes only through a new Flyway migration** (`V3__…`); never edit an applied one.
- Prefer rule lists / data-driven structures over if-else chains (ErrorClassifier, RelevantLogExtractor,
  TestSummaryExtractor, FRAMEWORK_PACKAGES, the credential and run-specific-value masks).
- Keep parsing **tool-neutral**; tool-specific knowledge goes into strategy implementations (`InteractionExtractor`).
- Extracted fields are `null` when not found — never guessed. Prompts list only non-null facts.
- Secrets come from the environment, never committed; credentials in logs never reach a prompt.

## Conventions & gotchas
- **JDK 23 is the machine default**, the project targets Java 21. `pom.xml` lists Lombok explicitly in
  `annotationProcessorPaths` because JDK 23 no longer runs annotation processors implicitly — keep it.
- **Stale classes:** `target/` is not cleaned automatically; after removing classes run `mvn clean`.
- **Stale process after `mvn spring-boot:run`:** the forked JVM keeps holding 8090 after a naive kill:
  ```powershell
  Get-NetTCPConnection -LocalPort 8090 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  ```
- **401/403 from the endpoint** = missing/wrong Jenkins credentials, not a code bug. **502 "OpenAI returned HTTP
  401"** = wrong/revoked `OPENAI_API_KEY`; **429** = rate limit or no quota.
- **GPT-5 specifics:** no custom `temperature`; `max_output_tokens` also covers reasoning tokens, so a too-small
  value can yield an empty answer (reported as a clear 502).
- **Tests and the real environment:** tests that bind configuration either replace the OS environment
  (`EnvironmentConfigBindingTest`) or set values explicitly, because an `OPENAI_API_KEY` on the developer machine
  would otherwise leak into them.
- **Manual test job:** `Mini-UI-Automation` — builds #1–#5 are SUCCESS; **#7 is a real Selenium failure** (locator
  `name=qqqqqqqq`, `ExampleSteps.java:28`); #9 fails with a Selenium `TimeoutException`. Build #7's full console log
  is the fixture `src/test/resources/logs/build-7-selenium-failure.log`.
- **PostgreSQL 18:** Flyway logs "PostgreSQL 18.6 is newer than this version of Flyway" — a warning only.
- **Analysis ids have gaps:** the DB tests roll back their rows, but PostgreSQL does not give used ids back.
- **Windows shell gotcha (Git Bash):** `/tmp` in Git Bash is not the folder Windows `node` sees; use a Windows path
  for files shared between them.
- ⚠️ **Security:** an earlier Jenkins API token was committed in `application.yml` (initial commit). It is gone from
  the file but still in git history — it must be revoked in Jenkins.

## Status & roadmap
**Done**
- Jenkins fetch with env-only credentials and cp1254-aware log decoding; nested-folder jobs.
- Context extraction: SUCCESS short-circuit, test counts, primary failure (scenario, step, full exception block,
  code location, UI command/locator), error category, relevant log snippet, last 200 lines.
- **Every failed test** extracted (pretty output, Surefire/Failsafe, Gradle; reruns, flaky tests, logged exceptions
  and runner naming variants handled), grouped by error signature, analysed with one bounded set of LLM calls
  (normally one), per-group answer and status, stored as one row, shown as group cards with an IDE-like test view.
- Provider-independent LLM port; OpenAI GPT-5 mini adapter; short Turkish decision-support prompts with evidence
  priority, anti-speculation rules and credential masking; strict JSON answers; ≤ 10-line rendered text.
- Usage statistics: provider, model, tokens, response time, estimated cost.
- PostgreSQL history (`analyses`, Flyway V1 + V2) with list and detail endpoints; React frontend.
- Verified end to end on 2026-10-04 against the real Jenkins, OpenAI and PostgreSQL 18 (build #7; ~6 s,
  ~$0.0024). The multi-failure flow was verified with unit tests, the real PostgreSQL and the UI against a stub
  API (2026-10-11/12) — **not yet against the real Jenkins/OpenAI**.

**Known limitations**
- Failures are itemised from the console log only: JVM output (Gherkin pretty output, Surefire/Failsafe and Gradle
  per-test reports, Java exception blocks). pytest / Jest / Cypress logs give test counts only. Interleaved output
  of parallel runs can attribute an exception to the wrong scenario.
- A scenario that is not shown under a failed step, in a `Failed scenarios:` list or in a runner report cannot be
  confirmed: in a log with none of these, an exception a passing scenario logged counts as its failure.
- Runner names are matched to scenarios by name or by the same error; an unusual naming scheme with a different
  error text could still count a failure twice.
- Jenkins' JUnit `testReport` API is not used (this team's jobs publish Cucumber reports, not `junit`).
- Only the representative's stack trace and log excerpt are stored per group, not one per failed test.
- In a big run the stored "Relevant Snippet" is dominated by `Scenario:` marker lines (`RelevantLogExtractor` is
  unchanged); the group cards carry each group's own evidence.
- Masking is pattern-based: text typed by Selenium (`sendKeys … value=[…]`) is not masked; a JSON field named like a
  credential is masked even when it holds a validation message.
- Tokens and cost of a call that failed are not in the totals (the provider reports no usage for it).

**Next**
- Run a real multi-failure build (and the opt-in live test) once to check the group prompt and answer quality.
- Analyse a real build of a job **inside a Jenkins folder** — supported and unit-tested, never tried against the
  real Jenkins.
- Decide how the app is served for the team (e.g. the frontend build in Spring's `static/` — then `/analyses/:id`
  needs a forward to `index.html` on refresh — or a separate web server). Today it runs as two dev servers.
- Collect real failed builds as an evaluation set; tighten the prompt where a third action is still generic.
- Change context: the build's commits/changed files from the Jenkins API and, if possible, the source line at
  `exceptionFile:exceptionLine`.
- More `InteractionExtractor` implementations (Playwright, Cypress) and further LLM providers when needed.

**Decided against for V1 (do not re-add without the owner)**
- Users, projects, project members, pipelines, roles, login (built once, then removed — V2 migration).
- A "NE OLDU?" UI section (expected / actual / mechanism) with its own `whatHappened` field — declined for now
  (the group answers' `rootCause` says what happened, under which condition and on which evidence).
- Async analysis, SSE/real progress events, caching, queues, extra DTO/mapper/repository layers, new tables for
  failures.
- Structured Evidence tab and the 6-stage progress screen (replaced by the simpler V1 screens).

**Open side-tasks**
- Revoke the old Jenkins token (see Security).
