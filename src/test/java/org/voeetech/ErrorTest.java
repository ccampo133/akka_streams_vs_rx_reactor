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
import akka.stream.ActorAttributes;
import akka.stream.ActorMaterializer;
import akka.stream.Supervision;
import akka.stream.javadsl.AsPublisher;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.retry.Retry;
import scala.jdk.javaapi.FutureConverters;

import java.util.concurrent.CompletableFuture;

public class ErrorTest
{
  private static final Logger logger = LoggerFactory.getLogger(ErrorTest.class);

  @Test
  public void reactorBaseTestCase()
  {
    Flux.range(-10, 20)
        .map(i -> i * i / i)
        .subscribe(
          i -> logger.info("Element {}", i),
          e -> logger.error("Kaboom!", e)
        );
  }

  @Test
  public void reactorRetry()
  {
    Flux.range(-10, 20)
        .map(i -> i * i / i)
        .retry(3)
        .subscribe(
          i -> logger.info("Element {}", i),
          e -> logger.error("Kaboom!", e)
        );
  }

  @Test
  public void reactorResume()
  {
    Flux.range(-10, 20)
        .map(i -> i * i / i)
        .onErrorResume(t -> Flux.just(666))
        .subscribe(
          i -> logger.info("Element {}", i),
          e -> logger.error("Kaboom!", e)
        );
  }

  @Test
  public void reactorContinue()
  {
    Flux.range(-10, 20)
        .map(i -> i * i / i)
        .onErrorContinue(
          (t, v) -> logger.warn("Offending value {} caused exception {}", v,
                                t.getClass().getSimpleName()))
        .subscribe(
          i -> logger.info("Element {}", i),
          e -> logger.error("Kaboom!", e)
        );

  }

  @Test
  public void reactorContinueFailure()
  {
    Flux.range(-10, 20)
        .map(i -> i * i / i)
        .concatMap(
          i -> (i == -5 ?
                Flux.<Integer>error(new RuntimeException("Kaboom!")) :
                Flux.just(i))
                 .retryWhen(Retry.onlyIf(retryContext -> false))
                 .onErrorResume(t -> Flux.just(666))
        )
        .onErrorContinue(
          (t, v) -> logger.warn("Offending value {} caused exception {}", v,
                                t.getClass().getSimpleName()))
        .subscribe(
          i -> logger.info("Element {}", i),
          e -> logger.error("Kaboom!", e)
        );

  }

  @Test
  public void akkaContinue()
  {
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(-10, 10)
          .via(Flow.of(Integer.class)
                   .map(i -> i * i / i)
                   .withAttributes(ActorAttributes.withSupervisionStrategy(
                     Supervision.getResumingDecider()))
          )
          .runWith(Sink.foreach(e -> logger.info("Element {}", e)),
                   materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();

  }

  @Test
  public void hybridContinue()
  {
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Publisher<Integer> publisher =
      Source.range(-10, 10)
            .via(Flow.of(Integer.class)
                     .map(i -> i * i / i)
                     .withAttributes(ActorAttributes.withSupervisionStrategy(
                       Supervision.getResumingDecider()))
            )
            .runWith(Sink.asPublisher(AsPublisher.WITHOUT_FANOUT),
                     materializer);

    CompletableFuture<Done> done = new CompletableFuture<>();

    Flux.from(publisher)
        .doOnComplete(() -> done.complete(Done.getInstance()))
        .doOnError(e -> done.completeExceptionally(e))
        .subscribe(
          i -> logger.info("Element {}", i),
          e -> logger.error("Kaboom!", e)
        );

    done.thenCompose(
      __ -> FutureConverters.asJava(system.terminate())
    )
        .toCompletableFuture()
        .join();

  }

  @Test
  public void akkaError()
  {
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Source.range(-10, 20)
          .map(i -> 100 / i)
          .runWith(Sink.foreach(System.out::println), materializer)
          .thenCompose(
            __ -> FutureConverters.asJava(system.terminate())
          )
          .toCompletableFuture()
          .join();
    ;

  }
}
