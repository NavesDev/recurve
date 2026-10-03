import { describe, expect, it } from 'vitest';
import { formatDate, formatDateTime } from './date';

// The server speaks UTC (NFR-05); the panel shows the operator's time.
// Tests pin the zone so they read the same on any machine.
const zone = 'America/Sao_Paulo';

describe('formatDate', () => {
  it('shows the local calendar day', () => {
    expect(formatDate('2026-01-15T02:00:00Z', zone)).toBe('14/01/2026');
  });

  it('shows a dash for a missing date', () => {
    expect(formatDate(null, zone)).toBe('—');
  });
});

describe('formatDateTime', () => {
  it('shows day and local time', () => {
    expect(formatDateTime('2026-01-15T13:05:00Z', zone)).toBe('15/01/2026 10:05');
  });

  it('shows a dash for a missing date', () => {
    expect(formatDateTime(undefined, zone)).toBe('—');
  });
});
