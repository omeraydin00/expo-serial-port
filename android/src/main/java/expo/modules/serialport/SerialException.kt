package expo.modules.serialport

import expo.modules.kotlin.exception.CodedException

internal class SerialException(code: String, message: String, cause: Throwable? = null) :
  CodedException(code, message, cause)
