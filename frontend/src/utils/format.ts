import type { TestSummary } from '../types/analysis';

/** "2026-09-29T14:32:10Z" → "29.09.2026 17:32" (local time). */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('tr-TR', { dateStyle: 'short', timeStyle: 'short' });
}

/** "30 toplam · 20 başarılı · 10 başarısız · 0 atlanan" — only the counts the log printed. */
export function formatTestSummary(summary: TestSummary): string {
  const parts = [
    summary.total !== null ? `${summary.total} toplam` : null,
    summary.passed !== null ? `${summary.passed} başarılı` : null,
    summary.failed !== null ? `${summary.failed} başarısız` : null,
    summary.skipped !== null ? `${summary.skipped} atlanan` : null,
  ];
  return parts.filter(Boolean).join(' · ');
}

/** "org.openqa.selenium.TimeoutException" → "TimeoutException". */
export function simpleName(type: string): string {
  return type.substring(type.lastIndexOf('.') + 1);
}
