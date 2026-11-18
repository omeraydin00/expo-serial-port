export type UartPortInfo = {
  kind: 'uart';
  /** Device node, e.g. `/dev/ttyS3`. */
  path: string;
  /** Whether the app can open the node as it is (without `useSu`). */
  readable: boolean;
  writable: boolean;
};

export type UsbPortInfo = {
  kind: 'usb';
  /** Android's id for the attached device. Changes when the adapter is plugged in again. */
  deviceId: number;
  /** Port number on adapters with more than one port, otherwise 0. */
  port: number;
  vendorId: number;
  productId: number;
  /** Driver picked by usb-serial-for-android, e.g. `Ch34x`, `Ftdi`, `Cp21xx`, `Cdc`. */
  driver: string;
  name: string | null;
  hasPermission: boolean;
};

export type PortInfo = UartPortInfo | UsbPortInfo;

export type Framing =
  | { type: 'raw' }
  /** Packets end with these bytes; the delimiter is not part of the packet. */
  | { type: 'delimiter'; bytes: number[] }
  /** Packets of exactly `size` bytes. */
  | { type: 'length'; size: number }
  /** A packet is what arrived before the line stayed quiet for `ms`. */
  | { type: 'idle'; ms: number };

export type OpenOptions = {
  baudRate: number;
  /** Default 8. */
  dataBits?: 5 | 6 | 7 | 8;
  /** Default 1. */
  stopBits?: 1 | 2;
  /** Default `'none'`. */
  parity?: 'none' | 'odd' | 'even';
  /** Default `{ type: 'raw' }`: every chunk as it is read. */
  framing?: Framing;
  /** UART only. If the node is not readable and writable, run `chmod 666` on it through `su` first. Default false. */
  useSu?: boolean;
  /** Default `'none'`. A mode the port does not support rejects `open()` with `NOT_SUPPORTED`. */
  flowControl?: FlowControl;
  /**
   * UART only. Puts the driver in RS-485 mode, so it switches RTS (the transceiver's direction) around
   * every write. `true` uses the defaults below. Rejects with `NOT_SUPPORTED` if the driver has no RS-485 mode.
   */
  rs485?: boolean | Rs485Options;
};

export type Rs485Options = {
  /** RTS level while sending. Default true. */
  rtsOnSend?: boolean;
  /** RTS level after sending. Default false. */
  rtsAfterSend?: boolean;
  /** Default 0. */
  delayBeforeSendMs?: number;
  /** Default 0. */
  delayAfterSendMs?: number;
  /** Keep receiving while sending (and so read back your own writes). Default false. */
  receiveDuringSend?: boolean;
};

export type Line = 'dtr' | 'rts' | 'cts' | 'dsr' | 'cd' | 'ri';

/** `dtr-dsr` is USB only. */
export type FlowControl = 'none' | 'rts-cts' | 'dtr-dsr' | 'xon-xoff';

/** The state of the lines in `capabilities.lines`. */
export type LineState = Partial<Record<Line, boolean>>;

/** What a port can do beyond reading and writing. Calls outside it reject with `NOT_SUPPORTED`. */
export type Capabilities = {
  lines: Line[];
  flowControl: FlowControl[];
  breakSignal: boolean;
  rs485: boolean;
};

export type VirtualPortOptions = {
  /** How the device side splits what the app writes. Default `{ type: 'raw' }`. */
  framing?: Framing;
};

export type SerialErrorCode =
  | 'NOT_FOUND'
  | 'PERMISSION_DENIED'
  | 'USB_PERMISSION_REQUIRED'
  | 'UNSUPPORTED_BAUD_RATE'
  | 'INVALID_OPTIONS'
  | 'ALREADY_OPEN'
  | 'NOT_OPEN'
  | 'OPEN_FAILED'
  | 'WRITE_FAILED'
  | 'DISCONNECTED'
  | 'NOT_SUPPORTED'
  | 'CONTROL_FAILED'
  | 'UNSUPPORTED_PLATFORM';

export type Subscription = { remove(): void };
