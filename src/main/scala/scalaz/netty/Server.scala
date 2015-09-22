/*
 * Copyright 2015 RichRelevance
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package scalaz
package netty

import concurrent._
import stream._
import syntax.monad._

import scodec.bits.ByteVector

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import _root_.io.netty.bootstrap._
import _root_.io.netty.buffer._
import _root_.io.netty.channel._
import _root_.io.netty.channel.nio._
import _root_.io.netty.channel.socket._
import _root_.io.netty.channel.socket.nio._
import _root_.io.netty.handler.codec._

private[netty] class Server(bossGroup: NioEventLoopGroup, channel: _root_.io.netty.channel.Channel, queue: async.mutable.Queue[(InetSocketAddress, Process[Task, Exchange[ByteVector, ByteVector]])]) { server =>

  def listen: Process[Task, (InetSocketAddress, Process[Task, Exchange[ByteVector, ByteVector]])] =
    queue.dequeue

  def shutdown(implicit pool: ExecutorService): Task[Unit] = {
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close

      _ <- Task delay {
        bossGroup.shutdownGracefully()
      }
    } yield ()
  }
}

private[netty] final class ServerHandler(channel: SocketChannel, serverQueue: async.mutable.Queue[(InetSocketAddress, Process[Task, Exchange[ByteVector, ByteVector]])], limit: Int)(implicit pool: ExecutorService, S: Strategy) extends ChannelInboundHandlerAdapter {

  // data from a single connection
  private val queue = async.unboundedQueue[ByteVector]

  Util.limiter(channel.config, queue, limit).run runAsync { _ => () }          // this is... fun

  override def channelActive(ctx: ChannelHandlerContext): Unit = {
    val process: Process[Task, Exchange[ByteVector, ByteVector]] =
      Process(Exchange(read, write)) onComplete Process.eval(shutdown).drain

    // gross...
    val addr = channel.remoteAddress.asInstanceOf[InetSocketAddress]

    serverQueue.enqueueOne((addr, process)) runAsync { _ => () }

    super.channelActive(ctx)
  }

  override def channelInactive(ctx: ChannelHandlerContext): Unit = {
    // if the connection is remotely closed, we need to clean things up on our side
    queue.close runAsync { _ => () }

    super.channelInactive(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit = {
    val buf = msg.asInstanceOf[ByteBuf]
    val dst = Array.ofDim[Byte](buf.capacity())
    buf.getBytes(0, dst)

    val bv = ByteVector.view(dst)

    buf.release()

    queue.enqueueOne(bv) runAsync { _ => () }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, t: Throwable): Unit = {
    queue.fail(t) runAsync { _ => () }

    // super.exceptionCaught(ctx, t)
  }

  // do not call more than once!
  private lazy val read: Process[Task, ByteVector] = queue.dequeue

  private def write: Sink[Task, ByteVector] = {
    def inner(bv: ByteVector): Task[Unit] = {
      Task delay {
        val data = bv.toArray
        val buf = channel.alloc().buffer(data.length)
        buf.writeBytes(data)

        Netty toTask channel.writeAndFlush(buf)
      } join
    }

    // TODO termination
    Process constant (inner _)
  }

  def shutdown: Task[Unit] = {
    for {
      _ <- Netty toTask channel.close()
      _ <- queue.close
    } yield ()
  }
}

private[netty] object Server {
  def apply(bind: InetSocketAddress, config: ServerConfig)(implicit pool: ExecutorService, S: Strategy): Task[Server] = Task delay {
    val bossGroup = new NioEventLoopGroup(config.numThreads)

    //val server = new Server(bossGroup, config.limit)
    val bootstrap = new ServerBootstrap

    val serverQueue = async.boundedQueue[(InetSocketAddress, Process[Task, Exchange[ByteVector, ByteVector]])](config.limit)

    bootstrap.group(bossGroup, Netty.serverWorkerGroup)
      .channel(classOf[NioServerSocketChannel])
      .childOption[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, config.keepAlive)
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, config.tcpNoDelay)

    // these do not seem to work with childOption
    config.soSndBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_SNDBUF, _))
    config.soRcvBuf.foreach(bootstrap.option[java.lang.Integer](ChannelOption.SO_RCVBUF, _))

    bootstrap.childHandler(new ChannelInitializer[SocketChannel] {
        def initChannel(ch: SocketChannel): Unit = {
          if (config.codeFrames) {
            ch.pipeline
              .addLast("frame encoding", new LengthFieldPrepender(4))
              .addLast("frame decoding", new LengthFieldBasedFrameDecoder(Int.MaxValue, 0, 4, 0, 4))
          }

          ch.pipeline.addLast("incoming handler", new ServerHandler(ch, serverQueue, config.limit))
        }
      })

    val bindF = bootstrap.bind(bind)

    for {
      _ <- Netty toTask bindF
      server <- Task delay {
        new Server(bossGroup, bindF.channel(), serverQueue) // yeah!  I <3 Netty
      }
    } yield server
  } join
}

final case class ServerConfig(keepAlive: Boolean, numThreads: Int, limit: Int, codeFrames: Boolean, tcpNoDelay: Boolean, soSndBuf: Option[Int], soRcvBuf: Option[Int])

object ServerConfig {
  // 1000?  does that even make sense?
  val Default = ServerConfig(true, Runtime.getRuntime.availableProcessors, 1000, true, false, None, None)

}
