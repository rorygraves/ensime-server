// Copyright: 2010 - 2016 https://github.com/ensime/ensime-server/graphs
// Licence: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.server

import org.ensime.util.file._

import akka.event.slf4j.SLF4JLogging

object PortUtil extends SLF4JLogging {

  def port(cacheDir: File, name: String): Option[Int] = {
    val portFile = cacheDir / name
    if (portFile.exists())
      Some(portFile.readString().trim.toInt)
    else
      None
  }

  def writePort(cacheDir: File, port: Int, name: String): Unit = {
    val portFile = cacheDir / name
    if (!portFile.exists()) {
      log.info("creating port file: " + portFile)
      portFile.createNewFile()
    }

    portFile.deleteOnExit() // doesn't work on Windows
    portFile.writeString(port.toString)
  }
}
