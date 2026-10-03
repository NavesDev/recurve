import { GENERIC_ERROR, KNOWN_ERRORS, STATUS_ERRORS } from '../constants/errors';
import { ApiError } from './ApiError';

/** Any failure as a sentence an operator can read, in pt-BR. */
export function describeError(error: unknown): string {
  if (!(error instanceof ApiError)) return GENERIC_ERROR;
  const known = KNOWN_ERRORS.find(({ pattern }) => pattern.test(error.message));
  return known?.message ?? STATUS_ERRORS[error.status] ?? GENERIC_ERROR;
}
