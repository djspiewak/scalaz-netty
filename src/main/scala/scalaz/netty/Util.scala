package scalaz
package netty

import io.netty.channel.ChannelConfig

import concurrent.Task

import stream.Process
import stream.async.mutable.Queue

private[netty] object Util {

  def limiter(config: ChannelConfig, queue: Queue[_], limit: Int): Process[Task, Nothing] = {
    queue.size.discrete evalMap { size =>
      Task delay {
        if (size >= limit && config.isAutoRead) {
          config.setAutoRead(false)
        } else if (size < limit && !config.isAutoRead) {
          config.setAutoRead(true)
        }
      }
    } drain
  }
}