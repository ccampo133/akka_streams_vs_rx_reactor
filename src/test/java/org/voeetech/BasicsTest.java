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

import akka.Done;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import scala.jdk.javaapi.FutureConverters;

import java.util.concurrent.CompletableFuture;

public class BasicsTest
{
  @Test
  public void reactorBasic()
  {
    Flux.range(0, 10)
        .map(i -> i * 2)
        .filter(i -> i > 10)
        .take(2)
        .subscribe(System.out::println);
  }

  @Test
  public void reactorBasicCompose()
  {
    Flux.range(0, 10)
        .map(i -> i * 2)
        .compose(
          f -> f.filter(i -> i > 10)
                .take(2)
        )
        .subscribe(System.out::println);
  }

  @Test
  public void reactorBasicvSynchronizeWhenStreamTerminates()
  {
    CompletableFuture<Done> done = new CompletableFuture<>();

    Flux.range(0, 10)
        .subscribeOn(Schedulers.parallel())
        .flatMap(i -> Flux.just(i * 2))
        .filter(i -> i > 10)
        .take(2)
        .doOnComplete(() -> done.complete(Done.done()))
        .doOnError(error -> done.completeExceptionally(error))
        .subscribe(System.out::println);

    done.join();

  }

  @Test
  public void akkaStreamsBasic()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(0, 10)
          .map(i -> i * 2)
          .filter(i -> i > 10)
          .take(2)
          .toMat(Sink.foreach(System.out::println), Keep.right())
          .run(materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();

  }

  @Test
  public void akkaStreamsBasicWithFlow()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(0, 10)
          .via(Flow.of(Integer.class)
                   .map(i -> i * 2)
                   .filter(i -> i > 10)
                   .take(2)
          )
          .toMat(Sink.foreach(System.out::println), Keep.right())
          .run(materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();

  }

  @Test
  public void akkaStreamsBasicWithFlowVer2()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(0, 10)
          .map(i -> i * 2)
          .via(Flow.of(Integer.class)
                   .filter(i -> i > 10)
                   .take(2)
          )
          .toMat(Sink.foreach(System.out::println), Keep.right())
          .run(materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();

  }
}
