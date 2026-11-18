import { LineState, Subscription } from './ExpoSerialPort.types';
import Native from './ExpoSerialPortModule';
import { SerialError } from './SerialError';

/** Anything with a native handle: a SerialPort or the device side of a VirtualPort. */
export interface Endpoint {
  _emitData(bytes: Uint8Array): void;
  _emitError(error: SerialError): void;
  _emitLines(lines: LineState): void;
  _markClosed(): void;
}

const endpoints = new Map<number, Endpoint>();
let nativeSubscriptions: Subscription[] = [];

/** One set of native listeners for all endpoints, routed by handle. */
export function track(handle: number, endpoint: Endpoint) {
  endpoints.set(handle, endpoint);
  if (!Native || nativeSubscriptions.length > 0) return;
  nativeSubscriptions = [
    Native.addListener('onData', ({ handle, data }) => endpoints.get(handle)?._emitData(data)),
    Native.addListener('onLines', ({ handle, lines }) => endpoints.get(handle)?._emitLines(lines)),
    Native.addListener('onError', ({ handle, code, message }) => {
      const endpoint = endpoints.get(handle);
      if (!endpoint) return;
      endpoint._markClosed();
      endpoint._emitError(new SerialError(code as SerialError['code'], message));
    }),
  ];
}

export function untrack(handle: number) {
  endpoints.delete(handle);
  if (endpoints.size > 0) return;
  nativeSubscriptions.forEach((s) => s.remove());
  nativeSubscriptions = [];
}
