package ch.mibex.bamboo.shipit

import org.apache.log4j.Logger


trait Logging {
  self => // self-type allows to use the class that mixes this trait for logging

  protected val log = Logger.getLogger(this.getClass)

  def debug[T](msg: => T): Unit = { // lazy function parameters to only evaluate msg when debug logging is enabled
//    if (log.isDebugEnabled) {
      log.error(msg.toString)
//    }
  }

}