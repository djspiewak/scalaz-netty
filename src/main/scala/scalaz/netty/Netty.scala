/*
 * Copyright 2016 RichRelevance
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

import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService

import _root_.io.netty.channel._
import scodec.bits.ByteVector

import scalaz.concurrent._
import scalaz.stream._

object Netty {

  def server(bind: InetSocketAddress, config: ServerConfig = ServerConfig.Default)(implicit pool: ExecutorService = Strategy.DefaultExecutorService, S: Strategy): Process[Task, Process[Task, Exchange[ByteVector, ByteVector]]] = {
    Process.bracket(Server(bind, config))(s => Process.eval(s.shutdown).drain) { server: Server =>
      server.listen
    }
  }

  def connect(to: InetSocketAddress, config: ClientConfig = ClientConfig.Default)(implicit pool: ExecutorService = Strategy.DefaultExecutorService, S: Strategy): Process[Task, Exchange[ByteVector, ByteVector]] = {
    Process.bracket(Client(to, config))(_.shutdown) { client: Client =>
      Process(Exchange(client.read, client.write))
    }
  }

  private[netty] def toTask(f: ChannelFuture)(implicit pool: ExecutorService): Task[Unit] = fork {
    Task async { (cb: (Throwable \/ Unit) => Unit) =>
      f.addListener(new ChannelFutureListener {
        def operationComplete(f: ChannelFuture): Unit = {
          if (f.isSuccess)
            cb(\/-(()))
          else
            cb(-\/(f.cause))
        }
      })
    }
  }

  private def fork[A](t: Task[A])(implicit pool: ExecutorService = Strategy.DefaultExecutorService): Task[A] = {
    Task async { cb =>
      t unsafePerformAsync { either =>
        pool.submit(new Runnable {
          def run(): Unit = cb(either)
        })

        ()
      }
    }
  }
}

