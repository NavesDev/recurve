import { z } from 'zod';
import { parseMoney } from '../../shared/format/money';

/** An amount typed in the form: greater than zero, two decimal places at most (FR-02.2, NFR-06). */
export const amount = z.string().superRefine((text, context) => {
  const value = parseMoney(text);
  if (value === null) context.addIssue({ code: 'custom', message: 'Valor inválido. Use o formato 0,00.' });
  else if (value <= 0) context.addIssue({ code: 'custom', message: 'O valor deve ser maior que zero.' });
});

/** The same, but empty is allowed: no price yet. */
export const optionalAmount = z.string().superRefine((text, context) => {
  if (text.trim() === '') return;
  const value = parseMoney(text);
  if (value === null) context.addIssue({ code: 'custom', message: 'Valor inválido. Use o formato 0,00.' });
  else if (value <= 0) context.addIssue({ code: 'custom', message: 'O valor deve ser maior que zero.' });
});
