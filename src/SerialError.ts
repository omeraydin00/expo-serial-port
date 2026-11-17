import { SerialErrorCode } from './ExpoSerialPort.types';

export class SerialError extends Error {
  readonly code: SerialErrorCode;

  constructor(code: SerialErrorCode, message: string) {
    super(message);
    this.name = 'SerialError';
    this.code = code;
  }
}

/** Native rejections carry a `code`; keep it, and the message without Expo's "Call to function ..." wrapper. */
export function toSerialError(e: unknown): SerialError {
  if (e instanceof SerialError) return e;
  const err = e as { code?: string; message?: string };
  const message = err?.message ?? String(e);
  const cause = message.split('Caused by: ');
  return new SerialError(
    (err?.code ?? 'OPEN_FAILED') as SerialErrorCode,
    cause[cause.length - 1].trim()
  );
}
