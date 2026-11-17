import * as Serial from '../index';

jest.mock('../ExpoSerialPortModule', () => ({ __esModule: true, default: null }));

it('lists nothing without the native module', async () => {
  expect(await Serial.list()).toEqual([]);
});

it('rejects open with UNSUPPORTED_PLATFORM', async () => {
  await expect(Serial.open('/dev/ttyS1', { baudRate: 9600 })).rejects.toMatchObject({
    code: 'UNSUPPORTED_PLATFORM',
  });
});
