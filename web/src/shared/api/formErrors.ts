import type { FieldPath, FieldValues, UseFormSetError } from 'react-hook-form';
import { ApiError } from './ApiError';

/**
 * Puts the server's field errors on the form's fields. Answers true when
 * every error found a field; false leaves the caller to say it otherwise.
 */
export function applyFieldErrors<T extends FieldValues>(
  error: unknown,
  setError: UseFormSetError<T>,
  fields: readonly FieldPath<T>[],
): boolean {
  if (!(error instanceof ApiError) || error.fieldErrors.length === 0) return false;
  let all = true;
  for (const fieldError of error.fieldErrors) {
    const field = fields.find((name) => name === fieldError.field);
    if (field) setError(field, { type: 'server', message: fieldError.message });
    else all = false;
  }
  return all;
}

/** A failure to show above the form: any but a refusal whose field errors the fields already show. */
export function isFormLevelError(error: unknown): boolean {
  return Boolean(error) && !(error instanceof ApiError && error.fieldErrors.length > 0);
}
