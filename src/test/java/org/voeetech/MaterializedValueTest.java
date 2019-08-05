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
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.actor.Terminated;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.KillSwitches;
import akka.stream.UniqueKillSwitch;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import scala.jdk.javaapi.FutureConverters;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class MaterializedValueTest
{
  @Test
  public void defaultMatValue()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    NotUsed notUsed =
      Source.range(0, 10)
            .via(Flow.of(Integer.class)
                     .map(i -> i * 2)
                     .filter(i -> i > 10)
                     .take(2)
            )
            .to(Sink.foreach(System.out::println))
            .run(materializer);
  }

  @Test
  public void toMatValue()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    CompletionStage<Done> done =
      Source.range(0, 10)
            .via(Flow.of(Integer.class)
                     .map(i -> i * 2)
                     .filter(i -> i > 10)
                     .take(2)
            )
            .toMat(Sink.foreach(System.out::println), Keep.right())
            .run(materializer);

  }

  @Test
  public void runWithValue()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    CompletionStage<Done> done =
      Source.range(0, 10)
            .via(Flow.of(Integer.class)
                     .map(i -> i * 2)
                     .filter(i -> i > 10)
                     .take(2)
            )
            .runWith(Sink.foreach(System.out::println), materializer);
  }

  @Test
  public void runForEachValue()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    CompletionStage<Done> done =
      Source.range(0, 10)
            .via(Flow.of(Integer.class)
                     .map(i -> i * 2)
                     .filter(i -> i > 10)
                     .take(2)
            )
            .runForeach(System.out::println, materializer);
  }

  @Test
  public void akkaKillswitch()
  {
    //we need the actor system
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Pair<UniqueKillSwitch, CompletionStage<Done>> pair =
      Source.fromCompletionStage(new CompletableFuture<>())
            .viaMat(KillSwitches.single(), Keep.right())
            .toMat(Sink.ignore(), Keep.both())
            .run(materializer);

    CompletionStage<Terminated> done =
      pair.second()
          .thenCompose(__ -> FutureConverters.asJava(system.terminate()));

    pair.first().shutdown();

    done.toCompletableFuture().join();

  }

  @Test
  public void reactorKillswitch()
  {
    CompletableFuture<Done> killSwitch = new CompletableFuture<>();

    Flux.never()
        .subscribeOn(Schedulers.single())
        .takeUntilOther(Mono.fromFuture(killSwitch))
        .subscribe();

    killSwitch.complete(Done.done());

  }
}
