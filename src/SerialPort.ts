import { Capabilities, LineState, PortInfo, Subscription } from './ExpoSerialPort.types';
import Native from './ExpoSerialPortModule';
import { SerialError, toSerialError } from './SerialError';
import { Endpoint, track, untrack } from './registry';
import { utf8 } from './utf8';

type DataListener = (bytes: Uint8Array) => void;
type ErrorListener = (error: SerialError) => void;
type LinesListener = (lines: LineState) => void;

const DTR = 1;
const RTS = 2;

export class SerialPort implements Endpoint {
  readonly info: PortInfo;
  readonly capabilities: Capabilities;
  private readonly handle: number;
  private open = true;
  private dataListeners = new Set<DataListener>();
  private errorListeners = new Set<ErrorListener>();
  private linesListeners = new Set<LinesListener>();

  /** @internal Use `open()`. */
  constructor(info: PortInfo, handle: number, capabilities: Capabilities) {
    this.info = info;
    this.handle = handle;
    this.capabilities = capabilities;
    track(handle, this);
  }

  get isOpen() {
    return this.open;
  }

  /** Writes bytes, or a string as UTF-8. Resolves once the data has been handed to the driver. */
  async write(data: Uint8Array | string): Promise<void> {
    this.ensureOpen();
    const bytes = typeof data === 'string' ? utf8(data) : data;
    if (!(bytes instanceof Uint8Array))
      throw new SerialError('INVALID_OPTIONS', 'write() takes a Uint8Array or a string');
    await call(() => Native!.write(this.handle, bytes));
  }

  /** Raises (true) or drops (false) DTR and RTS. Lines left out are not changed. */
  async setLines(lines: { dtr?: boolean; rts?: boolean }): Promise<void> {
    this.ensureOpen();
    let set = 0;
    let clear = 0;
    for (const [name, bit] of [
      ['dtr', DTR],
      ['rts', RTS],
    ] as const) {
      const value = lines?.[name];
      if (value === undefined) continue;
      if (typeof value !== 'boolean')
        throw new SerialError('INVALID_OPTIONS', `setLines: ${name} must be a boolean`);
      if (value) set |= bit;
      else clear |= bit;
    }
    if (set === 0 && clear === 0) return;
    await call(() => Native!.setLines(this.handle, set, clear));
  }

  /** The current state of the lines in `capabilities.lines`. */
  async getLines(): Promise<LineState> {
    this.ensureOpen();
    return call(() => Native!.getLines(this.handle));
  }

  /** Called with the current state of the lines, then every time one changes. Polls every 20 ms while listened to. */
  onLinesChange(listener: LinesListener): Subscription {
    if (this.capabilities.lines.length === 0)
      throw new SerialError('NOT_SUPPORTED', `${describe(this.info)} has no control lines`);
    this.linesListeners.add(listener);
    if (this.linesListeners.size === 1 && this.open) this.watchLines(true);
    return {
      remove: () => {
        if (this.linesListeners.delete(listener) && this.linesListeners.size === 0 && this.open)
          this.watchLines(false);
      },
    };
  }

  /** Holds the line in the break state for `ms` milliseconds, after any writes before it. */
  async sendBreak(ms: number): Promise<void> {
    this.ensureOpen();
    if (!Number.isInteger(ms) || ms < 1 || ms > 10000)
      throw new SerialError('INVALID_OPTIONS', 'sendBreak: ms must be an integer from 1 to 10000');
    await call(() => Native!.sendBreak(this.handle, ms));
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

  private ensureOpen() {
    if (!this.open || !Native) throw new SerialError('NOT_OPEN', `${describe(this.info)} is closed`);
  }

  private watchLines(on: boolean) {
    Native?.watchLines(this.handle, on).catch((e) => this._emitError(toSerialError(e)));
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
  _emitLines(lines: LineState) {
    this.linesListeners.forEach((l) => l(lines));
  }

  /** @internal */
  _markClosed() {
    this.open = false;
    untrack(this.handle);
  }
}

async function call<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn();
  } catch (e) {
    throw toSerialError(e);
  }
}

export function describe(info: PortInfo) {
  return info.kind === 'uart' ? info.path : `USB device ${info.deviceId} port ${info.port}`;
}
