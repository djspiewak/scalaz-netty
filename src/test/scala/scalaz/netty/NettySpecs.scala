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

import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}

import concurrent._
import stream._
import syntax.monad._

import scodec.bits._

import scala.concurrent.duration._

import org.specs2.mutable._

import org.scalacheck._

import java.net.InetSocketAddress
import java.util.concurrent.{Executors, ThreadFactory}

object NettySpecs extends Specification {

  sequential

  val scheduler = {
    Executors.newScheduledThreadPool(4, new ThreadFactory {
      def newThread(r: Runnable) = {
        val t = Executors.defaultThreadFactory.newThread(r)
        t.setDaemon(true)
        t.setName("scheduled-task-thread")
        t
      }
    })
  }

  "netty" should {
    "round trip some simple data" in {
      val addr = new InetSocketAddress("localhost", 9090)

      val server = Netty server addr take 1 flatMap { incoming =>
        incoming flatMap { exchange =>
          exchange.read take 1 to exchange.write drain
        }
      }

      val client = Netty connect addr flatMap { exchange =>
        val data = ByteVector(12, 42, 1)

        val initiate = Process(data) to exchange.write

        val check = for {
          results <- exchange.read.runLog timed (5 seconds)

          _ <- Task delay {
            results must haveSize(1)
            results must contain(data)
          }
        } yield ()

        Process.eval(initiate.run >> check).drain
      }

      val delay = time.sleep(200 millis)(Strategy.DefaultStrategy, scheduler)

      val test = server.drain merge (delay ++ client)

      test.run timed (15 seconds) unsafePerformSync

      ok
    }

    "round trip some simple data to ten simultaneous clients" in {
      val addr = new InetSocketAddress("localhost", 9090)

      val server = merge.mergeN(Netty server addr map { incoming =>
        incoming flatMap { exchange =>
          exchange.read take 1 to exchange.write drain
        }
      })

      def client(n: Int) = Netty connect addr flatMap { exchange =>
        val data = ByteVector(n)

        for {
          _ <- Process(data) to exchange.write
          results <- Process eval (exchange.read.runLog timed (5 seconds))
          bv <- Process emitAll results
        } yield (n -> bv)
      }

      val delay = time.sleep(200 millis)(Strategy.DefaultStrategy, scheduler)

      val test = (server.drain wye merge.mergeN(Process.range(0, 10) map { n => delay ++ client(n) })) (wye.mergeHaltBoth)

      val results = test.runLog timed (15 seconds) unsafePerformSync

      results must haveSize(10)
      results must containAllOf(0 until 10 map { n => n -> ByteVector(n) })
    }

    "terminate a client process with an error if connection failed" in {
      val addr = new InetSocketAddress("localhost", 51235) // hopefully no one is using this port...

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.unsafePerformSync

      result must beLike {
        case -\/(_) => ok
      }
    }

    "terminate a client process if connection times out" in {
      val addr = new InetSocketAddress("100.64.0.1", 51234) // reserved IP, very weird port

      val client = Netty connect addr map { _ => () }

      val result = client.run.attempt.unsafePerformSync

      result must eventually(beLike[Throwable \/ Unit] {
        case -\/(_) => ok
      })
    }

    "not lose data on client in rapid-closure scenario" in {
      forall(0 until 10) { i =>
        val addr = new InetSocketAddress("localhost", 9090 + i)
        val data = ByteVector(1, 2, 3)

        val server = for {
          incoming <- Netty server addr take 1
          exch <- incoming
          _ <- exch.write take 1 evalMap {
            _ (data)
          }
        } yield () // close connection instantly

        val client = for {
          _ <- time.sleep(500 millis)(Strategy.DefaultStrategy, scheduler) ++ Process.emit(())
          exch <- Netty connect addr
          back <- exch.read take 1
        } yield back

        val driver: Process[Task, ByteVector] = server.drain merge client
        val task = (driver wye time.sleep(3 seconds)(Strategy.DefaultStrategy, scheduler)) (wye.mergeHaltBoth).runLast

        task.unsafePerformSync must beSome(data)
      }
    }

    "not lose data on server in rapid-closure scenario" in {
      forall(0 until 10) { i =>
        val addr = new InetSocketAddress("localhost", 9090 + i)
        val data = ByteVector(1, 2, 3)

        val server = for {
          incoming <- Netty server addr take 1
          exch <- incoming
          back <- exch.read take 1
        } yield back

        val client = for {
          _ <- time.sleep(500 millis)(Strategy.DefaultStrategy, scheduler) ++ Process.emit(())
          exch <- Netty connect addr
          _ <- exch.write take 1 evalMap {
            _ (data)
          }
        } yield () // close connection instantly

        val task = ((server merge client.drain) wye time.sleep(3 seconds)(Strategy.DefaultStrategy, scheduler)) (wye.mergeHaltBoth).runLast

        task.unsafePerformSync must beSome(data)
      }
    }

    def clock(ticksPerSec: Int) = time.awakeEvery((1000000 / ticksPerSec).microseconds)(Strategy.DefaultStrategy, Executors.newScheduledThreadPool(1))

    def throttle[T](p: Process[Task, T], ticksPerSec: Int): Process[Task, T] =
      p.zip(clock(ticksPerSec)).map {
        case (a, _) => a
      }

    def roundTripTest(port: Int,
                      noOfPackets: Int,
                      clientBpQueueLimit: Int,
                      serverBpQueueLimit: Int,
                      clientSendSpeed: Int, // messages per second
                      clientReceiveSpeed: Int, // messages per second
                      serverSendSpeed: Int, // messages per second
                      serverReceiveSpeed: Int, // messages per second
                      dataMultiplier: Int = 1 // sizing the packet
                     ) = {

      val deadBeef = ByteVector(0xDE, 0xAD, 0xBE, 0xEF) //4 bytes
      val address = new InetSocketAddress("localhost", port)
      val data = (1 to dataMultiplier).foldLeft(deadBeef)((a, _) => a ++ deadBeef)

      val counter = new AtomicInteger(0)

      val bossPool = EventLoopType.Select.bossGroup
      val serverWorkerPool = EventLoopType.Select.serverWorkerGroup(5)
      val clientWorkerPool = EventLoopType.Select.clientWorkerGroup(1)

      val server = (Netty.server(address,
        ServerConfig(
          keepAlive = true,
          numThreads = Runtime.getRuntime.availableProcessors,
          limit = serverBpQueueLimit,
          codeFrames = true,
          tcpNoDelay = true,
          soSndBuf = None,
          soRcvBuf = None,
          eventLoopType = EventLoopType.Select,
          Some(bossPool),
          Some(serverWorkerPool)
        ))) take 1 flatMap { incoming =>
        incoming flatMap { exchange =>
          throttle(
            throttle(exchange.read, serverReceiveSpeed),
            serverSendSpeed
          ) to exchange.write
        }
      }

      val client = Netty.connect(address,
        ClientConfig(
          keepAlive = true,
          limit = clientBpQueueLimit,
          tcpNoDelay = true,
          soSndBuf = None,
          soRcvBuf = None,
          eventLoopType = EventLoopType.Select,
          Some(clientWorkerPool)
        )
      ).flatMap { exchange =>
        val sendPackets = throttle(Process(data).repeat.take(noOfPackets), clientSendSpeed) to exchange.write
        val readPackets = throttle(exchange.read.take(noOfPackets), clientReceiveSpeed).map(_ => counter.incrementAndGet())

        sendPackets ++ readPackets
      }

      val wait = time.sleep(500 millis)(Strategy.DefaultStrategy, scheduler)

      server.wye(wait ++ client)(wye.mergeHaltR).run.unsafePerformSync
      counter.get mustEqual noOfPackets
    }

    "round trip more data with slow client receive" in {
      roundTripTest(
        port = 51236,
        noOfPackets = 1000,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 1000,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 200,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 10000
      )
    }

    "round trip more data with slow server receive" in {
      roundTripTest(
        port = 51237,
        noOfPackets = 1000,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 1000,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 10000,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 200
      )
    }

    "round trip lots of data fast with small buffers" in {
      roundTripTest(
        port = 51238,
        noOfPackets = 10000,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 10,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 10000,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 10000
      )
    }

    "round trip some huge packets" in {
      roundTripTest(
        port = 51239,
        noOfPackets = 10,
        clientBpQueueLimit = 10,
        serverBpQueueLimit = 10,
        clientSendSpeed = 10000,
        clientReceiveSpeed = 10000,
        serverSendSpeed = 10000,
        serverReceiveSpeed = 10000,
        dataMultiplier = 256 * 256
      )
    }

  }
}
