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
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Attributes;
import akka.stream.FlowShape;
import akka.stream.Inlet;
import akka.stream.Outlet;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.AbstractInHandler;
import akka.stream.stage.AbstractOutHandler;
import akka.stream.stage.AsyncCallback;
import akka.stream.stage.GraphStageLogic;
import akka.stream.stage.GraphStageWithMaterializedValue;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.Tuple2;
import scala.jdk.javaapi.FutureConverters;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static akka.stream.DelayOverflowStrategy.backpressure;

public class CustomOperatorTest
{
  private static final Logger logger =
    LoggerFactory.getLogger(CustomOperatorTest.class);

  private interface PauseButton
  {
    void pause();

    void resume();
  }

  private static class PauseButtonStage<T>
    extends GraphStageWithMaterializedValue<FlowShape<T, T>,
                                             CompletionStage<PauseButton>>
  {
    final Outlet<T> out = Outlet.create("PauseButton.out");
    final Inlet<T> in = Inlet.create("PauseButton.in");

    private final FlowShape<T, T> shape = FlowShape.of(in, out);

    @Override
    public FlowShape<T, T> shape()
    {
      return shape;
    }

    @Override
    public Tuple2<GraphStageLogic, CompletionStage<PauseButton>> createLogicAndMaterializedValue(
      Attributes inheritedAttributes)
    {
      final CompletableFuture<PauseButton> pauseButton =
        new CompletableFuture<>();

      GraphStageLogic logic = new GraphStageLogic(shape())
      {
        {
          setHandler(
            in,
            new AbstractInHandler()
            {
              @Override
              public void onPush()
                throws Exception
              {
                if (isAvailable(out))
                  push(out, grab(in));
              }
            });

          setResumedHandler();

        }

        private void setPausedHandler()
        {
          setHandler(
            out,
            new AbstractOutHandler()
            {
              @Override
              public void onPull()
                throws Exception
              {
              }
            });
        }

        private void setResumedHandler()
        {
          setHandler(
            out,
            new AbstractOutHandler()
            {
              @Override
              public void onPull()
                throws Exception
              {

                if (!hasBeenPulled(in))
                  pull(in);

              }
            });
        }

        @Override
        public void preStart()
        {
          AsyncCallback<Object> pauseCallback =
            createAsyncCallback(__ -> setPausedHandler());

          AsyncCallback<Object> resumeCallback =
            createAsyncCallback(__ -> {
              if (isAvailable(out) && !hasBeenPulled(in))
                pull(in);
              setResumedHandler();
            });

          pauseButton.complete(
            new PauseButton()
            {
              @Override
              public void pause()
              {
                pauseCallback.invoke(NotUsed.getInstance());
              }

              @Override
              public void resume()
              {
                resumeCallback.invoke(NotUsed.getInstance());
              }
            }
          );
        }
      };

      return new Tuple2<>(logic, pauseButton);
    }
  }

  @Test
  public void customOperator()
  {
    final ActorSystem system = ActorSystem.create("Test");
    final ActorMaterializer materializer = ActorMaterializer.create(system);

    Pair<CompletionStage<PauseButton>, CompletionStage<Done>> matPair =
      Source.range(0, 100)
            .throttle(1, Duration.ofMillis(350))
            .viaMat(Flow.fromGraph(new PauseButtonStage<>()), Keep.right())
            .toMat(Sink.foreach(System.out::println), Keep.both())
            .run(materializer);

    Source.repeat(matPair.first())
          .take(3)
          .flatMapConcat(Source::fromCompletionStage)
          .zipWith(Source.repeat(1), (pauseButton, __) -> pauseButton)
          .delay(Duration.ofSeconds(10), backpressure())
          .throttle(1, Duration.ofSeconds(10))
          .wireTap(pauseButton -> {
            logger.info("pause!");
            pauseButton.pause();
          })
          .flatMapConcat(
            pauseButton -> Source.single(NotUsed.getInstance())
                                 .delay(Duration.ofSeconds(5), backpressure())
                                 .wireTap(__ -> {
                                   logger.info("resume!");
                                   pauseButton.resume();
                                 })
          )
          .runWith(Sink.ignore(), materializer);

    matPair.second()
           .thenCompose(
             __ -> FutureConverters.asJava(system.terminate())
           )
           .toCompletableFuture()
           .join();
  }
}
