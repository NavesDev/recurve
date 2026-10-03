export interface FieldError {
  field: string;
  message: string;
}

/**
 * The transport layer's only error: every failure of a request becomes
 * one, with the server's status and message when it sent them. Status 0
 * means the server was never reached.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly fieldErrors: readonly FieldError[];

  constructor(status: number, message: string, fieldErrors: readonly FieldError[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.fieldErrors = fieldErrors;
  }

  static network(cause: unknown): ApiError {
    const error = new ApiError(0, 'Network failure');
    error.cause = cause;
    return error;
  }
}
