import { Framing, OpenOptions } from './ExpoSerialPort.types';
import { NativeFraming, NativeOptions, NativeRs485 } from './ExpoSerialPortModule';
import { SerialError } from './SerialError';

function invalid(message: string): never {
  throw new SerialError('INVALID_OPTIONS', message);
}

const isByte = (n: unknown) => Number.isInteger(n) && (n as number) >= 0 && (n as number) <= 255;

/** Checks the options and fills in the defaults, so the native side gets a complete, valid object. */
export function normalizeOptions(options: OpenOptions): NativeOptions {
  if (!options || typeof options !== 'object') invalid('options are required: { baudRate }');
  const {
    baudRate,
    dataBits = 8,
    stopBits = 1,
    parity = 'none',
    framing = { type: 'raw' },
    useSu = false,
    flowControl = 'none',
    rs485 = false,
  } = options;

  if (!Number.isInteger(baudRate) || baudRate <= 0)
    invalid(`baudRate must be a positive integer, got ${baudRate}`);
  if (![5, 6, 7, 8].includes(dataBits)) invalid(`dataBits must be 5, 6, 7 or 8, got ${dataBits}`);
  if (stopBits !== 1 && stopBits !== 2) invalid(`stopBits must be 1 or 2, got ${stopBits}`);
  if (!['none', 'odd', 'even'].includes(parity))
    invalid(`parity must be 'none', 'odd' or 'even', got ${parity}`);
  if (typeof useSu !== 'boolean') invalid('useSu must be a boolean');
  if (!['none', 'rts-cts', 'dtr-dsr', 'xon-xoff'].includes(flowControl))
    invalid(`flowControl must be 'none', 'rts-cts', 'dtr-dsr' or 'xon-xoff', got ${flowControl}`);

  return {
    baudRate,
    dataBits,
    stopBits,
    parity,
    useSu,
    flowControl,
    rs485: normalizeRs485(rs485),
    framing: normalizeFraming(framing),
  };
}

function normalizeRs485(rs485: OpenOptions['rs485']): NativeRs485 {
  const off = {
    enabled: false,
    rtsOnSend: true,
    rtsAfterSend: false,
    delayBeforeSendMs: 0,
    delayAfterSendMs: 0,
    receiveDuringSend: false,
  };
  if (rs485 === false) return off;
  if (rs485 === true) return { ...off, enabled: true };
  if (!rs485 || typeof rs485 !== 'object') invalid('rs485 must be a boolean or an object');
  const out = { ...off, enabled: true };
  for (const key of Object.keys(off) as (keyof NativeRs485)[]) {
    if (key !== 'enabled' && rs485[key] !== undefined) (out as any)[key] = rs485[key];
  }
  for (const key of ['rtsOnSend', 'rtsAfterSend', 'receiveDuringSend'] as const) {
    if (typeof out[key] !== 'boolean') invalid(`rs485.${key} must be a boolean`);
  }
  for (const key of ['delayBeforeSendMs', 'delayAfterSendMs'] as const) {
    if (!Number.isInteger(out[key]) || out[key] < 0 || out[key] > 1000)
      invalid(`rs485.${key} must be an integer from 0 to 1000`);
  }
  return out;
}

/** Checks a framing and fills in the unused fields. */
export function normalizeFraming(framing: Framing): NativeFraming {
  const native = { type: framing?.type, bytes: [] as number[], size: 0, ms: 0 };
  switch (framing?.type) {
    case 'raw':
      break;
    case 'delimiter':
      if (
        !Array.isArray(framing.bytes) ||
        framing.bytes.length === 0 ||
        !framing.bytes.every(isByte)
      ) {
        invalid('framing.bytes must be a non-empty array of bytes (0-255)');
      }
      native.bytes = [...framing.bytes];
      break;
    case 'length':
      if (!Number.isInteger(framing.size) || framing.size <= 0)
        invalid('framing.size must be a positive integer');
      native.size = framing.size;
      break;
    case 'idle':
      if (!Number.isInteger(framing.ms) || framing.ms <= 0)
        invalid('framing.ms must be a positive integer');
      native.ms = framing.ms;
      break;
    default:
      invalid(`framing.type must be 'raw', 'delimiter', 'length' or 'idle'`);
  }
  return native;
}
