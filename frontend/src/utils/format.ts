/** "2026-09-29T14:32:10Z" → "29.09.2026 17:32" (local time). */
export function formatDateTime(iso: string): string {
  return new Date(iso).toLocaleString('tr-TR', { dateStyle: 'short', timeStyle: 'short' });
}
