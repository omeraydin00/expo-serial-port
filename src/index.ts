import { OpenOptions, PortInfo, UartPortInfo, VirtualPortOptions } from './ExpoSerialPort.types';
import Native from './ExpoSerialPortModule';
import { SerialError, toSerialError } from './SerialError';
import { SerialPort } from './SerialPort';
import { VirtualPort } from './VirtualPort';
import { normalizeFraming, normalizeOptions } from './options';

export * from './ExpoSerialPort.types';
export { SerialError } from './SerialError';
export { SerialPort } from './SerialPort';
export { VirtualPort } from './VirtualPort';

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
  if (info.kind === 'usb' && native.rs485.enabled) {
    throw new SerialError(
      'INVALID_OPTIONS',
      'rs485 only applies to UART ports; USB-RS485 adapters switch direction themselves'
    );
  }
  try {
    const { handle, capabilities } =
      info.kind === 'uart'
        ? await Native.openUart(info.path, native)
        : await Native.openUsb(info.deviceId, info.port ?? 0, native);
    const serialPort = new SerialPort(info, handle, capabilities);
    await Native.startReading(handle);
    return serialPort;
  } catch (e) {
    throw toSerialError(e);
  }
}

/**
 * Creates a pseudo-terminal: open `port.path` like a UART and play the device on `port`.
 * For trying an app without hardware. Needs no root.
 */
export async function createVirtualPort(options: VirtualPortOptions = {}): Promise<VirtualPort> {
  if (!Native) unsupported();
  const framing = normalizeFraming(options?.framing ?? { type: 'raw' });
  try {
    const { handle, path } = await Native.createVirtual(framing);
    const port = new VirtualPort(path, handle);
    await Native.startReading(handle);
    return port;
  } catch (e) {
    throw toSerialError(e);
  }
}
