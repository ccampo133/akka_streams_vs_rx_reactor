<!---
Copyright (c) 2019 Artur Jabłoński

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
-->

# **_Akka Streams_ vs _Project Reactor_ (and RxJava2)**

In this write up I will explore some differences
between __2 API designs__ of __3 reactive streams specification implementations__
available for Java: _Akka Streams_, _RxJava2_ and _Project Reactor_.

Some familiarity with reactive streams is assumed. 
If you haven't heard about reactive streams, [this is a great article about them](https://gist.github.com/staltz/868e7e9bc2a7b8c1f754). 
You can also have a look
at _Java_ [reactive streams interfaces specification](https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.2/README.md)

I have more experience with _RxJava2_ and _Project Reactor_ than I have
with _Akka Streams_. In fact I've run away scared many times after
starting reading the [_Akka Streams_ documentation](https://doc.akka.io/docs/akka/current/stream/index.html).
Reactive streams
programming is already hard as it is and _Akka Streams_ learning curve
seemed a bit steeper than those other libs out there. However, 
after finding some extra time to play with _Akka Streams_... I still
think its learning curve is steeper, BUT I started
appreciating the reason why. I hope this write up will make understanding
_Akka Streams_ easier for those who know _Reactor_ (or RxJava2) and for those
who don't that it will serve as an introductory/intermediate material for both
libraries with some comparison between them.  

All the code samples used here are available in the repository as _JUnit_
tests. I used the latest release versions of the libs that were available
at the time of writing which is **3.2.10-RELEASE** for _Project Reactor_
and **2.5.23** for _Akka Streams_. 

## RxJava2 and Project Reactor

I put _RxJava2_ and _Project Reactor_ together, because they are very similar
in their APIs and if you know one it's very easy to switch to the other.
The main abstractions here are `Flowable<T>` for _RxJava2_ and `Flux<T>` for _Project Reactor_.
In this article I will be using _Project Reactor_ for examples, but it should be 
for most parts interchangeable with _RxJava2_.

`Flux<T>` represent a stream of type `T`. Once you have an instance of a stream you 
can apply one of many available operators that are defined on `Flux` to express the
transformations of the data.

For example:
```java
Flux.range(0,10)
    .map(i -> i * 2)
    .filter(i -> i > 10)
    .take(2);
```
Here we start with a range of 10 values starting from 0, we then use `map` to
double each one, then we use `filter` to pass through only those greater
than 10 and then we'll `take` the first two such numbers. Each operator
returns an instance of `Flux` that can be further transformed.

Once all the transformations are expressed via operation chaining what you get
is a blueprint of the stream, that is, there's no data actually flowing yet. 
In order to run such blueprint you need to _subscribe_ to it, it's only then
that the stream is run:

```java
Flux.range(0,10)
    .map(i -> i * 2)
    .filter(i -> i > 10)
    .take(2)
    .subscribe(System.out::println);
```

This will trigger the stream execution and return an instance of `Disposable`, 
which can be used to cancel the stream while it's executing.

This blueprint + subscription is a simple model that you can use to reason about
your stream execution. The subscription can be explicit like in the example above,
but it can also be implicit as part of some operator workings, for example:

```java
Flux.range(0,10)
    .flatMap(i -> Flux.just(i * 2))
    .filter(i -> i > 10)
    .take(2)
    .subscribe(System.out::println);
```

Here we just changed `map` to `flatMap`, the lambda passed as parameter to the
`flatMap` returns a `Flux`, but if `Flux` is a blueprint and we don't call 
`subscribe(...)` on it, then how is it executed? Well, it's the `flatMap` itself
that is subscribing to it and then emits out whatever the `Flux` emits. In this
trivial example, the `Flux.just(...)` returns a single value calculated inline, but 
it could very well execute an asynchronous call(s) to some external system(s). `flatMap`
is coordinating the subscriptions to the streams returned by the lambda. It can 
subscribe to multiple such streams at once and all this behaviour forms the semantics
of the operator. There are many other operators that use such nested/inner streams.
 
## Akka Streams

_Akka Streams_ main abstractions are `Source<Out, Mat>`, `Flow<In,Out,Mat>` 
and `Sink<In, Mat>`.
If you visualise stream processing as a directed graph where nodes are operations and edges
are inputs/outputs of the operations then `Source<Out,Mat>` is a node with just one output, 
`Flow<In,Out,Mat>` is a node with one input and one output and `Sink<In,Mat>`
is a node with just one input.
There are other abstractions with multiple inputs outputs, but we will skip them 
for now for simplicity.

First difference is that unlike in _RxJava2_/_Reactor_ neither of the 
abstractions in _Akka Streams_ implement the reactive streams specification interfaces directly.
The documentation explains that this is by design and that reactive streams specification
interfaces are treated like SPI and are not "leaked" to the users unless explicitly
asked for.

Second one is that all mentioned abstractions are parametrized not only with the data type
that is flowing in and/or out, but also with what is referred to as materialized value type, 
which defines a type of an auxiliary value that each `Source`/`Flow`/`Sink` can produce.
We will look at it later. 

Implementing the same processing stages as with the _Reactor_ example 
can be achieved like this:

```
Source.range(0, 10)
      .map(i -> i * 2)
      .filter(i -> i > 10)
      .take(2)
      .to(Sink.foreach(System.out::println))
```
      
or using a `Source` + `Flow` like this:
```
Source.range(0, 10)
      .map(i -> i * 2)
      .via(Flow.of(Integer.class)
               .filter(i -> i > 10)
               .take(2)
      )
      .to(Sink.foreach(System.out::println))        
```

Both definitions are equivalent. _Reactor_ also has its own way of expressing
a similar concept to the `Flow`, which can be used via the `compose` operator. We could
express the above _Akka Streams_ example in _Reactor_ like this:

```
Flux.range(0, 10)
    .map(i -> i * 2)
    .compose(
       f -> f.filter(i -> i > 10)
             .take(2)
    )

```
Here the lambda inside the `compose` plugs in to the `Flux` instance
that the `compose` was called on. That `Flux` is represented by te `f` lambda argument.
So that lambda takes a `Flux` of some type type `In`
and can transform it to `Flux` of some type `Out`, so it is "kind of" like the `Flow` abstraction.

Similarly to _Reactor_, all the transformations like the one above are just blueprints
for what will happen when the stream is run. To run a stream
all `Source(s)`, `Flow(s)` and `Sink(s)` need to be connected so that there
are no "loose" inputs/outputs left. When that's the case you then need a `Materializer` 
which takes a stream
blueprint and actually executes it. This means that it is possible
to have different implementations of `Materializer`, though it will
be no surprise that all of examples (that I've ever seen) use `ActorMaterializer` implementation
that executes streams using (surprise surprise) _Akka Actor System_. 

The `Materializer` abstraction doesn't exist in _Reactor_ where the execution logic
is "hardcoded" inside the library and triggered by subscription.

This is how the stream can be run in _Akka Streams_:

```java
 final ActorSystem system = ActorSystem.create("Test");
 final ActorMaterializer materializer = ActorMaterializer.create(system);

 Source.range(0, 10)
       .map(i -> i * 2)
       .filter(i -> i > 10)
       .take(2)
       .to(Sink.foreach(System.out::println))
       .run(materializer);
```

The call to `run` apart from executing the stream returns materialized
value of the stream, which we'll have a look at next.

### Materialized value

Materialized value of a stream is an auxiliary value that is obtained
when running stream with `Materializer` and can be used to interact
somehow with the running stream. This statement is pretty vague
because the materialized value can be anything and the said interaction
can be anything too.

Every `Source`, `Flow` and `Sink` can produce a materialized value. The type
of materialized value is captured by the last type parameter like
we have seen before so `Source<Out,Mat>` is a `Source` that produces values
of type `Out` and a materialized value of type `Mat`. If a particular `Source`
doesn't produce any materialized value that type still needs to be 
defined and in such case `NotUsed` type is used.

So if every `Source`, `Flow` and `Sink` can produce a materialized value
of some type and a stream can be composed of multiple of those, the
question is, which materialized value will you get when the stream
is materialized? If you visualize stream as a linear structure
where data flows from left to right, then if you use `run` method as in the previous examples,
then by default you will get the materialized value of the leftmost 
component in the stream. If on the other hand you use `runWith` 
you will get the materialized value
of the `Sink` passed as the parameter to it. If you want more control
over which materialized value you will get, you can use `*Mat` methods
like `toMat`, `viaMat` and others,
where you can specify that you are interested in left, right, or both
materialized values.
 
Let's say you are interested in the `Sink` materialized value of the example
used before, you can achieve it by any of those:

```java
final ActorSystem system = ActorSystem.create("Test");
final ActorMaterializer materializer = ActorMaterializer.create(system);

Source.range(0, 10)
      .map(i -> i * 2)
      .filter(i -> i > 10)
      .take(2)
      .toMat(Sink.foreach(System.out::println), Keep.right())
      .run(materializer);

Source.range(0, 10)
      .map(i -> i * 2)
      .filter(i -> i > 10)
      .take(2)
      .runWith(Sink.foreach(System.out::println));
```
_Akka Streams_ [reference documentation](https://doc.akka.io/docs/akka/current/stream/index.html) 
explains the mechanics of handling materialized values in depth.

_Reactor_ doesn't have the concept of materialized value, though if you bend
your mind enough you can think of `Disposable` instance that is returned after
subscribing to a stream as a one and only possible materialized value that can 
be returned when running a stream. `Disposable` can be used to cancel
a running stream.

The materialized value concept complicates the API as now every `Source`,
`Flow` and `Sink` need to be parameterized with two types and when you build
your stream you need to think not only about what flows through 
your stream and how it is transformed, but also how to get the 
materialized value you are interested in. On the other hand this 
concept opens a lot of possibilities. It is a trade off. 

So what are the materialized values actually used for? Here are some examples
from core _Akka Streams_ library.

#### `CompletionStage<Done>`
Many sinks return `CompletionStage<Done>` instance that completes when the stream
terminates. You can hook up some code to be executed when this happens.
In this example we will use it to terminate the _Actor System_.

```java
final ActorSystem system = ActorSystem.create("Test");
final ActorMaterializer materializer = ActorMaterializer.create(system);

Source.range(0, 10)
      .map(i -> i * 2)
      .filter(i -> i > 10)
      .take(2)
      .runWith(Sink.foreach(System.out::println), materializer)
      .thenCompose(__ -> FutureConverters.asJava(system.terminate()))
      .toCompletableFuture()
      .join();

```  
The `runWith` will return the materialized value of the `Sink.foreach` which is
`CompletionStage<Done>`. We then use the `CompletionStage` API to terminate
the _Actor System_ when the stream completes. Since `ActorSystem.terminate()`
returns _Scala_ `Future` we need to convert it to _Java_ `CompletionStage`, we then
convert it to `CompletableFuture` and use `join` to block the calling thread. 
This is important here as this code is executed in context of a _JUnit_ test. 
Remember that the stream is run using actors in the `ActorSystem` which is
using its own _dispacher_/thread pool to do that. If we didn't synchronize here
then it's possible that the _JUnit_ thread finishes before the stream finishes
execution (or before it even started!).

This is not necessary in the _Reactor_ example, let's invoke it here again:

```java
Flux.range(0,10)
    .flatMap(i -> Flux.just(i * 2))
    .filter(i -> i > 10)
    .take(2)
    .subscribe(System.out::println);
```

This is because by default _Reactor_ execution model is single threaded and
the thread that calls `subscribe` is the one that actually executes the whole
stream. So in this case it would be the _JUnit_ thread, so there's no need for
any synchronization. But let's imagine we changed that default behaviour by 
using the `subscribeOn` operator like this:

```java
Flux.range(0, 10)
    .subscribeOn(Schedulers.parallel())
    .flatMap(i -> Flux.just(i * 2))
    .filter(i -> i > 10)
    .take(2)
    .subscribe(System.out::println);
```

By doing so the stream will be executed by a thread from the _parallel_ threadpool
and so our _JUnit_ thread needs to somehow wait for the stream termination.
We can't get a `CompletionStage` that completes when the stream terminates directly
here, but we can use the `doOnComplete` and `doOnError` hooks to get something similar, 
even if it is arguably less elegant:

```java
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

```

#### `CompletionStage<IOResult>`

Similarily to `CompletionStage<Done>` which completes when stream terminates, 
some components return `CompletionStage<IOResult>` that complete with `IOResult`
instance that gives some more information about an IO (like the number of bytes
actually written). One such example is `Source` and `Sink` that can be obtained
from the `FileIO` class to stream either from or to a filesystem.

#### `KillSwitch`

`KillSwitch` can be used to gracefully
terminate a stream. This can be a good strategy if you want to externally 
(like say when SIGINT is sent to the application) stop
the stream, but give it some time to finish processing all the "in flight"
data. `KillSwitch` is a `Flow` that can be plugged
in anywhere in a stream, for example:

```java
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
``` 
Here our stream starts with a `CompletableFuture` that never completes, 
therefore it should never terminate. However, we plugged in a `KillSwitch` `Flow`
that has materialized value of type `KillSwitch`. We had to use `viaMat` + `Keep.right`
to select the materialized value of the `KillSwitch` rather than that of the `Source`.
We use the `Sink.ignore` `Sink` and we used `toMat` + `Keep.both`  to pick up both the `KillSwitch`
materialized value and the `CompletionStage<Done>` materialized value wrapped in a `Pair` instance.
After the call to `run` our stream is now running. We use the `shutdown` method of 
the `KillSwitch` to gracefully terminate the stream. 

Could we do something similar to this in _Reactor_? Kind of... we can use the
`takeUntilOther` operator like this:

```java
CompletableFuture<Done> killSwitch = new CompletableFuture<>();

Flux.never()
    .subscribeOn(Schedulers.single())
    .takeUntilOther(Mono.fromFuture(killSwitch))
    .subscribe();

killSwitch.complete(Done.done());
```

The `takeUntilOther` has very similar semantics to `KillSwitch`. It will cancel
the upstream and terminate the downstream. Here we use _Reactor_ `Mono.fromFuture`
to trigger the operator via a `CompletableFuture`. It's definitely more concise
without all the materialized values juggling. Of course here we don't have the 
`CompletableFuture` that completes when the stream terminates, which we could plug
in using the previous example, making the code somehow more convoluted. 

#### Queue
The last example of a materialized value is that of `Source.queue` that returns
an implementation of a queue when materialized that can be used to flow data
into the stream from "outside".
Similarily, there is a `Sink.queue` that materializes to a queue that can be used
to consume data from the stream from "outside".  

## Backpressure (and the lack of it)

Backpressure in reactive streams is a flow control mechanism that allows for
the stream processing speed to adjust to the speed of the slowest part of it.

The way it works is that the downstream is signalling to upstream a number of items
it can take. Only then is the upstream allowed to send up to that amount of data 
items. This amount of data items is called _demand_. You can imagine that in 
reactive streams there are demand signals going in direction from downstream
to upstream and then the data is flowing from upstream to downstream.

The backpressure mechanism is a part of the reactive streams specification
and is not (or should be not) an optional feature. 

The backpressure mechanism gives reactive streams the property of boundedness,
which is to say that a given stream will execute in bounded memory since
regardless of the response time fluctuations of systems the stream interacts 
with, there is no need to buffer any data as everything is driven by _demand_.


There are plenty of good write ups explaining backpressure, 
[_Akka Streams_ documentation contains one of them](https://doc.akka.io/docs/akka/current/stream/stream-flows-and-basics.html#back-pressure-explained)
 
In _Reactor_ backpressure works most of the time. There are some operators however,  
that don't honour backpressure. Those are: `interval`, time driven `buffer`
and time driven `window`. All 3 operators are driven by time and for some reason
the designers of the library decided that the time dimension is more
important than the backpressure and if your downstream stops signaling
demand your stream will fail. 
This is illustrated by the following examples where I simulated slower consumers:

```java
Flux.interval(Duration.ofMillis(1))
    .concatMap(i -> Flux.just(i).delaySubscription(Duration.ofSeconds(1)))
    .blockLast();
    
```

```java
Flux.range(1, Integer.MAX_VALUE)
    .buffer(Duration.ofMillis(1))
    .concatMap(i -> Flux.just(i).delaySubscription(Duration.ofSeconds(1)))
    .blockLast();
```
    
```java
Flux.range(1, Integer.MAX_VALUE)
    .window(Duration.ofMillis(1))
    .concatMap(w -> w.delaySubscription(Duration.ofSeconds(1)))
    .blockLast();
```

All of the above will fail. You can try to improve the situation by 
using one of the `onBackpressureXXX` operators, for example:

```java
 Flux.interval(Duration.ofMillis(1))
     .map(__ -> new byte[1024 * 1024])
     .onBackpressureBuffer()
     .concatMap(i -> Flux.just(i).delaySubscription(Duration.ofSeconds(1)))
     .blockLast();
```
In the example above we used `onBackpressureBuffer`, the consequence of which 
is that the stream looses its boundedness property. Here I deliberately mapped
every `Integer` coming out of the `interval` operator to a 1MB byte array.
Because the downstream here is slow the byte arrays will be buffered and pretty
soon you will see `OutOfMemoryException` thrown.

In _Akka Streams_ the backpressure is respected always without exceptions (at
least that's what the documentation states). 
Here an equivalent of the Reactor `interval` example:

```java
final ActorSystem system = ActorSystem.create("Test");
final ActorMaterializer materializer = ActorMaterializer.create(system);

Source.tick(Duration.ZERO, Duration.ofMillis(1), new Object())
      .flatMapConcat(
        l -> Source.single(l)
                   .delay(Duration.ofSeconds(1), DelayOverflowStrategy.backpressure())
      )
      .take(100)
      .runWith(Sink.foreach(System.out::println), materializer)
      .thenCompose(
        __ -> FutureConverters.asJava(system.terminate())
      )
      .toCompletableFuture()
      .join();
```
Here the stream would go forever, so that's why `take` operator is there to terminate
it after 100 elements. The `flatMapConcat` which is way slower here than the upstream
will stop sending demand signals to the `tick` operator. It doesn't matter that
the `tick` is configured to give a "tick" every millisecond, it will respect
the lack of _demand_ without throwing any exception.

## Concurrency

As mentioned before both _Reactor_ and _Akka Streams_ are by default executed
sequentially. If you want to run your streams concurrently you need to explicitly
declare it.

So imagine you want to use all the available cores to parallelize some part 
of the stream. In _Reactor_ the API gives you two ways of doing this:

```java
Flux.range(1, 100)
    .flatMap(
      i -> Flux.just(i)
               .subscribeOn(Schedulers.parallel()),
      Runtime.getRuntime().availableProcessors()
    )
    .doOnNext(i -> logger.info("Element {}", i))
    .blockLast();
```
Here the `flatMap` operator is used. The first argument to `flatMap` is a
lambda that needs to return a `Flux`, there we used `subscribeOn` to explicitly
declare that we want that internal stream to be operating on one of the threads
available in the `Schedulers.parallel()` threadpool. `flatMap` will simultaneously
subscribe to some default number of streams. That default can be overridden by passing
a second argument to it. Here, since we're performing computation, there's no point
of subscribing to more streams than available processors, so we use 
`Runtime.getRuntime().availableProcessors()` to get that value.  Any computation
happening inside the `Flux` returned from the lambda will effectively run in 
parallel. 

There's another way of doing the same using the `parallel` operator:

```java
 Flux.range(1, 100)
     .parallel()
     .runOn(Schedulers.parallel())
     .doOnNext(i -> logger.info("Element {}", i))
     .sequential()
     .blockLast();
    }
```
`parallel` will by default use number of available cores. Everything between
`runOn` and `sequential` is effectively running in parallel. The `sequential`
here is used only for the JUnit thread synchronization so we can have the 
`blockLast` call available, which is not present in the `ParallelFlux` type.

The same approach can be used if we want to call some blocking or non blocking
API that calls another service with a given concurrency level

```java
final Integer concurrency = 64;
Flux.range(1, concurrency)
    .flatMap(
      __ -> Mono.fromCallable(this::blockingApiCall)
                .doOnNext(o -> logger.info("Element {}", o))
                .subscribeOn(Schedulers.elastic()),
      concurrency
    )
    .blockLast();
```

Here we will be calling the `blockingApiCall` with max concurrency of 64. First
we will send 64 "dummy" elements down to `flatMap` which will subscribe to up 
to 64 streams that are all instructed to operate on a thread from the `elastic`
thread pool. This threadpool will create a new thread anytime one is requested
and all threads already created are busy. This means that this thread pool
is unbounded, but what puts an upper bound here is the `flatMap` 
itself that will only 
request up to `concurrency` threads from it at any given time.

So what if your call is non-blocking call? Like some _Netty_ based client? The only
difference is that you don't need to allocate a thread per call, since it will
not block any thread. The rest stays pretty much the same.
Assuming you get a `CompletableFuture` from your API, this is how the stream can look:

```java
final Integer concurrency = 64;
Flux.range(1, concurrency)
    .flatMap(
      __ -> Mono.fromFuture(this::nonBlockingApiCall)
                .doOnNext(o -> logger.info("Element {}", o)),
      concurrency
    )
    .blockLast();
```

_Akka Streams_ concurrency is bound to the underlying implementation
concurrency model. Since _Akka Streams_ are 
run on _Actor System_, you don't deal with threads/thread pools directly like 
you do with _Project Reactor_. Rather, your stream
is converted to _Actor(s)_ which are then executed by the dispatcher of the _Actor
System_. As mentioned before, by default an _Akka Stream_ stream executes sequentially. 
You can imagine that all your operators of such stream are fused into one actor instance. So 
what if you wanted, like in the _Reactor_ example to take advantage of multiple
cores to perform computations or if you wanted to do (non)blocking IO? 

There are two excelent posts on the subject, which I recommend to go through:
+ https://blog.colinbreck.com/maximizing-throughput-for-akka-streams/
+ https://blog.colinbreck.com/partitioning-akka-streams-to-maximize-throughput/

Here I will go through some of the approaches from the articles and render them
in _Java_. 

The first trick is to use the `mapAsync` operator, that can manage values
signaled via `CompletableFuture`. You can then isolate the computation part that
you want to run in parallel on a separate threadpool:

```java
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
```

The same approach can be used for (non)blocking IO calls. With blocking you'd need to adjust
your threadpool and concurrency level of `mapAsync` and with nonBlocking just
the latter (since there's no threadpool involved).

The important part of this approach is that your stream continues to be contained
entirely in one actor instance and is sequential, it's just you now offloaded the
asynchronous part to a threadpool and you let `mapAsync` manage results of asynchronous 
computations run by it.

Another approach would be to use `groupBy` to create a number of `SubFlow` equal 
to number of available cores:

```java
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
```

The trick here is first to partition the stream and then use `async` to demarcate
the asynchronous boundaries that will effectively get translated to separate
actor instances running each `SubFlow`. If you look at the output you will see
that the code is executed by different threads of _Akka's_ dispatcher. The same 
approach with `groupBy` can be used in _Project Reactor_.

There is another way of partitioning the stream using different API available 
in _Akka Streams_. This is explained in the next section.

## Create non-linear streams.

So far when looking at _Akka Streams_ we looked at the "linear stream" API. This API
covers the most common case of the data flowing from "upstream" to "downstream"
passing more less linearly through the stream. I used the not so precise
"more less" phrase as we've seen an exception to this
with `groupBy` operator that creates `SubFlows` and partitions the main stream into
multiple substreams. 

_Akka Streams_ provides a different API to express more complex
stream topologies like broadcasting, balancing, merging or even feedback loops
(that is stream output feeding back to its input).
 
Here we'll implement a partitioning of a stream similar to that of `groupBy`+ 
`mergeSubstreams` above. The idea is that we'll implement a `Flow` using the _GraphDSL_
API that takes concurrency level and another flow that captures the desired computation
and then plug it in to the linear API via `via` operator. Here's a relatively simple
example:

```java
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
```

First we have the `parallelizeFlow` that takes concurrency and a `Flow` that
we would like to parallelize. Then inside we use the _GraphDSL_ to create appropriate
stream pieces like `Balance`, `Merge`. We then used the `GraphDSL.Builder` that
is passed to our lambda to wire up all elements and return our `Flow`. Then 
we can use that `Flow` as any other by plugging it in to a linear stream topology.

_Project Reactor_ doesn't provide a similar API. All non-linear topologies that
can be expressed use the same API using specialized operators like `groupBy` (partitioning)
or `publish` (broadcasting).

## Creating your own operator

Even though both _Project Reactor_ and _Akka Streams_ provide a wide range of
stream operators, sooner or later you will find a usecase that is hard or impossible
to express using those built in operators and you will face a possibility of having
to implement your own operator. 

In _Project Reactor_ implementing your operator is discouraged, because it's considered
hard and easy to do wrong. A part of the complexity seems to arise form the fact
that _Project Reactor_ implements the reactive streams interfaces directly
and that's what you have to do when implementing your own operator.
If you look back at the _Java_ [specification of the reactive interfaces](https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.2/README.md)
, you will find them
pretty simple, however if you read the list of rules that govern their interactions,
you will realize the complexity involved.
To help testing implementations there's _Technology Compatibility Kit_ available
that can run custom implementations against a test suite that checks the constraints
coming from the spec. You can find it [here](https://github.com/reactive-streams/reactive-streams-jvm/blob/v1.0.2/tck/README.md).
However you still need to implement it in the first place. There are some utility/not documented/subject to change
routines that help you manage demand count or implement correct signaling of elements
by using a queue draining loop, which is an implementation detail of the built in 
operators, but you'd need to study the _Reactor_ source code to figure out how to use them. 
There's [this wiki page on RxJava2 project](https://github.com/ReactiveX/RxJava/wiki/Writing-operators-for-2.0) 
that goes deeper in the complexities involved. 

_Akka Streams_ on the other hand shields you from the complexity of the underlying
reactive streams interfaces implementation, managing demand count, thread safety, etc.,
giving you an API that you can use to plugin the logic of your operator in reactive streams
context.

Remember the `KillSwitch`? Let's try to implement a similar operator with the 
exception that instead of terminating the stream we want to pause it and then
resume it when we please. There isn't such operator provided in the core library,
so let's get our hands dirty and implement one.

If you feel lost reading the code snippets, please go to the [Akka Streams documentation](https://doc.akka.io/docs/akka/current/stream/index.html)
that explains in details all the concepts used.
There's no point repeating it all here, the point is to go through
some working example.

So lets first start with our self explanatory `PauseButton` abstraction:

```java
interface PauseButton
{
 void pause();
 void resume();
}
```

Then we need our custom stage based on `FlowShape` and we'll also need
ability to return a materialized value. Since we want to plug
this into a stream returning any datatype we parametrize over it
with `T`:

```java
private static class PauseButtonStage<T> extends GraphStageWithMaterializedValue<FlowShape<T, T>, PauseButton>
```

Then we need to create our input and output and appropriate `Shape`:

```java
final Outlet<T> out = Outlet.create("PauseButton.out");
final Inlet<T> in = Inlet.create("PauseButton.in");

private final FlowShape<T, T> shape = FlowShape.of(in, out);

public FlowShape<T, T> shape()
{
  return shape;
}

```
Then we can define method that returns the logic and materialized value
wrapped in a `Tuple2`.
```java
public Tuple2<GraphStageLogic, CompletionStage<PauseButton>> createLogicAndMaterializedValue(Attributes inheritedAttributes)
```        

First we'll create an instance of a `CompletableFuture` of our 
materialized value which is `PauseButton`, I will explain later
why we can't return the value directly.
        
```java
final CompletableFuture<PauseButton> pauseButton = new CompletableFuture<>();
```

Now we can create the actual logic based on our `Shape`:

```java
GraphStageLogic logic = new GraphStageLogic(shape())
```    
    
In the initialize section we will setup the initial behaviour which
will be 'unpaused' so as if our custom operator was not even
there:

```java
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
    }
  );

  setResumedHandler();

}
```    

and then the supporting routines for setting handlers in resumed
and paused state:

```java
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
```

Now in the `preStart` method we will actually create the
`PauseButton` instance and complete the `CompletableFuture` with it.
The reason why we have to go about this like that, is the use of
`createAsyncCallback` method that is not safe to call in the
constructor (as the documentation states). The reason why we want
to use that method in the first place is that our `PauseButton` will
effectively be affecting the way the events dispatched from the
in and out ports are handled. These changes in the behaviour will
be triggered externally and independently of the stream itself.
This means that if we were to invoke the handling changing code
directly we could run into race conditions because we couldn't
guarantee that our code is not running concurrently with a
currently installed handlers invoked by some event happening on
one of the ports. Therefore `createAsyncCallback` is there to
guarantee that this will not happen, which simplifies the implementation
greatly. Basically it wraps your callback into another "safe" object
that you should call externally.

```java
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
```

Finally we can return the logic and the pause button:

```java
return new Tuple2<>(logic, pauseButton);
```
    
Alright! So let's put this to test. We'll create a stream
that will emit numbers from 0 to 100 throttled to 1 per 350ms.
We will then plugin our custom operator using `viaMat` to fish
out the materialized value we were preparing. Then we will
run it with a `foreach` `Sink` that will print out every element.

```java
Pair<CompletionStage<PauseButton>, CompletionStage<Done>> matPair =
      Source.range(0, 100)
            .throttle(1, Duration.ofMillis(350))
            .viaMat(Flow.fromGraph(new PauseButtonStage<>()), Keep.right())
            .toMat(Sink.foreach(System.out::println), Keep.both())
            .run(materializer);
``` 

So now we want to start using the `PauseButton` (as soon as it is available on 
the `CompletableFuture`), we'll use another stream for this. Basically
we will repeat 3 times a cycle of pausing and then resuming after 5 seconds:

```java
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

```

and now, since we are running this from _JUnit_, we need to wait
for the main stream to complete:

```java
matPair.second()
       .thenCompose(
           __ -> FutureConverters.asJava(system.terminate())
       )
       .toCompletableFuture()
       .join();
```

If you run this you should see the stream pausing and resuming each
time advertised by some log statement on the console.

All these facilities will probably be not the first ones you will 
go to, but it's good to know they are there and that there are so
thought through covering some pretty complex use cases. 

## Error/Failure handling

A reactive stream can emit one of two terminal events. First is
completion and another is error. After the terminal event, no other events
are allowed to happen. There are bunch of operators that the libraries provide
that can be used to handle the error signals and handle them in some way.

Let's start with some nice blowing up stream:

```java
Flux.range(-10, 20)
    .map(i -> i * i / i)
    .subscribe(
        i -> logger.info("Element {}", i),
        e -> logger.error("Kaboom!", e)
    );
```

Here we are creating a range from -10 to 10 and then multiple each item by itself
and then dividing the result by the element itself again thus yielding the
same number that we started with, with the exception of 0 that will blow up
with `div by 0` exception, which is the whole point. Great!
In our `subscribe` we now have two lambdas, one that handles data
emissions and simply prints them out and the other handles the error signal
(remember there can only be one since it's terminal) if it happens. 

Let's start with _Reactor_

One operator that can be used to handle error singal is `retry`. It will upon
receiving the error signal, resubscribe to the stream basically starting to run
it from the very beginning:

```java
Flux.range(-10, 20)
    .map(i -> i * i / i)
    .retry(3)
    .subscribe(
        i -> logger.info("Element {}", i),
        e -> logger.error("Kaboom!", e)
    );
```

Here we instructed `retry` to capture any error coming from its upstream and 
then resubscribe up to 3 times to the upstream. This operator is useful when 
an error is intermittent in nature like say network related exception, when this sort of resubscription
may have a chance of success. Here we will simply trigger the error four times.
That last fourth exception will be propagated downstream so that it will only 
be logged once. So from the subscriber's perspective the error only happens once,
the `retry` operator basically is not letting through the error signal during the
3 attempts and reestablishes the whole upstream. This is invisible to the subscriber.
There are also `retryWhen` and `retryBackoff` operators that provide similar
resubscription error handling but with some back off time applied between retries.

Since here the resubscription strategy is not very useful, let's check `onErrorResume`,
which upon receiving error signal from upstream will call the lambda that's passed
to it, subscribe to returned `Flux` and pass the emissions downstream. This effectively
switches to a "secondary" stream when the "primary" terminates with error.
There is also `onErrorResume` version that lets you target specific exception
and then there is `onErrorReturn` which differs in that instead of returning
emissions from secondary stream it just returns a fixed single value instead.

```java
Flux.range(-10, 20)
    .map(i -> i * i / i)
    .onErrorResume(t -> Flux.just(666))
    .subscribe(
        i -> logger.info("Element {}", i),
        e -> logger.error("Kaboom!", e)
    );
```

So that's all great, but can we do better? I mean from all the original stream
values it's only the element 0 that will trigger the exception, can't we somehow just
skip it? Well, yes we can, let's check `onErrorContinue` operator

```java
Flux.range(-10, 20)
    .map(i -> i * i / i)
    .onErrorContinue(
        (t, v) -> logger.warn("Offending value {} caused exception {}", v, t.getClass().getSimpleName()))
    .subscribe(
        i -> logger.info("Element {}", i),
        e -> logger.error("Kaboom!", e)
    );
```

Nice! It works here just as we wanted it, but there's a dark side to the
`onErrorContinue` operator. This operator relies on the operators upstream to 
be aware of how it works and to check some flags, so it won't work with all the
operators (you need to check Javadoc for operators to figure out which ones support
it). Also there's some unexpected behaviour when nested streams are involved.
The example below is adopted from [this github issue.](https://github.com/reactor/reactor-addons/issues/210
)

```java
Flux.range(-10, 20)
    .map(i -> i * i / i)
    .concatMap(
        i -> (i == -5 ? Flux.<Integer>error(new RuntimeException("Kaboom!")) : Flux.just(i))
            .retryWhen(Retry.onlyIf(retryContext -> false))
            .onErrorResume(t -> Flux.just(666))
    )
    .onErrorContinue(
        (t, v) -> logger.warn("Offending value {} caused exception {}", v, t.getClass().getSimpleName()))
    .subscribe(
        i -> logger.info("Element {}", i),
        e -> logger.error("Kaboom!", e)
    );
```
Here I just added the `concatMap` fragment, where basically when we encounter
-5, we will start with failed stream, which we will then handle with `retryWhen`
and `onErrorResume` defaulting it to 666. All values other than -5 will be passed
through. You would expect that this would have caused the -5 be turned to 666
and the 0 swallowed by the `onErrorContinue`, but this is not the case. The 
last emited value is -6. -5 causes the stream to terminate. This is supposedly
due to the fact that the `onErrorContinue` applied on the outer stream somehow messes
with the inner stream of the `concatMap` which is something quite unexpected. 
`onErrorContinue` feels really hacky.  

So let's turn to _Akka Streams_ to see how it tackles the dropping of an offending
element problem:

```java
Source.range(-10, 10)
      .via(Flow.of(Integer.class)
               .map(i -> i * i / i)
               .withAttributes(ActorAttributes.withSupervisionStrategy(Supervision.getResumingDecider()))
      )
      .runWith(Sink.foreach(e -> logger.info("Element {}", e)), materializer)
      .thenCompose(
          __ -> FutureConverters.asJava(system.terminate())
      )
      .toCompletableFuture()
      .join();
```
Here I isolated the `map` operator in a `Flow` and attached an attribute that
will apply a resume strategy in case any error happens. There are other more
fine grained ways of applying this and other available error handling strategies, 
which are detailed in the documentation. So how is this possible if an error
was supposed to be a terminal event of a stream? Well I guess you can imagine
that the error handling strategy kicks in before an error is emitted to the stream.
So from the reactive streams perspective this error has never happened anywhere...
magic. To prove this point let's try something funky and let's try to take that 
_Akka Streams_ definition up to the `map` and plug it in as a `Publisher` to 
_Reactor_ subscriber and see where it gets us, which will also serve as an example
of how to do such thing in the first place.

```java
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
```
If you run this you will see the subscriber has never observed the error.

_Akka Streams_ has other error handling operators similar in workings to those
of _Project Reactor_. They are all well documented, so have a look.

## Testing

In terms of testing both libraries provide similar facilities where you 
can plugin a special component (`StepVerifier` and `TestPublisher` for _Project Reactor_ 
and `TestSource` and `TestSink`
for Akka Streams) to then assert on emissions, errors, completions, etc. 

One cool feature that _Project Reactor_ has is the concept of virtual time,
where testing of streams that involve things like delays or retries with backoff
super easy and fast as you can switch "wall time" based schedulers to "virtual time"
schedulers in a breeze. I don't think any such facility exists for _Akka Streams_.

## Distributing stream

So far all the streams we've looked at are running on a single machine. 
Distributing a stream means that there are multiple machines communicating
over network involved in running one stream. For that to work the protocol 
used for the machines to communicate would need to support not only passing 
the stream of data, but also passing the demand signals as per backpressure protocol.

This setup is not possible with _Project Reactor_, while _Akka Streams_ provides
it via `SourceRef` and `SinkRef` instances. The process of such  distributing is 
surprisingly simple and is well documented in the reference documentation.

What is not supported is an automated distribution say in a way _Persistent Actors_
can be distributed accross cluster using _Cluster Sharding_. It's a manual process. 
Though seeing that _Apache Flink_ for example has a binary dependency on _Akka Streams_ I wonder
if they don't use this mechanism under the hood to implement the distribution 
of stream processing. I couldn't google up anything that would confirm or deny it.
  
## Summary:
In this write up I went through various features of 2 API designs of 3 reactive
streams specification implementations available for _Java_.

_Project Reactor_ is easier to start with. The documentation, community support, 
and a lot of examples everywhere make learning a good experience. Also since
the project is powering reactive web stack of _Spring Framework_ (aka _WebFlux_) it is here
to stay. On the downside you may need to look for workarounds if you hit one of 
the design issues that seem to go against the reactive streams specification like the lack
of backpressure support for some operators or the weird `onErrorContinue` operator
behaviour. If your stream topology goes wildly beyond linear with feedback loops
or bidirectional flows, then the API will fall short. 

_Akka Streams_ has a really thought out API(s) that allow you to build very complex
stream topologies. This flexibility comes at a cost of API complexity, some of which
is addressed by splitting API into most common and simpler "linear streams" API and
more complex _GraphDSL_ API. _Akka Streams_ is definitely harder to pick up, especially 
if you're not that familiar with _Scala_ as it is the language most examples out there
are using. Also, if you're not familiar with _Akka Actors_ that _Akka Streams_ build
on, you probably want to at least understand the basic ideas behind them, which is extra
effort. _Akka Streams_ offer powerful features like stream distribution, per operator
error handing strategies, no-exception backpressure support, custom operator implementation
made (relatively) easy. [_Akka Http_](https://doc.akka.io/docs/akka-http/current/index.html) 
project is a fully blown HTTP server and client
library built entirely on _Akka Streams_ and projects like [_Play_](https://www.playframework.com/documentation/2.7.x/Home)
or [_Lagom_](https://www.lagomframework.com/documentation/) build on top of _Akka Http_.    