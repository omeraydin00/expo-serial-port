// Opens and configures a UART device node, and does poll-based reads.
// Java can open the file but cannot set the line speed or raw mode, hence this file.

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/serial.h>
#include <poll.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <unistd.h>

// Returned by nativeOpen when the baud rate has no termios constant.
#define ERR_BAUD (-100000)

static speed_t to_speed(int baud) {
  switch (baud) {
    case 50: return B50;
    case 75: return B75;
    case 110: return B110;
    case 134: return B134;
    case 150: return B150;
    case 200: return B200;
    case 300: return B300;
    case 600: return B600;
    case 1200: return B1200;
    case 1800: return B1800;
    case 2400: return B2400;
    case 4800: return B4800;
    case 9600: return B9600;
    case 19200: return B19200;
    case 38400: return B38400;
    case 57600: return B57600;
    case 115200: return B115200;
    case 230400: return B230400;
    case 460800: return B460800;
    case 500000: return B500000;
    case 576000: return B576000;
    case 921600: return B921600;
    case 1000000: return B1000000;
    case 1152000: return B1152000;
    case 1500000: return B1500000;
    case 2000000: return B2000000;
    case 2500000: return B2500000;
    case 3000000: return B3000000;
    case 3500000: return B3500000;
    case 4000000: return B4000000;
    default: return (speed_t) -1;
  }
}

// Returns the fd, or a negative errno, or ERR_BAUD.
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeOpen(JNIEnv *env, jclass clazz, jstring jpath,
                                                jint baud, jint data_bits, jint stop_bits,
                                                jint parity, jint flow) {
  speed_t speed = to_speed(baud);
  if (speed == (speed_t) -1) return ERR_BAUD;

  const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
  int fd = open(path, O_RDWR | O_NOCTTY | O_CLOEXEC);
  int open_errno = errno;
  (*env)->ReleaseStringUTFChars(env, jpath, path);
  if (fd < 0) return -open_errno;

  struct termios tio;
  if (tcgetattr(fd, &tio) != 0) {
    int e = errno;
    close(fd);
    return -e;
  }
  cfmakeraw(&tio);
  cfsetispeed(&tio, speed);
  cfsetospeed(&tio, speed);

  tio.c_cflag &= ~CSIZE;
  switch (data_bits) {
    case 5: tio.c_cflag |= CS5; break;
    case 6: tio.c_cflag |= CS6; break;
    case 7: tio.c_cflag |= CS7; break;
    default: tio.c_cflag |= CS8; break;
  }
  if (stop_bits == 2) tio.c_cflag |= CSTOPB; else tio.c_cflag &= ~CSTOPB;
  tio.c_cflag &= ~(PARENB | PARODD);
  if (parity == 1) tio.c_cflag |= PARENB | PARODD;
  if (parity == 2) tio.c_cflag |= PARENB;
  tio.c_cflag |= CLOCAL | CREAD;
  // flow: 0 none, 1 RTS/CTS, 2 XON/XOFF. cfmakeraw has already cleared IXON and IXOFF.
  if (flow == 1) tio.c_cflag |= CRTSCTS; else tio.c_cflag &= ~CRTSCTS;
  if (flow == 2) {
    tio.c_iflag |= IXON | IXOFF;
    tio.c_cc[VSTART] = 0x11;
    tio.c_cc[VSTOP] = 0x13;
  }
  tio.c_cc[VMIN] = 1;
  tio.c_cc[VTIME] = 0;

  if (tcsetattr(fd, TCSANOW, &tio) != 0) {
    int e = errno;
    close(fd);
    return e == EINVAL ? ERR_BAUD : -e;
  }
  tcflush(fd, TCIOFLUSH);
  return fd;
}

// Waits up to timeout_ms for data. Returns bytes read, 0 on timeout, or a negative errno.
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeRead(JNIEnv *env, jclass clazz, jint fd,
                                                jbyteArray jbuf, jint timeout_ms) {
  struct pollfd pfd = {.fd = fd, .events = POLLIN};
  int r = poll(&pfd, 1, timeout_ms);
  if (r == 0) return 0;
  if (r < 0) return errno == EINTR ? 0 : -errno;
  if (pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) return -EIO;

  jsize len = (*env)->GetArrayLength(env, jbuf);
  jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
  ssize_t n = read(fd, buf, (size_t) len);
  int read_errno = errno;
  (*env)->ReleaseByteArrayElements(env, jbuf, buf, n > 0 ? 0 : JNI_ABORT);
  if (n < 0) return read_errno == EAGAIN || read_errno == EINTR ? 0 : -read_errno;
  return (jint) n;
}

// Writes all of len bytes. Returns 0, or a negative errno.
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeWrite(JNIEnv *env, jclass clazz, jint fd,
                                                 jbyteArray jbuf, jint len) {
  jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
  jint done = 0;
  jint result = 0;
  while (done < len) {
    ssize_t n = write(fd, buf + done, (size_t) (len - done));
    if (n < 0) {
      if (errno == EINTR) continue;
      result = -errno;
      break;
    }
    done += (jint) n;
  }
  (*env)->ReleaseByteArrayElements(env, jbuf, buf, JNI_ABORT);
  if (result == 0) tcdrain(fd);
  return result;
}

JNIEXPORT void JNICALL
Java_expo_modules_serialport_Termios_nativeClose(JNIEnv *env, jclass clazz, jint fd) {
  close(fd);
}

// Line bits shared with Lines.kt.
#define LINE_DTR 1
#define LINE_RTS 2
#define LINE_CTS 4
#define LINE_DSR 8
#define LINE_CD 16
#define LINE_RI 32

// Returns the line bits, or a negative errno (ENOTTY/EINVAL when the driver has no modem lines).
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeGetLines(JNIEnv *env, jclass clazz, jint fd) {
  int m;
  if (ioctl(fd, TIOCMGET, &m) != 0) return -errno;
  return ((m & TIOCM_DTR) ? LINE_DTR : 0) | ((m & TIOCM_RTS) ? LINE_RTS : 0) |
         ((m & TIOCM_CTS) ? LINE_CTS : 0) | ((m & TIOCM_DSR) ? LINE_DSR : 0) |
         ((m & TIOCM_CAR) ? LINE_CD : 0) | ((m & TIOCM_RNG) ? LINE_RI : 0);
}

// Raises the lines in `set` and drops the ones in `clear` (only DTR and RTS). Returns 0 or a negative errno.
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeSetLines(JNIEnv *env, jclass clazz, jint fd, jint set,
                                                    jint clear) {
  int on = ((set & LINE_DTR) ? TIOCM_DTR : 0) | ((set & LINE_RTS) ? TIOCM_RTS : 0);
  int off = ((clear & LINE_DTR) ? TIOCM_DTR : 0) | ((clear & LINE_RTS) ? TIOCM_RTS : 0);
  if (on && ioctl(fd, TIOCMBIS, &on) != 0) return -errno;
  if (off && ioctl(fd, TIOCMBIC, &off) != 0) return -errno;
  return 0;
}

JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeSetBreak(JNIEnv *env, jclass clazz, jint fd, jboolean on) {
  return ioctl(fd, on ? TIOCSBRK : TIOCCBRK) == 0 ? 0 : -errno;
}

// Returns the driver's current RS-485 flags, or a negative errno if it has no RS-485 support.
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeGetRs485(JNIEnv *env, jclass clazz, jint fd) {
  struct serial_rs485 conf;
  memset(&conf, 0, sizeof(conf));
  if (ioctl(fd, TIOCGRS485, &conf) != 0) return -errno;
  return (jint) (conf.flags & 0x7fffffff);
}

// Sets the RS-485 mode and returns the flags the driver kept (it may drop some), or a negative errno.
JNIEXPORT jint JNICALL
Java_expo_modules_serialport_Termios_nativeSetRs485(JNIEnv *env, jclass clazz, jint fd, jint flags,
                                                    jint delay_before_ms, jint delay_after_ms) {
  struct serial_rs485 conf;
  memset(&conf, 0, sizeof(conf));
  conf.flags = (__u32) flags;
  conf.delay_rts_before_send = (__u32) delay_before_ms;
  conf.delay_rts_after_send = (__u32) delay_after_ms;
  if (ioctl(fd, TIOCSRS485, &conf) != 0) return -errno;
  memset(&conf, 0, sizeof(conf));
  if (ioctl(fd, TIOCGRS485, &conf) != 0) return -errno;
  return (jint) (conf.flags & 0x7fffffff);
}

// Creates a pseudo-terminal in raw mode. fds[0] gets the master fd (or a negative errno), fds[1]
// an fd on the pts side, kept open so the master does not read a hangup before the app opens it.
// Returns the pts path, or NULL on failure.
JNIEXPORT jstring JNICALL
Java_expo_modules_serialport_Termios_nativeCreatePty(JNIEnv *env, jclass clazz, jintArray jfds) {
  jint fds[2] = {-1, -1};
  char name[64];
  jstring path = NULL;
  struct termios tio;
  int slave;

  int master = posix_openpt(O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (master < 0) {
    fds[0] = -errno;
    goto out;
  }
  if (grantpt(master) != 0 || unlockpt(master) != 0 || ptsname_r(master, name, sizeof(name)) != 0) {
    fds[0] = -errno;
    close(master);
    goto out;
  }
  slave = open(name, O_RDWR | O_NOCTTY | O_CLOEXEC);
  if (slave < 0) {
    fds[0] = -errno;
    close(master);
    goto out;
  }
  if (tcgetattr(slave, &tio) == 0) {
    cfmakeraw(&tio);
    tcsetattr(slave, TCSANOW, &tio);
  }
  fds[0] = master;
  fds[1] = slave;
  path = (*env)->NewStringUTF(env, name);

out:
  (*env)->SetIntArrayRegion(env, jfds, 0, 2, fds);
  return path;
}
