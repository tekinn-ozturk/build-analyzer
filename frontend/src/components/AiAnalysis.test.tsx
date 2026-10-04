import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Analysis } from '../types/analysis';
import AiAnalysis from './AiAnalysis';

const failure: Analysis = {
  id: 'x', jobName: 'UI-Test', buildNumber: 125, buildUrl: 'https://jenkins.company.com/job/UI-Test/125/',
  status: 'FAILURE', analyzedAt: '2026-10-04T09:42:00Z',
  errorType: 'TimeoutException', errorMessage: 'waiting for visibility of #finish h4',
  testContext: null,
  ai: { cause: '#finish h4 2 saniyede görünür olmadı.', suggestions: ['Selector\'ı doğrula.'] },
  relevantLogs: '', last200Lines: '',
};

describe('AiAnalysis', () => {
  it('shows the IDE view when testContext is present', () => {
    render(
      <AiAnalysis
        analysis={{
          ...failure,
          testContext: { scenario: 'Open Google', step: 'Then sonuçları kontrol et', featureFile: 'features/Login.feature', featureLine: 6 },
        }}
      />,
    );

    expect(screen.getByText('HATA SEBEBİ')).toBeTruthy();
    expect(screen.getByText('ÇÖZÜM ÖNERİLERİ')).toBeTruthy();
    expect(screen.getByText('HATA KONUMU')).toBeTruthy();
    expect(screen.getByText('Open Google', { exact: false })).toBeTruthy();
    expect(screen.getByText('sonuçları kontrol et', { exact: false })).toBeTruthy();
    expect(screen.getByText('6')).toBeTruthy();
    expect(screen.queryByText('HATA ÖZETİ')).toBeNull();
  });

  it('shows the plain error summary when testContext is null', () => {
    render(<AiAnalysis analysis={failure} />);

    expect(screen.getByText('HATA ÖZETİ')).toBeTruthy();
    expect(screen.getByText('waiting for visibility of #finish h4')).toBeTruthy();
    expect(screen.queryByText('HATA KONUMU')).toBeNull();
  });

  it('does not break when testContext fields are missing', () => {
    render(<AiAnalysis analysis={{ ...failure, testContext: { scenario: 'Kullanıcı giriş yapar' } }} />);
    expect(screen.getByText('Kullanıcı giriş yapar', { exact: false })).toBeTruthy();

    render(<AiAnalysis analysis={{ ...failure, testContext: {} }} />);
    expect(screen.getByText('Test konumu bilgisi yok.')).toBeTruthy();
  });

  it('shows a success message when there is nothing to analyze', () => {
    render(<AiAnalysis analysis={{ ...failure, status: 'SUCCESS', ai: null }} />);

    expect(screen.getByText(/Build başarılı/)).toBeTruthy();
    expect(screen.queryByText('HATA SEBEBİ')).toBeNull();
  });
});
