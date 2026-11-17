import * as Serial from '../index';

type Listener = (e: any) => void;

const listeners: Record<string, Set<Listener>> = { onData: new Set(), onError: new Set() };
const emit = (name: string, event: any) => listeners[name].forEach((l) => l(event));
let nextHandle = 1;
const calls: string[] = [];

const mockNative = {
  addListener: jest.fn((name: string, l: Listener) => {
    listeners[name].add(l);
    return { remove: () => listeners[name].delete(l) };
  }),
  list: jest.fn(async () => [{ kind: 'uart', path: '/dev/ttyS1', readable: true, writable: true }]),
  requestUsbPermission: jest.fn(async () => true),
  openUart: jest.fn(async (path: string) => {
    if (path === '/dev/locked')
      throw Object.assign(
        new Error(
          "Call to function 'ExpoSerialPort.openUart' has been rejected.\n→ Caused by: No read/write access to /dev/locked"
        ),
        { code: 'PERMISSION_DENIED' }
      );
    calls.push('openUart');
    return nextHandle++;
  }),
  openUsb: jest.fn(async () => nextHandle++),
  startReading: jest.fn(async () => {
    calls.push('startReading');
  }),
  write: jest.fn(async () => {}),
  close: jest.fn(async () => {}),
};

// A getter, because the import below is hoisted above mockNative.
jest.mock('../ExpoSerialPortModule', () => ({
  __esModule: true,
  get default() {
    return mockNative;
  },
}));

beforeEach(() => {
  calls.length = 0;
  jest.clearAllMocks();
});

const usb: Serial.UsbPortInfo = {
  kind: 'usb',
  deviceId: 1003,
  port: 0,
  vendorId: 0x1a86,
  productId: 0x7523,
  driver: 'Ch34x',
  name: 'USB Serial',
  hasPermission: true,
};

it('lists ports', async () => {
  expect(await Serial.list()).toHaveLength(1);
});

it('opens a UART by path and starts reading after the port exists', async () => {
  const port = await Serial.open('/dev/ttyS1', { baudRate: 115200 });
  expect(port.info).toMatchObject({ kind: 'uart', path: '/dev/ttyS1' });
  expect(calls).toEqual(['openUart', 'startReading']);
  await port.close();
});

it('opens USB with device and port number', async () => {
  const port = await Serial.open(usb, { baudRate: 9600 });
  expect(mockNative.openUsb).toHaveBeenCalledWith(
    1003,
    0,
    expect.objectContaining({ baudRate: 9600 })
  );
  await port.close();
});

it('refuses useSu on USB', async () => {
  await expect(Serial.open(usb, { baudRate: 9600, useSu: true })).rejects.toMatchObject({
    code: 'INVALID_OPTIONS',
  });
});

it('keeps the native error code', async () => {
  await expect(Serial.open('/dev/locked', { baudRate: 9600 })).rejects.toMatchObject({
    name: 'SerialError',
    code: 'PERMISSION_DENIED',
    message: 'No read/write access to /dev/locked',
  });
});

it('routes data to the right port', async () => {
  const a = await Serial.open('/dev/ttyS1', { baudRate: 9600 });
  const b = await Serial.open('/dev/ttyS2', { baudRate: 9600 });
  const gotA: number[][] = [];
  const gotB: number[][] = [];
  a.onData((d) => gotA.push([...d]));
  b.onData((d) => gotB.push([...d]));

  emit('onData', { handle: (b as any).handle, data: new Uint8Array([1, 2]) });
  emit('onData', { handle: 999, data: new Uint8Array([9]) });

  expect(gotA).toEqual([]);
  expect(gotB).toEqual([[1, 2]]);
  await a.close();
  await b.close();
});

it('writes strings as UTF-8', async () => {
  const port = await Serial.open('/dev/ttyS1', { baudRate: 9600 });
  await port.write('ş\n');
  expect([...(mockNative.write.mock.calls[0] as any)[1]]).toEqual([0xc5, 0x9f, 0x0a]);
  await port.close();
});

it('closes the port on a native error and refuses writes after', async () => {
  const port = await Serial.open(usb, { baudRate: 9600 });
  const errors: string[] = [];
  port.onError((e) => errors.push(e.code));

  emit('onError', { handle: (port as any).handle, code: 'DISCONNECTED', message: 'unplugged' });

  expect(errors).toEqual(['DISCONNECTED']);
  expect(port.isOpen).toBe(false);
  await expect(port.write('x')).rejects.toMatchObject({ code: 'NOT_OPEN' });
});

it('drops the native listeners when no port is open', async () => {
  const port = await Serial.open('/dev/ttyS1', { baudRate: 9600 });
  expect(listeners.onData.size).toBe(1);
  await port.close();
  expect(listeners.onData.size).toBe(0);
});
