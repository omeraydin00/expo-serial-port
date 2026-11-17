import { NativeModule, requireOptionalNativeModule } from 'expo';

import { OpenOptions, PortInfo } from './ExpoSerialPort.types';

export type NativeOptions = Required<Omit<OpenOptions, 'framing'>> & {
  framing: { type: string; bytes: number[]; size: number; ms: number };
};

type Events = {
  onData: (event: { handle: number; data: Uint8Array }) => void;
  onError: (event: { handle: number; code: string; message: string }) => void;
};

declare class ExpoSerialPortModule extends NativeModule<Events> {
  list(): Promise<PortInfo[]>;
  requestUsbPermission(deviceId: number): Promise<boolean>;
  openUart(path: string, options: NativeOptions): Promise<number>;
  openUsb(deviceId: number, port: number, options: NativeOptions): Promise<number>;
  startReading(handle: number): Promise<void>;
  write(handle: number, data: Uint8Array): Promise<void>;
  close(handle: number): Promise<void>;
}

/** null on iOS and web, where there is no native module. */
export default requireOptionalNativeModule<ExpoSerialPortModule>('ExpoSerialPort');
