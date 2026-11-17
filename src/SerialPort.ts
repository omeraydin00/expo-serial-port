import Native from './ExpoSerialPortModule';
import { PortInfo, Subscription } from './ExpoSerialPort.types';
import { SerialError, toSerialError } from './SerialError';
import { utf8 } from './utf8';

type DataListener = (bytes: Uint8Array) => void;
type ErrorListener = (error: SerialError) => void;

const openPorts = new Map<number, SerialPort>();
let nativeSubscriptions: Subscription[] = [];

/** One pair of native listeners for all ports, routed by handle. */
function ensureNativeListeners() {
  if (!Native || nativeSubscriptions.length > 0) return;
  nativeSubscriptions = [
    Native.addListener('onData', ({ handle, data }) => openPorts.get(handle)?._emitData(data)),
    Native.addListener('onError', ({ handle, code, message }) => {
      const port = openPorts.get(handle);
      if (!port) return;
      port._markClosed();
      port._emitError(new SerialError(code as SerialError['code'], message));
    }),
  ];
}

function releaseNativeListenersIfIdle() {
  if (openPorts.size > 0) return;
  nativeSubscriptions.forEach((s) => s.remove());
  nativeSubscriptions = [];
}

export class SerialPort {
  readonly info: PortInfo;
  private readonly handle: number;
  private open = true;
  private dataListeners = new Set<DataListener>();
  private errorListeners = new Set<ErrorListener>();

  /** @internal Use `open()`. */
  constructor(info: PortInfo, handle: number) {
    this.info = info;
    this.handle = handle;
    openPorts.set(handle, this);
    ensureNativeListeners();
  }

  get isOpen() {
    return this.open;
  }

  /** Writes bytes, or a string as UTF-8. Resolves once the data has been handed to the driver. */
  async write(data: Uint8Array | string): Promise<void> {
    if (!this.open || !Native) throw new SerialError('NOT_OPEN', `${describe(this.info)} is closed`);
    const bytes = typeof data === 'string' ? utf8(data) : data;
    if (!(bytes instanceof Uint8Array)) throw new SerialError('INVALID_OPTIONS', 'write() takes a Uint8Array or a string');
    try {
      await Native.write(this.handle, bytes);
    } catch (e) {
      throw toSerialError(e);
    }
  }

  /** Called for every packet (or every chunk with raw framing). */
  onData(listener: DataListener): Subscription {
    this.dataListeners.add(listener);
    return { remove: () => this.dataListeners.delete(listener) };
  }

  /** Called once if the port dies (USB unplugged, read failure). The port is closed by then. */
  onError(listener: ErrorListener): Subscription {
    this.errorListeners.add(listener);
    return { remove: () => this.errorListeners.delete(listener) };
  }

  async close(): Promise<void> {
    if (!this.open) return;
    this._markClosed();
    await Native?.close(this.handle);
  }

  /** @internal */
  _emitData(bytes: Uint8Array) {
    this.dataListeners.forEach((l) => l(bytes));
  }

  /** @internal */
  _emitError(error: SerialError) {
    this.errorListeners.forEach((l) => l(error));
  }

  /** @internal */
  _markClosed() {
    this.open = false;
    openPorts.delete(this.handle);
    releaseNativeListenersIfIdle();
  }
}

export function describe(info: PortInfo) {
  return info.kind === 'uart' ? info.path : `USB device ${info.deviceId} port ${info.port}`;
}
