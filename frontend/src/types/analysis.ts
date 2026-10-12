/**
 * Where a test failed. Present only for failures that come from a test (any framework);
 * null for Maven / Jenkins / pipeline / infrastructure failures. Every field is optional.
 * The backend decides this; the frontend only checks whether it is null.
 */
export interface TestContext {
  scenario?: string | null;
  /** The failed step as written in the feature file, e.g. "Then sonuçları kontrol et". */
  step?: string | null;
  featureFile?: string | null;
  /** Line of the failed step in the feature file. */
  featureLine?: number | null;
}

export interface AiAnalysis {
  /** Hata Sebebi: short, interpreted cause (for a build with several failed tests: the build summary). */
  cause: string;
  /** Çözüm Önerileri: at most 3. */
  suggestions: string[];
}

/** Test counts printed by the test runner; a count the log did not show is null. */
export interface TestSummary {
  total: number | null;
  passed: number | null;
  failed: number | null;
  skipped: number | null;
}

/** One failed test of a failure group. Every field may be null when the log does not show it. */
export interface TestFailure {
  testContext: TestContext | null;
  /** Fully-qualified, e.g. "org.openqa.selenium.TimeoutException". */
  exceptionType: string | null;
  /** First line of the exception message. */
  message: string | null;
}

/**
 * Failed tests that share one error signature, with the AI's answer for them.
 * aiStatus: ANALYZED; SKIPPED = over the AI call limit, not sent to the AI; FAILED = the AI call failed
 * or gave no usable answer for this group. aiNote says why there is no answer.
 */
export interface FailureGroup {
  /** "G1", "G2", ... largest group first. */
  id: string;
  /** The grouping key: exception type, message and failure location. */
  signature: string;
  failureCount: number;
  exceptionType: string | null;
  aiStatus: 'ANALYZED' | 'SKIPPED' | 'FAILED';
  rootCause: string | null;
  actions: string[];
  aiNote: string | null;
  failures: TestFailure[];
}

/** What the detail screen shows; built from AnalysisResponse in api/analysisApi.ts. */
export interface Analysis {
  id: string;
  jobName: string;
  buildNumber: number;
  buildUrl: string;
  /** Jenkins build status: SUCCESS, FAILURE, UNSTABLE, ABORTED. */
  status: string;
  analyzedAt: string;
  /** Error Summary, e.g. "TimeoutException" / "MAVEN"; null for a successful build. */
  errorType: string | null;
  errorMessage: string | null;
  testContext: TestContext | null;
  /** null for a successful build. */
  ai: AiAnalysis | null;
  /** null when the log has no test summary line. */
  testSummary: TestSummary | null;
  /** Every failed test, grouped; empty when none was found (Maven / Jenkins / infrastructure failures). */
  failureGroups: FailureGroup[];
  relevantLogs: string;
  last200Lines: string;
}

/** One row of GET /api/v1/analyses. */
export interface AnalysisSummary {
  id: number;
  jobName: string;
  /** Jenkins full name, e.g. "Team/UI/Web-Regression". */
  jobPath: string;
  buildNumber: number;
  buildUrl: string;
  buildStatus: string | null;
  analysisStatus: string;
  errorCategory: string | null;
  headline: string | null;
  analyzedAt: string;
}

/** The fields of the backend's AnalyzeBuildResponse (POST /analysis/build, GET /analyses/{id}) the UI uses. */
export interface AnalysisResponse {
  id: number;
  jobName: string;
  buildNumber: number;
  buildUrl: string;
  analyzedAt: string;
  buildStatus: string | null;
  exceptionType: string | null;
  errorCategory: string | null;
  stackTrace: string | null;
  relevantLogSnippet: string | null;
  last200Lines: string | null;
  aiAnalysis: string | null;
  rootCauseAnalysis: { rootCause: string; actions: string[] } | null;
  testContext: TestContext | null;
  testSummary: TestSummary | null;
  /** Build summary of a build with several failed tests. */
  aiSummary: string | null;
  /** Missing in analyses stored before multi-failure support. */
  failureGroups?: FailureGroup[] | null;
}
