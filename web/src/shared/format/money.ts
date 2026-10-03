const formatters = new Map<string, Intl.NumberFormat>();

/** An amount in its currency, two decimal places (NFR-06), pt-BR notation. */
export function formatMoney(amount: number, currency: string): string {
  let formatter = formatters.get(currency);
  if (!formatter) {
    formatter = new Intl.NumberFormat('pt-BR', { style: 'currency', currency, minimumFractionDigits: 2 });
    formatters.set(currency, formatter);
  }
  return formatter.format(amount);
}

const AMOUNT = /^\d+(\.\d{1,2})?$/;

/**
 * An amount as an operator types it: `1.299,90` the Brazilian way, or
 * `49.9` with a decimal point. Null for anything that is not a
 * non-negative amount with at most two decimal places (NFR-06).
 */
export function parseMoney(text: string): number | null {
  const trimmed = text.trim();
  const normalized = trimmed.includes(',') ? trimmed.replace(/\./g, '').replace(',', '.') : trimmed;
  return AMOUNT.test(normalized) ? Number(normalized) : null;
}
