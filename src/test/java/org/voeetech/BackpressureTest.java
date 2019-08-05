/*
 * Copyright (c) 2019 Artur Jabłoński
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.voeetech;

import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.DelayOverflowStrategy;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import scala.jdk.javaapi.FutureConverters;

import java.time.Duration;

public class BackpressureTest
{
  @Test
  public void reactorOverflowExceptionInterval()
  {
    Flux.interval(Duration.ofMillis(1))
        .concatMap(i -> Flux.just(i).delaySubscription(Duration.ofSeconds(1)))
        .blockLast();
  }

  @Test
  public void reactorOverflowExceptionBuffer()
  {
    Flux.range(1, Integer.MAX_VALUE)
        .buffer(Duration.ofMillis(1))
        .concatMap(i -> Flux.just(i).delaySubscription(Duration.ofSeconds(1)))
        .blockLast();
  }

  @Test
  public void reactorOverflowExceptionWindow()
  {
    Flux.range(1, Integer.MAX_VALUE)
        .window(Duration.ofMillis(1))
        .concatMap(w -> w.delaySubscription(Duration.ofSeconds(1)))
        .blockLast();
  }

  @Test
  public void reactorOverflowExceptionOOME()
  {
    Flux.interval(Duration.ofMillis(1))
        .map(__ -> new byte[1024 * 1024])
        .onBackpressureBuffer()
        .concatMap(i -> Flux.just(i).delaySubscription(Duration.ofSeconds(1)))
        .blockLast();
  }

  @Test
  public void akkaStreamsBackpressureFirst()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.tick(Duration.ZERO, Duration.ofMillis(1), new Object())
          .flatMapConcat(
            l -> Source.single(l)
                       .delay(Duration.ofSeconds(1),
                              DelayOverflowStrategy.backpressure())
          )
          .take(100)
          .runWith(Sink.foreach(System.out::println), materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();
  }
}
