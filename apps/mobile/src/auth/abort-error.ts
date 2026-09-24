/** Hermes does not provide DOMException; callers can still recognize cancellation by name. */
export function nativeAbortError(message: string): Error {
  const error = new Error(message);
  error.name = 'AbortError';
  return error;
}
