import type { Analysis, AnalysisResponse, AnalysisSummary } from '../types/analysis';
import type { JenkinsBuild } from '../utils/jenkinsUrl';

/**
 * All backend calls of the UI. In development Vite forwards /api to the backend on :8090.
 */

/** Runs the analysis (synchronous, usually 3–10 s); the backend stores it in PostgreSQL and returns it with its id. */
export async function analyzeBuild(build: JenkinsBuild): Promise<Analysis> {
  const response = await request<AnalysisResponse>('/api/v1/analysis/build', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ buildUrl: build.buildUrl }),
  });
  return toAnalysis(response);
}

/** The build history from PostgreSQL, newest first. */
export function listAnalyses(): Promise<AnalysisSummary[]> {
  return request<AnalysisSummary[]>('/api/v1/analyses');
}

export async function getAnalysis(id: string): Promise<Analysis> {
  return toAnalysis(await request<AnalysisResponse>(`/api/v1/analyses/${encodeURIComponent(id)}`));
}

/** Backend response → what the screens show. */
export function toAnalysis(response: AnalysisResponse): Analysis {
  const rootCause = response.rootCauseAnalysis;
  const succeeded = response.buildStatus === 'SUCCESS' && !rootCause;
  // First line of the exception block, without the class name: "org.x.TimeoutException: Expected …" → "Expected …"
  const firstLine = response.stackTrace?.split('\n')[0] ?? null;
  const exceptionName = response.exceptionType?.substring(response.exceptionType.lastIndexOf('.') + 1) ?? null;

  return {
    id: String(response.id),
    jobName: response.jobName,
    buildNumber: response.buildNumber,
    buildUrl: response.buildUrl,
    status: response.buildStatus ?? 'UNKNOWN',
    analyzedAt: response.analyzedAt,
    errorType: succeeded ? null : exceptionName ?? response.errorCategory,
    errorMessage: firstLine && response.exceptionType ? firstLine.replace(`${response.exceptionType}: `, '') : firstLine,
    testContext: response.testContext,
    ai: succeeded
      ? null
      : rootCause
        ? { cause: rootCause.rootCause, suggestions: rootCause.actions }
        : { cause: response.aiAnalysis ?? 'AI analizi yok.', suggestions: [] }, // answer the backend could not parse
    relevantLogs: response.relevantLogSnippet ?? '',
    last200Lines: response.last200Lines ?? '',
  };
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  let response: Response;
  try {
    response = await fetch(url, init);
  } catch {
    throw new Error('Backend\'e ulaşılamıyor. Backend (port 8090) çalışıyor mu?');
  }
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    throw new Error(errorMessage(response.status, body?.error));
  }
  return body as T;
}

/** A failed call as a message for the user; the backend's own text is kept for debugging. */
function errorMessage(status: number, backendText: string | undefined): string {
  const detail = backendText ? ` (${backendText})` : '';
  if (!backendText && status >= 500) {
    // The dev proxy answers so when the backend is down.
    return 'Backend\'e ulaşılamıyor. Backend (port 8090) çalışıyor mu?';
  }
  if (status === 400) {
    return `Geçerli bir Jenkins build URL'i değil.${detail}`;
  }
  if (status === 404) {
    return `Bulunamadı.${detail}`;
  }
  if (status === 401 || status === 403) {
    return `Jenkins erişimi reddedildi. Backend'deki JENKINS_USERNAME / JENKINS_API_TOKEN ayarlarını kontrol edin.${detail}`;
  }
  if (backendText?.startsWith('AI analysis failed')) {
    return `AI analizi başarısız oldu. Tekrar deneyin.${detail}`;
  }
  if (backendText?.startsWith('Jenkins is unreachable')) {
    return `Backend Jenkins'e ulaşamıyor.${detail}`;
  }
  return `İstek başarısız oldu (HTTP ${status}).${detail}`;
}
