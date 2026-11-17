import { NativeOptions } from './ExpoSerialPortModule';
import { OpenOptions } from './ExpoSerialPort.types';
import { SerialError } from './SerialError';

function invalid(message: string): never {
  throw new SerialError('INVALID_OPTIONS', message);
}

const isByte = (n: unknown) => Number.isInteger(n) && (n as number) >= 0 && (n as number) <= 255;

/** Checks the options and fills in the defaults, so the native side gets a complete, valid object. */
export function normalizeOptions(options: OpenOptions): NativeOptions {
  if (!options || typeof options !== 'object') invalid('options are required: { baudRate }');
  const { baudRate, dataBits = 8, stopBits = 1, parity = 'none', framing = { type: 'raw' }, useSu = false } = options;

  if (!Number.isInteger(baudRate) || baudRate <= 0) invalid(`baudRate must be a positive integer, got ${baudRate}`);
  if (![5, 6, 7, 8].includes(dataBits)) invalid(`dataBits must be 5, 6, 7 or 8, got ${dataBits}`);
  if (stopBits !== 1 && stopBits !== 2) invalid(`stopBits must be 1 or 2, got ${stopBits}`);
  if (!['none', 'odd', 'even'].includes(parity)) invalid(`parity must be 'none', 'odd' or 'even', got ${parity}`);
  if (typeof useSu !== 'boolean') invalid('useSu must be a boolean');

  const native = { type: framing?.type, bytes: [] as number[], size: 0, ms: 0 };
  switch (framing?.type) {
    case 'raw':
      break;
    case 'delimiter':
      if (!Array.isArray(framing.bytes) || framing.bytes.length === 0 || !framing.bytes.every(isByte)) {
        invalid('framing.bytes must be a non-empty array of bytes (0-255)');
      }
      native.bytes = [...framing.bytes];
      break;
    case 'length':
      if (!Number.isInteger(framing.size) || framing.size <= 0) invalid('framing.size must be a positive integer');
      native.size = framing.size;
      break;
    case 'idle':
      if (!Number.isInteger(framing.ms) || framing.ms <= 0) invalid('framing.ms must be a positive integer');
      native.ms = framing.ms;
      break;
    default:
      invalid(`framing.type must be 'raw', 'delimiter', 'length' or 'idle'`);
  }

  return { baudRate, dataBits, stopBits, parity, useSu, framing: native };
}
