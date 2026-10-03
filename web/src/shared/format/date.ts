const MISSING = '—';

type Instant = string | null | undefined;

/** The server's UTC instant (NFR-05) as the operator's calendar day. */
export function formatDate(instant: Instant, timeZone?: string): string {
  if (!instant) return MISSING;
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeZone }).format(new Date(instant));
}

/** The server's UTC instant as the operator's day and time. */
export function formatDateTime(instant: Instant, timeZone?: string): string {
  if (!instant) return MISSING;
  return new Intl.DateTimeFormat('pt-BR', { dateStyle: 'short', timeStyle: 'short', timeZone })
    .format(new Date(instant))
    .replace(',', '');
}
