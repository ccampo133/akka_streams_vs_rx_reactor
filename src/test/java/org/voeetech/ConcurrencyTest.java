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

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.ActorMaterializer;
import akka.stream.FlowShape;
import akka.stream.UniformFanInShape;
import akka.stream.UniformFanOutShape;
import akka.stream.javadsl.Balance;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.GraphDSL;
import akka.stream.javadsl.Merge;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import scala.jdk.javaapi.FutureConverters;

import java.util.concurrent.CompletableFuture;

public class ConcurrencyTest
{
  private static final Logger logger =
    LoggerFactory.getLogger(ConcurrencyTest.class);

  @Test
  public void reactorParallelComputationTheOldWay()
  {
    Flux.range(1, 100)
        .flatMap(
          i -> Flux.just(i)
                   .subscribeOn(Schedulers.parallel()),
          Runtime.getRuntime().availableProcessors()
        )
        .doOnNext(i -> logger.info("Element {}", i))
        .blockLast();
  }

  @Test
  public void reactorParallelComputationTheNewWay()
  {
    Flux.range(1, 100)
        .parallel()
        .runOn(Schedulers.parallel())
        .doOnNext(i -> logger.info("Element {}", i))
        .sequential()
        .blockLast();
  }

  //simulates a blocking API call
  private Object blockingApiCall()
  {
    try {
      Thread.sleep(100);
    } catch (Exception e) {
    }
    return new Object();
  }

  //simulates a blocking API call
  private CompletableFuture<Object> nonBlockingApiCall()
  {
    return CompletableFuture.supplyAsync(this::blockingApiCall);
  }

  @Test
  public void reactorBlockingIO()
  {
    final Integer concurrency = 64;
    Flux.range(1, concurrency)
        .flatMap(
          __ -> Mono.fromCallable(this::blockingApiCall)
                    .doOnNext(o -> logger.info("Element {}", o))
                    .subscribeOn(Schedulers.elastic()),
          concurrency
        )
        .blockLast();
  }

  @Test
  public void reactorNonBlockingIO()
  {
    final Integer concurrency = 64;
    Flux.range(1, concurrency)
        .flatMap(
          __ -> Mono.fromFuture(this::nonBlockingApiCall)
                    .doOnNext(o -> logger.info("Element {}", o)),
          concurrency
        )
        .blockLast();
  }

  @Test
  public void akkaParallelComputationMapAsync()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(1, 100)
          .mapAsync(Runtime.getRuntime().availableProcessors(),
                    i ->
                      CompletableFuture.supplyAsync(
                        () -> {
                          logger.info("Element {}", i);
                          return i;
                        }
                      )
          )
          .runWith(Sink.foreach(System.out::println), materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();
  }

  @Test
  public void akkaParallelComputationGroupBy()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(1, 100)
          .groupBy(Runtime.getRuntime().availableProcessors(),
                   i -> i % Runtime.getRuntime().availableProcessors())
          .map(i -> i)
          .log("subFlow")
          .async()
          .mergeSubstreams()
          .runWith(Sink.foreach(System.out::println), materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();
  }

  @Test
  public void akkasubstream()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(1, 50)
          .log("main")
          .flatMapMerge(10, i -> Source.single(i).async().log("inner"))
          .runWith(Sink.foreach(System.out::println), materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();

  }

  private <I, O, M> Flow<I, O, NotUsed> parallelizeFlow(int concurrency,
                                                        Flow<I, O, M> innerFlow)
  {
    return
      Flow.fromGraph(GraphDSL.create(
        b -> {
          UniformFanOutShape<I, I> balance = b.add(Balance.create(concurrency));
          UniformFanInShape<O, O> merge = b.add(Merge.create(concurrency));

          for (int i = 0; i < concurrency; i++) {
            b.from(balance).via(b.add(innerFlow.async())).viaFanIn(merge);
          }

          return FlowShape.of(balance.in(), merge.out());
        }
      ));
  }

  @Test
  public void akkaGraphDSL()
  {

    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(1, 100)
          .via(parallelizeFlow(
            Runtime.getRuntime().availableProcessors(),
            Flow.of(Integer.class).log("pFlow"))
          )
          .runWith(Sink.foreach(System.out::println), materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();

  }
}