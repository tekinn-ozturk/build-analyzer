import { describe, expect, it } from 'vitest';
import type { AnalysisResponse } from '../types/analysis';
import { toAnalysis } from './analysisApi';

const failure: AnalysisResponse = {
  id: 7, jobName: 'Mini-UI-Automation', buildNumber: 7, buildUrl: 'http://localhost:8080/job/Mini-UI-Automation/7/',
  analyzedAt: '2026-10-04T09:42:00Z', buildStatus: 'FAILURE',
  exceptionType: 'org.openqa.selenium.NoSuchElementException', errorCategory: 'SELENIUM',
  stackTrace: 'org.openqa.selenium.NoSuchElementException: no such element\n\tat x.Y.z(Y.java:1)',
  relevantLogSnippet: 'snippet', last200Lines: 'tail', aiAnalysis: '🚨 KÖK NEDEN ...',
  rootCauseAnalysis: { rootCause: 'Locator bulunamadı.', actions: ['Locator\'ı düzelt.'] },
  testContext: { scenario: 'Open Google', step: 'Arama kutusuna tıkla.', featureFile: 'Example.feature', featureLine: 6 },
};

describe('toAnalysis', () => {
  it('maps a failed build for the detail screen and passes testContext through unchanged', () => {
    const analysis = toAnalysis(failure);

    expect(analysis.id).toBe('7');
    expect(analysis.status).toBe('FAILURE');
    expect(analysis.errorType).toBe('NoSuchElementException');
    expect(analysis.errorMessage).toBe('no such element');
    expect(analysis.ai).toEqual({ cause: 'Locator bulunamadı.', suggestions: ['Locator\'ı düzelt.'] });
    expect(analysis.testContext).toBe(failure.testContext);
    expect(analysis.relevantLogs).toBe('snippet');
  });

  it('has no AI analysis for a successful build', () => {
    const analysis = toAnalysis({
      ...failure, buildStatus: 'SUCCESS', exceptionType: null, errorCategory: 'NONE', stackTrace: null,
      rootCauseAnalysis: null, testContext: null,
    });

    expect(analysis.ai).toBeNull();
    expect(analysis.errorType).toBeNull();
    expect(analysis.testContext).toBeNull();
  });

  it('shows the raw answer when the backend could not parse the model answer', () => {
    const analysis = toAnalysis({ ...failure, rootCauseAnalysis: null, aiAnalysis: 'serbest metin' });

    expect(analysis.ai).toEqual({ cause: 'serbest metin', suggestions: [] });
  });
});
