/** Include a timezone so an operator can review the committed expiry unambiguously. */
export function formatGrantExpiry(expiresAt: string, locale?: string, timeZone?: string): string {
  return new Date(expiresAt).toLocaleString(locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
    timeZoneName: 'short',
    ...(timeZone ? { timeZone } : {}),
  });
}
