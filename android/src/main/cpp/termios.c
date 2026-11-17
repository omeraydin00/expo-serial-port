// Opens and configures a UART device node, and does poll-based reads.
// Java can open the file but cannot set the line speed or raw mode, hence this file.

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <poll.h>
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
                                                jint parity) {
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
  tio.c_cflag &= ~CRTSCTS;
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
