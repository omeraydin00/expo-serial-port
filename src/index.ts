import { OpenOptions, PortInfo, UartPortInfo } from './ExpoSerialPort.types';
import Native from './ExpoSerialPortModule';
import { SerialError, toSerialError } from './SerialError';
import { SerialPort } from './SerialPort';
import { normalizeOptions } from './options';

export * from './ExpoSerialPort.types';
export { SerialError } from './SerialError';
export { SerialPort } from './SerialPort';

function unsupported(): never {
  throw new SerialError('UNSUPPORTED_PLATFORM', 'expo-serial-port only works on Android');
}

/** UART device nodes and attached USB-serial adapters. Empty on iOS and web. */
export async function list(): Promise<PortInfo[]> {
  if (!Native) return [];
  return Native.list();
}

/** Shows Android's USB permission dialog for the adapter. Resolves with the user's answer. */
export async function requestUsbPermission(deviceId: number): Promise<boolean> {
  if (!Native) unsupported();
  try {
    return await Native.requestUsbPermission(deviceId);
  } catch (e) {
    throw toSerialError(e);
  }
}

/** Opens a port from `list()`, or a UART by path. */
export async function open(port: PortInfo | string, options: OpenOptions): Promise<SerialPort> {
  if (!Native) unsupported();
  const info: PortInfo =
    typeof port === 'string'
      ? ({ kind: 'uart', path: port, readable: true, writable: true } as UartPortInfo)
      : port;
  const native = normalizeOptions(options);
  if (info.kind === 'usb' && native.useSu) {
    throw new SerialError('INVALID_OPTIONS', 'useSu only applies to UART ports');
  }
  try {
    const handle =
      info.kind === 'uart'
        ? await Native.openUart(info.path, native)
        : await Native.openUsb(info.deviceId, info.port ?? 0, native);
    const serialPort = new SerialPort(info, handle);
    await Native.startReading(handle);
    return serialPort;
  } catch (e) {
    throw toSerialError(e);
  }
}
