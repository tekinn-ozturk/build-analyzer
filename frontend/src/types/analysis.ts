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
  /** Hata Sebebi: short, interpreted cause. */
  cause: string;
  /** Optional second paragraph under the cause. */
  explanation?: string;
  /** Çözüm Önerileri: at most 3. */
  suggestions: string[];
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
}
