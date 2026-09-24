# Build Analyzer

> **Note for an AI assistant / new session:** this README is the single source of truth
> for understanding the project without reading every file. It is kept in sync with the
> code by hand. Read it fully first; only open individual files when you need to change them.

AI-assisted **Jenkins Build Analyzer** for a QA team. A REST API that pulls a Jenkins
build's console log, distills it into a small structured context, classifies the error
family, and builds an LLM prompt from it. The LLM itself is **not yet integrated** — the
current endpoint returns the extracted context plus the generated prompt so it can be
inspected in Postman.

## Tech stack
- Java 21, Spring Boot 3.3.5, Maven, Lombok
- Architecture: **Hexagonal (Ports & Adapters)**
- First LLM target (planned): Ollama + Qwen3 4B, provider-swappable. No DB, no frontend in v1.

## Run
The app listens on **port 8090** (port 8080 is left to a local Jenkins). Jenkins auth is
supplied via environment variables — never hard-coded.

```bash
cd C:\Users\PC\Desktop\build-analyzer
set "JENKINS_USERNAME=admin"
set "JENKINS_API_TOKEN=<current-token>"
mvn spring-boot:run
```

`mvn test` runs the suite (**25 unit tests, all green**).

### Endpoint
`POST /api/v1/analysis/build`

Request:
```json
{ "jobName": "Mini-UI-Automation", "buildNumber": 5 }
```
`jobName` must not be blank, `buildNumber` must be a positive integer (Bean Validation → 400 on violation).

Response (current shape):
```json
{
  "buildStatus": "FAILURE",
  "failedScenario": "Open Google",
  "exceptionType": "org.openqa.selenium.NoSuchElementException",
  "errorCategory": "SELENIUM",
  "stackTrace": "...",
  "last500Lines": "...",
  "generatedPrompt": "Sen profesyonel bir QA Otomasyon Mühendisi ve CI/CD uzmanısın. ..."
}
```

## Request flow
```
POST /api/v1/analysis/build
  → BuildAnalysisController
    → AnalyzeBuildUseCase (impl: AnalyzeBuildService)
        → BuildSourcePort (out)  → JenkinsBuildSourceAdapter → JenkinsHttpClient → Jenkins REST /job/{job}/{build}/consoleText
        → BuildContextBuilder    → (uses ErrorClassifier) → BuildAnalysisContext (domain)
    → PromptBuilder  → String prompt        (controller calls this)
    → BuildAnalysisResponseMapper → AnalyzeBuildResponse (DTO)
```

## Package / class map
Base package: `com.company.buildanalyzer`

### api (inbound adapter — Spring web)
- `controller/BuildAnalysisController` — the one endpoint. Orchestrates: `analyze` → `promptBuilder.build` → `mapper.toResponse(context, prompt)`. Returns a DTO, never a domain model.
- `dto/request/AnalyzeBuildRequest` — record `{ jobName, buildNumber }` + validation annotations.
- `dto/response/AnalyzeBuildResponse` — record: buildStatus, failedScenario, exceptionType, errorCategory (String), stackTrace, last500Lines, generatedPrompt.
- `mapper/BuildAnalysisResponseMapper` (@Component) — `toResponse(BuildAnalysisContext, String generatedPrompt)`. Converts the `ErrorCategory` enum via `.name()` so the DTO stays free of the domain enum.
- `error/GlobalExceptionHandler` (@RestControllerAdvice) — maps `MethodArgumentNotValidException`→400, `RestClientResponseException`→upstream status (e.g. Jenkins 401/403/404), `ResourceAccessException`→502 (Jenkins unreachable).

### application (use-cases / orchestration — may use Spring, depends on domain)
- `usecase/AnalyzeBuildUseCase` — inbound port: `BuildAnalysisContext analyze(String jobName, int buildNumber)`.
- `usecase/AnalyzeBuildService` (@Service) — impl. Injects `BuildSourcePort` + `BuildContextBuilder`. Fetches raw log via the port, then builds the context. Returns a domain model.
- `port/out/BuildSourcePort` — outbound port: `String fetchConsoleLog(String jobName, int buildNumber)`. The core's only view of the CI source.
- `context/BuildContextBuilder` (@Service) — string/regex extraction of buildStatus, failedScenario, exceptionType, stackTrace, last500Lines from the raw log; injects `ErrorClassifier` to set errorCategory. Best-effort — missing fields are null. Blank/null log → all-null context with status/category UNKNOWN.
- `classifier/ErrorClassifier` (@Service) — **rule-list based**, no if-else chain. An ordered `List<Rule>` of `{ErrorCategory, compiled keyword Pattern}`; `classify(rawLog, exceptionType)` returns the first matching rule's category, else UNKNOWN. Order = priority: specific signals (Selenium exceptions, INFRA connection errors, JENKINS agent, specific Maven dependency errors, Cucumber) are checked before the generic `BUILD FAILURE` (which almost every failed build prints).
- `prompt/PromptBuilder` (@Service) — turns a `BuildAnalysisContext` into the LLM prompt String. **The prompt is in Turkish** (the app serves Turkish users) and tells the model to answer in Turkish while keeping technical identifiers (exception/class/method names, commands) as-is. Persona = profesyonel QA Otomasyon Mühendisi + CI/CD uzmanı. Sections in this order — Build Durumu, Başarısız Senaryo, Hata Kategorisi, Exception Tipi, Stack Trace, Son 500 Log Satırı — each skipped when null/blank via one `appendSection(label, value)` helper. Raw values (e.g. `FAILURE`, `SELENIUM`) are passed through untranslated. Ends with a task list: Kök Neden Analizi, Teknik Açıklama, QA Önerileri, Geliştirici Önerileri, Güven Seviyesi (Düşük / Orta / Yüksek). **No LLM call** — text only.

### domain (pure Java — NO Spring, no framework imports)
- `model/BuildAnalysisContext` — record: buildStatus, failedScenario, exceptionType, `ErrorCategory errorCategory`, stackTrace, last500Lines. This is the payload destined for the LLM.
- `model/ErrorCategory` — enum: SELENIUM, MAVEN, CUCUMBER, JENKINS, INFRA, UNKNOWN.

### infrastructure (outbound adapters — Spring, HTTP; holds all Jenkins/auth detail)
- `jenkins/JenkinsBuildSourceAdapter` (@Component) — implements `BuildSourcePort`, delegates to the HTTP client.
- `jenkins/JenkinsHttpClient` (@Component) — builds a `RestClient` with baseUrl from properties; adds Spring's `BasicAuthenticationInterceptor` only when a username is configured (else anonymous). Calls `/job/{jobName}/{buildNumber}/consoleText`.
- `jenkins/JenkinsProperties` (@ConfigurationProperties("jenkins")) — url, username, apiToken.

### config
- `application.yml` — `server.port: 8090`; `jenkins.url: http://localhost:8080`, `jenkins.username: ${JENKINS_USERNAME:}`, `jenkins.api-token: ${JENKINS_API_TOKEN:}`.

## Architectural rules (keep these)
- **Dependency direction:** infrastructure → application → domain. Domain imports nothing framework-related; application never imports `infrastructure.*`.
- Controllers return DTOs, not domain models; conversion goes through the mapper.
- Jenkins / auth details live only in `infrastructure.jenkins` + config.
- Prefer rule-lists / data-driven structures over long if-else chains (see ErrorClassifier).
- Secrets come from the environment, never committed.

## Conventions & gotchas
- **JDK 23 is the machine default**, but the project targets Java 21. `pom.xml` sets Lombok as an explicit `annotationProcessorPaths` entry because JDK 23 no longer runs annotation processors implicitly — do not remove it or Lombok stops generating.
- **App port is 8090.** A local Jenkins runs on 8080; don't reuse it.
- **Stale process after `mvn spring-boot:run`:** the forked JVM keeps holding 8090 after a naive kill. Free it before restarting:
  ```powershell
  Get-NetTCPConnection -LocalPort 8090 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
  ```
- The Jenkins API token was rotated once; always pass the current one via env. A 401/403 means bad/absent creds, not a code bug — the request still reached Jenkins.
- Test Jenkins job used for manual checks: `Mini-UI-Automation` (build #5).

## Status & roadmap
- **Done:** Jenkins fetch (Basic auth) → context extraction → error classification → prompt generation, all exposed via the endpoint. 25 tests pass.
- **Next (main):** LLM integration — add `application/port/out/LlmPort`, an infrastructure adapter for Ollama + Qwen3 4B (provider-swappable via config), send it `generatedPrompt`, parse the answer into summary / rootCause / recommendation (+ confidence). Note: the model answers in Turkish, so the parser must match the Turkish headings (e.g. "Kök Neden Analizi") and confidence values (Düşük / Orta / Yüksek).
- **Open side-tasks:** add a `.gitignore` (exclude `target/`, IDE files, any secrets) — there is none yet; optionally drop `last500Lines` from the HTTP response and send it only to the LLM (it bloats replies).
