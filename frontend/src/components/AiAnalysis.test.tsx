import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Analysis } from '../types/analysis';
import AiAnalysis from './AiAnalysis';

const failure: Analysis = {
  id: 'x', jobName: 'UI-Test', buildNumber: 125, buildUrl: 'https://jenkins.company.com/job/UI-Test/125/',
  status: 'FAILURE', analyzedAt: '2026-10-04T09:42:00Z',
  errorType: 'TimeoutException', errorMessage: 'waiting for visibility of #finish h4',
  testContext: null,
  ai: { cause: '#finish h4 2 saniyede görünür olmadı.', suggestions: ['Selector\'ı doğrula.'] },
  testSummary: null, failureGroups: [],
  relevantLogs: '', last200Lines: '',
};

/** 30 scenarios, 5 failed tests found in the log, 3 groups; G3 was over the AI call limit. */
const severalFailures: Analysis = {
  ...failure,
  errorType: 'NoSuchElementException',
  ai: { cause: '5 testin 3\'ü #login-btn yüzünden düştü; ortak neden kanıtlanamadı.', suggestions: [] },
  testSummary: { total: 30, passed: 24, failed: 6, skipped: 0 },
  failureGroups: [
    {
      id: 'G1', signature: 'org.openqa.selenium.NoSuchElementException: no such element @ LoginSteps.clickLogin(LoginSteps.java:31)',
      failureCount: 3, exceptionType: 'org.openqa.selenium.NoSuchElementException',
      exceptionFile: 'LoginSteps.java', exceptionLine: 31,
      stackTrace: 'org.openqa.selenium.NoSuchElementException: no such element\n\tat LoginSteps.clickLogin(LoginSteps.java:31)',
      aiStatus: 'ANALYZED', rootCause: '#login-btn locator\'ı bulunamadı.', actions: ['LoginSteps.java:31\'deki locator\'ı düzelt.'],
      aiNote: null,
      failures: [
        { testContext: { scenario: 'Login 1', step: 'Giriş butonuna tıkla.', featureFile: 'features/Login.feature', featureLine: 5 },
          exceptionType: 'org.openqa.selenium.NoSuchElementException', message: 'no such element: #login-btn' },
        { testContext: { scenario: 'Login 2', step: 'Giriş butonuna tıkla.', featureFile: 'features/Login.feature', featureLine: 11 },
          exceptionType: 'org.openqa.selenium.NoSuchElementException', message: 'no such element: #login-btn' },
        { testContext: { scenario: 'Login 3', step: 'Tekrar giriş yap.', featureFile: 'features/Login.feature', featureLine: 17 },
          exceptionType: 'org.openqa.selenium.NoSuchElementException', message: 'no such element: #login-btn' },
      ],
    },
    {
      id: 'G2', signature: 'java.lang.AssertionError: expected [201] but found [500]',
      failureCount: 1, exceptionType: 'java.lang.AssertionError',
      exceptionFile: null, exceptionLine: null, stackTrace: 'java.lang.AssertionError: expected [201] but found [500]',
      aiStatus: 'FAILED', rootCause: null, actions: [], aiNote: 'AI çağrısı başarısız: OpenAI returned HTTP 429',
      failures: [
        { testContext: { scenario: 'com.acme.OrderApiTest.createOrder' },
          exceptionType: 'java.lang.AssertionError', message: 'expected [201] but found [500]' },
      ],
    },
    {
      id: 'G3', signature: 'Logda exception / stack trace yok',
      failureCount: 1, exceptionType: null, exceptionFile: null, exceptionLine: null, stackTrace: null,
      aiStatus: 'SKIPPED', rootCause: null, actions: [], aiNote: 'AI çağrı sınırına (1 çağrı) ulaşıldığı için ayrıntılı analiz edilmedi.',
      failures: [{ testContext: { scenario: 'Checkout', featureFile: 'features/Checkout.feature', featureLine: 7 }, exceptionType: null, message: null }],
    },
  ],
};

/** The text of a count tile, e.g. "Başarısız test6 / 30". */
function tile(label: string): string | null {
  return screen.getByText(label).parentElement!.textContent;
}

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

  it('shows the counts, the build summary and the largest group when several tests failed', () => {
    render(<AiAnalysis analysis={severalFailures} />);

    // the runner's failed / total, the groups, the groups the AI answered
    expect(tile('Başarısız test')).toBe('Başarısız test6 / 30');
    expect(tile('Hata grubu')).toBe('Hata grubu3');
    expect(tile('AI analizi')).toBe('AI analizi1 / 3 grup');

    expect(screen.getByText(/5 testin 3'ü #login-btn yüzünden düştü/)).toBeTruthy();
    expect(screen.getByText('5 başarısız testten 3 tanesi G1\'de toplanıyor; önce bu gruba bakın.')).toBeTruthy();
    // the runner counted 6 failed tests, the log gave details of 5: said, not hidden
    expect(screen.getByText(/Test özetinde 6 başarısız test var; logdan 5 tanesinin ayrıntısı çıkarılabildi/)).toBeTruthy();
    expect(screen.getByText(/2 hata grubu AI ile ayrıntılı analiz edilmedi/)).toBeTruthy();

    // the list on the left
    expect(screen.getByRole('button', { name: 'Hata grupları (3)' })).toBeTruthy();
    expect(screen.getByText('LoginSteps.java:31')).toBeTruthy();

    // the details of G1, the largest group, on the right
    expect(screen.getByText('3 test · %60')).toBeTruthy();
    expect(screen.getByText('LoginSteps.clickLogin(LoginSteps.java:31)')).toBeTruthy();
    expect(screen.getByText('#login-btn locator\'ı bulunamadı.')).toBeTruthy();
    expect(screen.getByText('LoginSteps.java:31\'deki locator\'ı düzelt.')).toBeTruthy();
    expect(screen.getByText('ETKİLENEN TESTLER (3)')).toBeTruthy();
    expect(screen.getByText('Login 2')).toBeTruthy();
    expect(screen.getByText('Login.feature:11')).toBeTruthy();
    expect(screen.getAllByText('HATA SEBEBİ')).toHaveLength(2); // build summary + the selected group
    expect(screen.queryByText(/AI analizi yok:/)).toBeNull(); // the other groups' answers are not shown yet
    expect(screen.queryByText('HATA ÖZETİ')).toBeNull();
  });

  it('shows another group when it is selected in the list', () => {
    render(<AiAnalysis analysis={severalFailures} />);

    fireEvent.click(screen.getByText('AssertionError'));

    expect(screen.getByText('AI analizi yok: AI çağrısı başarısız: OpenAI returned HTTP 429')).toBeTruthy();
    expect(screen.getByText('ETKİLENEN TESTLER (1)')).toBeTruthy();
    expect(screen.queryByText('#login-btn locator\'ı bulunamadı.')).toBeNull();
  });

  it('opens the selected failed test in the IDE view', () => {
    render(<AiAnalysis analysis={severalFailures} />);
    expect(screen.getByText('Giriş butonuna tıkla.', { exact: false })).toBeTruthy(); // first test preselected

    fireEvent.click(screen.getByText('Login 3'));

    expect(screen.getByText('Tekrar giriş yap.', { exact: false })).toBeTruthy();
    expect(screen.getByText('17')).toBeTruthy();
  });

  it('lists every failed test in the tests view and opens the chosen one', () => {
    render(<AiAnalysis analysis={severalFailures} />);
    fireEvent.click(screen.getByRole('button', { name: 'Testler (5)' }));

    expect(screen.getByText('G1 · Login.feature:5')).toBeTruthy();
    expect(screen.getByText('G3 · Checkout.feature:7')).toBeTruthy();
    expect(screen.getByText('G2 · AssertionError')).toBeTruthy(); // no feature file: its exception instead

    fireEvent.click(screen.getByText('G1 · Login.feature:17'));
    expect(screen.getByText('Tekrar giriş yap.', { exact: false })).toBeTruthy();

    fireEvent.click(screen.getByText('G2 · AssertionError'));
    expect(screen.getByText('AI analizi yok: AI çağrısı başarısız: OpenAI returned HTTP 429')).toBeTruthy();
    expect(screen.getByText('AssertionError: expected [201] but found [500]')).toBeTruthy();
  });

  it('finds tests regardless of case and Turkish letters', () => {
    render(<AiAnalysis analysis={severalFailures} />);
    fireEvent.click(screen.getByRole('button', { name: 'Testler (5)' }));
    const search = screen.getByPlaceholderText('Senaryo, feature, exception ara');

    // the step "Giriş butonuna tıkla." of Login 1 and Login 2
    fireEvent.change(search, { target: { value: 'GIRIS BUTONUNA' } });
    expect(screen.getByText('G1 · Login.feature:5')).toBeTruthy();
    expect(screen.getByText('G1 · Login.feature:11')).toBeTruthy();
    expect(screen.queryByText('G1 · Login.feature:17')).toBeNull();
    expect(screen.queryByText('G3 · Checkout.feature:7')).toBeNull();

    fireEvent.change(search, { target: { value: 'ödeme sayfası' } });
    expect(screen.getByText('Eşleşen sonuç yok.')).toBeTruthy();
  });

  it('points at the two largest groups when they hold half of the failed tests', () => {
    // build #14 of Mini-UI-Automation: 10 failed tests in groups of 3, 2, 1, 1, 1, 1, 1
    const [first] = severalFailures.failureGroups;
    const failureGroups = [3, 2, 1, 1, 1, 1, 1].map((count, index) => ({ ...first, id: `G${index + 1}`, failureCount: count }));
    render(<AiAnalysis analysis={{ ...severalFailures, failureGroups }} />);

    expect(screen.getByText('10 başarısız testten 5 tanesi G1 ve G2\'de toplanıyor; önce bu gruplara bakın.')).toBeTruthy();
  });

  it('keeps the group\'s signature and stack trace under TEKNİK DETAY', () => {
    render(<AiAnalysis analysis={severalFailures} />);

    fireEvent.click(screen.getByText('TEKNİK DETAY'));

    expect(screen.getByText(/at LoginSteps\.clickLogin\(LoginSteps\.java:31\)/)).toBeTruthy();
  });

  it('starts another analysis with its own first test selected', () => {
    const { rerender } = render(<AiAnalysis analysis={severalFailures} />);
    fireEvent.click(screen.getByText('Login 3'));

    // another build whose G1 has only one test: no stale selection, its test is shown
    const other: Analysis = {
      ...severalFailures,
      id: 'other',
      failureGroups: [
        { ...severalFailures.failureGroups[0], failureCount: 1, failures: [severalFailures.failureGroups[0].failures[1]] },
        severalFailures.failureGroups[1],
      ],
    };
    rerender(<AiAnalysis analysis={other} />);

    // once in the list and once in the IDE view (with a stale selection the IDE view would be empty)
    expect(screen.getAllByText('Login 2')).toHaveLength(2);
    expect(screen.getByText('11')).toBeTruthy();
    // two groups of one test each: no group stands out, so no "look here first" line
    expect(screen.queryByText(/toplanıyor/)).toBeNull();
  });

  it('lists the groups and the failed tests as list items with buttons', () => {
    const { container } = render(<AiAnalysis analysis={severalFailures} />);

    const lists = container.querySelectorAll('ul');
    expect(lists).toHaveLength(2); // the groups on the left, the selected group's tests on the right
    lists.forEach((list) => expect(Array.from(list.children).every((child) => child.tagName === 'LI')).toBe(true));
    expect(lists[0].querySelectorAll('li [role="button"]')).toHaveLength(3);
    expect(lists[1].querySelectorAll('li [role="button"]')).toHaveLength(3);
  });

  it('invents no file or line for a test the log shows without them', () => {
    render(<AiAnalysis analysis={{ ...severalFailures, failureGroups: [severalFailures.failureGroups[1], severalFailures.failureGroups[0]] }} />);

    // the plain JUnit test is listed by name only, with its error, and no feature file / line
    expect(screen.getAllByText('com.acme.OrderApiTest.createOrder').length).toBeGreaterThan(0);
    expect(screen.getByText('AssertionError: expected [201] but found [500]')).toBeTruthy();
    expect(screen.queryByText(/OrderApiTest.*\.feature/)).toBeNull();
    // no code location either: in its list row and in its details
    expect(screen.getAllByText('Kod konumu logda yok')).toHaveLength(2);
  });

  it('keeps the single view for one failed test', () => {
    render(<AiAnalysis analysis={{ ...failure, failureGroups: [severalFailures.failureGroups[1]] }} />);

    expect(screen.getByText('HATA ÖZETİ')).toBeTruthy();
    expect(screen.queryByText('G2')).toBeNull();
  });

  it('shows a success message when there is nothing to analyze', () => {
    render(<AiAnalysis analysis={{ ...failure, status: 'SUCCESS', ai: null }} />);

    expect(screen.getByText(/Build başarılı/)).toBeTruthy();
    expect(screen.queryByText('HATA SEBEBİ')).toBeNull();
  });
});
