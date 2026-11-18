# Changelog

## 0.2.0 (2026-11-18)

- `createVirtualPort()`: a pseudo-terminal to play the device on, for trying an app without hardware.
- Control lines: `setLines()`, `getLines()`, `onLinesChange()`.
- `sendBreak(ms)`.
- `flowControl` option: `rts-cts`, `xon-xoff`, and `dtr-dsr` on USB.
- `rs485` option for UARTs whose driver has an RS-485 mode.
- `port.capabilities`, and the error codes `NOT_SUPPORTED` and `CONTROL_FAILED`.

## 0.1.1 (2026-11-17)

- README badges (npm version, CI, platform, license).

## 0.1.0 (2026-11-17)

- First version: UART ports under `/dev` and USB-serial adapters (via usb-serial-for-android), raw bytes, delimiter / length / idle framing, optional `su` for UART permissions.
