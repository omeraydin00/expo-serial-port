import { NativeModule, requireOptionalNativeModule } from 'expo';

import { Capabilities, LineState, OpenOptions, PortInfo } from './ExpoSerialPort.types';

export type NativeFraming = { type: string; bytes: number[]; size: number; ms: number };

export type NativeRs485 = {
  enabled: boolean;
  rtsOnSend: boolean;
  rtsAfterSend: boolean;
  delayBeforeSendMs: number;
  delayAfterSendMs: number;
  receiveDuringSend: boolean;
};

export type NativeOptions = Required<Omit<OpenOptions, 'framing' | 'rs485'>> & {
  framing: NativeFraming;
  rs485: NativeRs485;
};

export type NativeOpened = { handle: number; capabilities: Capabilities };

type Events = {
  onData: (event: { handle: number; data: Uint8Array }) => void;
  onError: (event: { handle: number; code: string; message: string }) => void;
  onLines: (event: { handle: number; lines: LineState }) => void;
};

declare class ExpoSerialPortModule extends NativeModule<Events> {
  list(): Promise<PortInfo[]>;
  requestUsbPermission(deviceId: number): Promise<boolean>;
  openUart(path: string, options: NativeOptions): Promise<NativeOpened>;
  openUsb(deviceId: number, port: number, options: NativeOptions): Promise<NativeOpened>;
  createVirtual(framing: NativeFraming): Promise<{ handle: number; path: string }>;
  startReading(handle: number): Promise<void>;
  write(handle: number, data: Uint8Array): Promise<void>;
  getLines(handle: number): Promise<LineState>;
  /** Bit masks: dtr 1, rts 2. */
  setLines(handle: number, set: number, clear: number): Promise<void>;
  sendBreak(handle: number, ms: number): Promise<void>;
  watchLines(handle: number, on: boolean): Promise<void>;
  close(handle: number): Promise<void>;
}

/** null on iOS and web, where there is no native module. */
export default requireOptionalNativeModule<ExpoSerialPortModule>('ExpoSerialPort');
