import { normalizeOptions } from '../options';
import { utf8 } from '../utf8';

describe('normalizeOptions', () => {
  it('fills in the defaults', () => {
    expect(normalizeOptions({ baudRate: 9600 })).toEqual({
      baudRate: 9600,
      dataBits: 8,
      stopBits: 1,
      parity: 'none',
      useSu: false,
      flowControl: 'none',
      rs485: {
        enabled: false,
        rtsOnSend: true,
        rtsAfterSend: false,
        delayBeforeSendMs: 0,
        delayAfterSendMs: 0,
        receiveDuringSend: false,
      },
      framing: { type: 'raw', bytes: [], size: 0, ms: 0 },
    });
  });

  it('checks flow control', () => {
    expect(normalizeOptions({ baudRate: 1, flowControl: 'rts-cts' }).flowControl).toBe('rts-cts');
    expect(() => normalizeOptions({ baudRate: 1, flowControl: 'hardware' as any })).toThrow(
      expect.objectContaining({ code: 'INVALID_OPTIONS' })
    );
  });

  it('turns rs485: true into the defaults', () => {
    expect(normalizeOptions({ baudRate: 1, rs485: true }).rs485).toEqual({
      enabled: true,
      rtsOnSend: true,
      rtsAfterSend: false,
      delayBeforeSendMs: 0,
      delayAfterSendMs: 0,
      receiveDuringSend: false,
    });
  });

  it('merges rs485 options over the defaults, ignoring undefined', () => {
    expect(
      normalizeOptions({
        baudRate: 1,
        rs485: { rtsOnSend: false, rtsAfterSend: true, delayAfterSendMs: 2, receiveDuringSend: undefined },
      }).rs485
    ).toEqual({
      enabled: true,
      rtsOnSend: false,
      rtsAfterSend: true,
      delayBeforeSendMs: 0,
      delayAfterSendMs: 2,
      receiveDuringSend: false,
    });
  });

  it.each([
    [{ rtsOnSend: 1 }],
    [{ delayBeforeSendMs: -1 }],
    [{ delayAfterSendMs: 1.5 }],
    [{ delayAfterSendMs: 5000 }],
  ])('rejects bad rs485 options %j', (rs485) => {
    expect(() => normalizeOptions({ baudRate: 1, rs485: rs485 as any })).toThrow(
      expect.objectContaining({ code: 'INVALID_OPTIONS' })
    );
  });

  it('passes framing through', () => {
    expect(
      normalizeOptions({ baudRate: 1, framing: { type: 'delimiter', bytes: [13, 10] } }).framing
    ).toEqual({
      type: 'delimiter',
      bytes: [13, 10],
      size: 0,
      ms: 0,
    });
    expect(normalizeOptions({ baudRate: 1, framing: { type: 'idle', ms: 20 } }).framing.ms).toBe(
      20
    );
    expect(
      normalizeOptions({ baudRate: 1, framing: { type: 'length', size: 12 } }).framing.size
    ).toBe(12);
  });

  it.each([
    [{ baudRate: 0 }, /baudRate/],
    [{ baudRate: 9600.5 }, /baudRate/],
    [{ baudRate: 9600, dataBits: 9 }, /dataBits/],
    [{ baudRate: 9600, stopBits: 3 }, /stopBits/],
    [{ baudRate: 9600, parity: 'mark' }, /parity/],
    [{ baudRate: 9600, framing: { type: 'delimiter', bytes: [] } }, /framing.bytes/],
    [{ baudRate: 9600, framing: { type: 'delimiter', bytes: [256] } }, /framing.bytes/],
    [{ baudRate: 9600, framing: { type: 'length', size: 0 } }, /framing.size/],
    [{ baudRate: 9600, framing: { type: 'idle', ms: -1 } }, /framing.ms/],
    [{ baudRate: 9600, framing: { type: 'line' } }, /framing.type/],
  ])('rejects %j', (options, message) => {
    expect(() => normalizeOptions(options as any)).toThrow(message);
    try {
      normalizeOptions(options as any);
    } catch (e: any) {
      expect(e.code).toBe('INVALID_OPTIONS');
    }
  });
});

describe('utf8', () => {
  it('encodes ASCII, Turkish letters and emoji', () => {
    expect([...utf8('PING\r\n')]).toEqual([80, 73, 78, 71, 13, 10]);
    expect([...utf8('ş')]).toEqual([0xc5, 0x9f]);
    expect([...utf8('€')]).toEqual([0xe2, 0x82, 0xac]);
    expect([...utf8('😀')]).toEqual([0xf0, 0x9f, 0x98, 0x80]);
  });

  it('replaces a lone surrogate', () => {
    expect([...utf8('\ud800')]).toEqual([0xef, 0xbf, 0xbd]);
  });
});
