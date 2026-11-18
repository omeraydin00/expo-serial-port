import { Subscription } from './ExpoSerialPort.types';
import Native from './ExpoSerialPortModule';
import { SerialError, toSerialError } from './SerialError';
import { Endpoint, track, untrack } from './registry';
import { utf8 } from './utf8';

type DataListener = (bytes: Uint8Array) => void;

/**
 * The device side of a pseudo-terminal. Open `path` with `open()` like any UART, and play the
 * device here: what the app writes arrives in `onData`, and `write()` sends to the app.
 */
export class VirtualPort implements Endpoint {
  readonly path: string;
  private readonly handle: number;
  private open = true;
  private dataListeners = new Set<DataListener>();

  /** @internal Use `createVirtualPort()`. */
  constructor(path: string, handle: number) {
    this.path = path;
    this.handle = handle;
    track(handle, this);
  }

  get isOpen() {
    return this.open;
  }

  /** Sends bytes, or a string as UTF-8, to the app's side. */
  async write(data: Uint8Array | string): Promise<void> {
    if (!this.open || !Native) throw new SerialError('NOT_OPEN', `Virtual port ${this.path} is closed`);
    const bytes = typeof data === 'string' ? utf8(data) : data;
    if (!(bytes instanceof Uint8Array))
      throw new SerialError('INVALID_OPTIONS', 'write() takes a Uint8Array or a string');
    try {
      await Native.write(this.handle, bytes);
    } catch (e) {
      throw toSerialError(e);
    }
  }

  /** What the app wrote, split by the framing given to `createVirtualPort()`. */
  onData(listener: DataListener): Subscription {
    this.dataListeners.add(listener);
    return { remove: () => this.dataListeners.delete(listener) };
  }

  /** Hangs up. A port the app has open on `path` fails with `DISCONNECTED`. */
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
  _emitError() {}

  /** @internal */
  _emitLines() {}

  /** @internal */
  _markClosed() {
    this.open = false;
    untrack(this.handle);
  }
}
