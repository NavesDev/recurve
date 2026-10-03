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
