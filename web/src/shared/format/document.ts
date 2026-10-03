const CPF_LENGTH = 11;
const CNPJ_LENGTH = 14;

export function digitsOf(text: string): string {
  return text.replace(/\D/g, '');
}

/**
 * A CPF or CNPJ with its punctuation, as far as it has been typed: up to
 * eleven digits read as a CPF, beyond that as a CNPJ.
 */
export function formatDocument(text: string): string {
  const digits = digitsOf(text).slice(0, CNPJ_LENGTH);
  const pattern = digits.length <= CPF_LENGTH ? '###.###.###-##' : '##.###.###/####-##';
  let result = '';
  let next = 0;
  for (const symbol of pattern) {
    if (next >= digits.length) break;
    result += symbol === '#' ? digits[next++] : symbol;
  }
  return result;
}
