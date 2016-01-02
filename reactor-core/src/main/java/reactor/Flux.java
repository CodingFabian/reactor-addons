/*
 * Copyright (c) 2011-2016 Pivotal Software, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package reactor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.processor.FluxProcessor;
import reactor.core.processor.ProcessorGroup;
import reactor.core.publisher.FluxAmb;
import reactor.core.publisher.FluxArray;
import reactor.core.publisher.FluxFactory;
import reactor.core.publisher.FluxFlatMap;
import reactor.core.publisher.FluxJust;
import reactor.core.publisher.FluxLift;
import reactor.core.publisher.FluxLog;
import reactor.core.publisher.FluxMap;
import reactor.core.publisher.FluxNever;
import reactor.core.publisher.FluxPeek;
import reactor.core.publisher.FluxResume;
import reactor.core.publisher.FluxSession;
import reactor.core.publisher.FluxZip;
import reactor.core.publisher.ForEachSequencer;
import reactor.core.publisher.MonoIgnoreElements;
import reactor.core.publisher.MonoSingle;
import reactor.core.publisher.convert.DependencyUtils;
import reactor.core.subscriber.SubscriberWithContext;
import reactor.core.subscription.ReactiveSession;
import reactor.core.support.ReactiveState;
import reactor.core.support.ReactiveStateUtils;
import reactor.fn.BiConsumer;
import reactor.fn.BiFunction;
import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.Supplier;
import reactor.fn.tuple.Tuple;
import reactor.fn.tuple.Tuple2;

/**
 * A Reactive Streams {@link Publisher} with basic rx operators that emits 0 to N elements, and then complete
 * (successfully or with an error).
 * <p>
 * <p>It is intended to be used in Reactive Spring projects implementation and return types. Input parameters should
 * keep using raw {@link Publisher} as much as possible.
 * <p>
 * <p>If it is known that the underlying {@link Publisher} will emit 0 or 1 element, {@link Mono} should be used
 * instead.
 * <p>
 *
 * @author Sebastien Deleuze
 * @author Stephane Maldini
 * @see Mono
 * @since 2.5
 */
public abstract class Flux<T> implements Publisher<T> {

	/**
	 * ==============================================================================================================
	 * ==============================================================================================================
	 * <p>
	 * Static Generators
	 * <p>
	 * ==============================================================================================================
	 * ==============================================================================================================
	 */

	private static final IdentityFunction IDENTITY_FUNCTION = new IdentityFunction();
	private static final Flux<?>          EMPTY             = Mono.empty()
	                                                              .flux();

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <I> Flux<I> amb(Publisher<? extends I> source1, Publisher<? extends I> source2) {
		return new FluxAmb<>(new Publisher[]{source1, source2});
	}

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	@SuppressWarnings("unchecked")
	public static <I> Flux<I> amb(Iterable<? extends Publisher<? extends I>> sources) {
		if (sources == null) {
			return empty();
		}

		Iterator<? extends Publisher<? extends I>> it = sources.iterator();
		if (!it.hasNext()) {
			return empty();
		}

		List<Publisher<? extends I>> list = null;
		Publisher<? extends I> p;
		do {
			p = it.next();
			if (list == null) {
				if (it.hasNext()) {
					list = new ArrayList<>();
				}
				else {
					return wrap((Publisher<I>) p);
				}
			}
			list.add(p);
		}
		while (it.hasNext());

		return new FluxAmb<>(list.toArray(new Publisher[list.size()]));
	}

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <I> Flux<I> concat(Publisher<? extends Publisher<? extends I>> source) {
		return new FluxFlatMap<>(source, 1, 32);
	}

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <I> Flux<I> concat(Iterable<? extends Publisher<? extends I>> source) {
		return concat(from(source));
	}

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	@SafeVarargs
	public static <I> Flux<I> concat(Publisher<? extends I>... sources) {
		return concat(from(sources));
	}

	/**
	 * @param source
	 * @param <IN>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <IN> Flux<IN> convert(Object source) {

		if (Publisher.class.isAssignableFrom(source.getClass())) {
			return wrap((Publisher<IN>) source);
		}
		else if (Iterable.class.isAssignableFrom(source.getClass())) {
			return from((Iterable<IN>) source);
		}
		else if (Iterator.class.isAssignableFrom(source.getClass())) {
			return from((Iterator<IN>) source);
		}
		else {
			return (Flux<IN>) DependencyUtils.convertToPublisher(source);
		}
	}

	/**
	 * Create a {@link Publisher} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 *
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param <T> The type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <T> Flux<T> create(Consumer<SubscriberWithContext<T, Void>> requestConsumer) {
		return create(requestConsumer, null, null);
	}

	/**
	 * Create a {@link Publisher} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls.
	 *
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param contextFactory A {@link Function} called for every new subscriber returning an immutable context (IO
	 * connection...)
	 * @param <T> The type of the data sequence
	 * @param <C> The type of contextual information to be read by the requestConsumer
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <T, C> Flux<T> create(Consumer<SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory) {
		return create(requestConsumer, contextFactory, null);
	}

	/**
	 * Create a {@link Publisher} reacting on each available {@link Subscriber} read derived with the passed {@link
	 * Consumer}. If a previous request is still running, avoid recursion and extend the previous request iterations.
	 * The argument {@code contextFactory} is executed once by new subscriber to generate a context shared by every
	 * request calls. The argument {@code shutdownConsumer} is executed once by subscriber termination event (cancel,
	 * onComplete, onError).
	 *
	 * @param requestConsumer A {@link Consumer} invoked when available read with the target subscriber
	 * @param contextFactory A {@link Function} called once for every new subscriber returning an immutable context (IO
	 * connection...)
	 * @param shutdownConsumer A {@link Consumer} called once everytime a subscriber terminates: cancel, onComplete(),
	 * onError()
	 * @param <T> The type of the data sequence
	 * @param <C> The type of contextual information to be read by the requestConsumer
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <T, C> Flux<T> create(final Consumer<SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory,
			Consumer<C> shutdownConsumer) {
		return FluxFactory.create(requestConsumer, contextFactory, shutdownConsumer);
	}

	/**
	 * Create a {@link Flux} that completes without emitting any item.
	 *
	 * @param <T>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T> Flux<T> empty() {
		return (Flux<T>) EMPTY;
	}

	/**
	 * Create a {@link Flux} that completes with the specified error.
	 *
	 * @param error
	 * @param <T>
	 *
	 * @return
	 */
	public static <T> Flux<T> error(Throwable error) {
		return Mono.<T>error(error).flux();
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Iterable}.
	 *
	 * @param it
	 * @param <T>
	 *
	 * @return
	 */
	public static <T> Flux<T> from(Iterable<? extends T> it) {
		ForEachSequencer.IterableSequencer<T> iterablePublisher = new ForEachSequencer.IterableSequencer<>(it);
		return create(iterablePublisher, iterablePublisher);
	}

	/**
	 * Create a {@link Flux} that emits the items contained in the provided {@link Iterable}.
	 *
	 * @param array
	 * @param <T>
	 *
	 * @return
	 */
	public static <T> Flux<T> from(T[] array) {
		return new FluxArray<>(array);
	}

	/**
	 * @param defaultValues
	 * @param <T>
	 *
	 * @return
	 */
	public static <T> Flux<T> from(final Iterator<? extends T> defaultValues) {
		if (defaultValues == null || !defaultValues.hasNext()) {
			return empty();
		}
		ForEachSequencer.IteratorSequencer<T> iteratorPublisher =
				new ForEachSequencer.IteratorSequencer<>(defaultValues);
		return create(iteratorPublisher, iteratorPublisher);
	}

	/**
	 * Create a {@link Publisher} reacting on requests with the passed {@link BiConsumer}. The argument {@code
	 * contextFactory} is executed once by new subscriber to generate a context shared by every request calls. The
	 * argument {@code shutdownConsumer} is executed once by subscriber termination event (cancel, onComplete,
	 * onError).
	 *
	 * @param requestConsumer A {@link BiConsumer} with left argument request and right argument target subscriber
	 * @param contextFactory A {@link Function} called once for every new subscriber returning an immutable context (IO
	 * connection...)
	 * @param shutdownConsumer A {@link Consumer} called once everytime a subscriber terminates: cancel, onComplete(),
	 * onError()
	 * @param <T> The type of the data sequence
	 * @param <C> The type of contextual information to be read by the requestConsumer
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <T, C> Flux<T> generate(BiConsumer<Long, SubscriberWithContext<T, C>> requestConsumer,
			Function<Subscriber<? super T>, C> contextFactory,
			Consumer<C> shutdownConsumer) {

		return FluxFactory.generate(requestConsumer, contextFactory, shutdownConsumer);
	}

	/**
	 * Create a new {@link Flux} that emits the specified item.
	 *
	 * @param data
	 * @param <T>
	 *
	 * @return
	 */
	@SafeVarargs
	public static <T> Flux<T> just(T... data) {
		return from(data);
	}

	/**
	 * @param data
	 * @param <T>
	 *
	 * @return
	 */
	public static <T> Flux<T> just(T data) {
		return new FluxJust<>(data);
	}

	/**
	 * @param source
	 * @param <T>
	 *
	 * @return
	 */
	public static <T> Flux<T> merge(Publisher<? extends Publisher<? extends T>> source) {
		return new FluxFlatMap<>(source, ReactiveState.SMALL_BUFFER_SIZE, 32);
	}

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <I> Flux<I> merge(Iterable<? extends Publisher<? extends I>> sources) {
		return merge(from(sources));
	}

	/**
	 * @param <I> The source type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	@SafeVarargs
	@SuppressWarnings("unchecked")
	public static <I> Flux<I> merge(Publisher<? extends I>... sources) {
		if (sources == null || sources.length == 0) {
			return empty();
		}
		if (sources.length == 1) {
			return (Flux<I>) wrap(sources[0]);
		}
		return merge(from(sources));
	}

	/**
	 * Create a {@link Flux} that never completes.
	 *
	 * @param <T>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T> Flux<T> never() {
		return FluxNever.instance();
	}

	/**
	 * Expose the specified {@link Publisher} with the {@link Flux} API.
	 *
	 * @param source
	 * @param <T>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T> Flux<T> wrap(Publisher<T> source) {
		if (Flux.class.isAssignableFrom(source.getClass())) {
			return (Flux<T>) source;
		}

		if (Supplier.class.isAssignableFrom(source.getClass())) {
			T t = ((Supplier<T>) source).get();
			if (t != null) {
				return just(t);
			}
		}
		return new FluxBarrier<>(source);
	}

	/**
	 * Create a {@link Flux} reacting on subscribe with the passed {@link Consumer}. The argument {@code
	 * sessionConsumer} is executed once by new subscriber to generate a {@link ReactiveSession} context ready to accept
	 * signals.
	 *
	 * @param sessionConsumer A {@link Consumer} called once everytime a subscriber subscribes
	 * @param <T> The type of the data sequence
	 *
	 * @return a fresh Reactive Streams publisher ready to be subscribed
	 */
	public static <T> Flux<T> yield(Consumer<? super ReactiveSession<T>> sessionConsumer) {
		return new FluxSession<>(sessionConsumer);
	}

	/**
	 * @param source1
	 * @param source2
	 * @param <T1>
	 * @param <T2>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static <T1, T2> Flux<Tuple2<T1, T2>> zip(Publisher<? extends T1> source1, Publisher<? extends T2> source2) {

		return new FluxZip<>(new Publisher[]{source1, source2},
				(Function<Tuple2<T1, T2>, Tuple2<T1, T2>>) IDENTITY_FUNCTION,
				ReactiveState.XS_BUFFER_SIZE);
	}

	/**
	 * @param source1
	 * @param source2
	 * @param combinator
	 * @param <O>
	 * @param <T1>
	 * @param <T2>
	 *
	 * @return
	 */
	public static <T1, T2, O> Flux<O> zip(Publisher<? extends T1> source1,
			Publisher<? extends T2> source2,
			final BiFunction<? super T1, ? super T2, ? extends O> combinator) {

		return new FluxZip<>(new Publisher[]{source1, source2}, new Function<Tuple2<T1, T2>, O>() {
			@Override
			public O apply(Tuple2<T1, T2> tuple) {
				return combinator.apply(tuple.getT1(), tuple.getT2());
			}
		}, ReactiveState.XS_BUFFER_SIZE);
	}

	/**
	 * @param sources
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public static Flux<Tuple> zip(Iterable<? extends Publisher<?>> sources) {
		return zip(sources, IDENTITY_FUNCTION);
	}

	/**
	 * @param sources
	 * @param combinator
	 * @param <O>
	 *
	 * @return
	 */
	public static <O> Flux<O> zip(Iterable<? extends Publisher<?>> sources,
			final Function<? super Tuple, ? extends O> combinator) {

		if (sources == null) {
			return empty();
		}

		Iterator<? extends Publisher<?>> it = sources.iterator();
		if (!it.hasNext()) {
			return empty();
		}

		List<Publisher<?>> list = null;
		Publisher<?> p;
		do {
			p = it.next();
			if (list == null) {
				if (it.hasNext()) {
					list = new ArrayList<>();
				}
				else {
					return new FluxMap<>(p, new Function<Object, O>() {
						@Override
						public O apply(Object o) {
							return combinator.apply(Tuple.of(o));
						}
					});
				}
			}
			list.add(p);
		}
		while (it.hasNext());

		return new FluxZip<>(list.toArray(new Publisher[list.size()]), combinator, ReactiveState.XS_BUFFER_SIZE);
	}

	/**
	 * ==============================================================================================================
	 * ==============================================================================================================
	 * <p>
	 * Instance Operators
	 * <p>
	 * ==============================================================================================================
	 * ==============================================================================================================
	 */
	protected Flux() {
	}

	/**
	 * Return a {@code Mono<Void>} that completes when this {@link Flux} completes.
	 *
	 * @return
	 */
	public final Mono<Void> after() {
		return new MonoIgnoreElements<>(this);
	}

	/**
	 * @param capacity
	 *
	 * @return
	 */
	public final Flux<T> capacity(long capacity) {
		return new Flux.FluxBounded<>(this, capacity);
	}

	/**
	 * Like {@link #flatMap(Function)}, but concatenate emissions instead of merging (no interleave).
	 *
	 * @param mapper
	 * @param <R>
	 *
	 * @return
	 */
	public final <R> Flux<R> concatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
		return new FluxFlatMap<>(this, mapper, 1, 32);
	}

	/**
	 * Concatenate emissions of this {@link Flux} with the provided {@link Publisher} (no interleave). TODO Varargs ?
	 *
	 * @param source
	 *
	 * @return
	 */
	public final Flux<T> concatWith(Publisher<? extends T> source) {
		throw new UnsupportedOperationException(); // TODO
	}

	/**
	 * @param to
	 * @param <TO>
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public <TO> TO convert(Class<TO> to) {
		if (Publisher.class.isAssignableFrom(to.getClass())) {
			return (TO) this;
		}
		else {
			return DependencyUtils.convertFromPublisher(this, to);
		}
	}

	/**
	 * Call {@link #subscribe(Subscriber)} and return the passed {@link Subscriber}, allows for chaining, e.g. :
	 * <p>
	 * {@code Processors.topic().process(Processors.queue()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param group
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final Flux<T> dispatchOn(ProcessorGroup group) {
		FluxProcessor<T, T> processor = ((ProcessorGroup<T>) group).dispatchOn();
		subscribe(processor);
		return processor;
	}

	/**
	 * Triggered when the {@link Flux} is cancelled.
	 *
	 * @param onCancel
	 *
	 * @return
	 */
	public final Flux<T> doOnCancel(Runnable onCancel) {
		return new FluxPeek<>(this, null, null, null, null, null, null, onCancel);
	}

	/**
	 * Triggered when the {@link Flux} completes successfully.
	 *
	 * @param onComplete
	 *
	 * @return
	 */
	public final Flux<T> doOnComplete(Runnable onComplete) {
		return new FluxPeek<>(this, null, null, null, onComplete, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} completes with an error.
	 *
	 * @param onError
	 *
	 * @return
	 */
	public final Flux<T> doOnError(Consumer<? super Throwable> onError) {
		return new FluxPeek<>(this, null, null, onError, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} emits an item.
	 *
	 * @param onNext
	 *
	 * @return
	 */
	public final Flux<T> doOnNext(Consumer<? super T> onNext) {
		return new FluxPeek<>(this, null, onNext, null, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} is subscribed.
	 *
	 * @param onSubscribe
	 *
	 * @return
	 */
	public final Flux<T> doOnSubscribe(Consumer<? super Subscription> onSubscribe) {
		return new FluxPeek<>(this, onSubscribe, null, null, null, null, null, null);
	}

	/**
	 * Triggered when the {@link Flux} terminates, either by completing successfully or with an error.
	 *
	 * @param onTerminate
	 *
	 * @return
	 */
	public final Flux<T> doOnTerminate(Runnable onTerminate) {
		return new FluxPeek<>(this, null, null, null, null, onTerminate, null, null);
	}

	/**
	 * Emit only the first item emitted by this {@link Flux}.
	 *
	 * @return
	 */
	public final Mono<T> first() {
		return new MonoSingle<>(this);
	}

	/**
	 * Transform the items emitted by this {@link Flux} into Publishers, then flatten the emissions from those by
	 * merging them into a single {@link Flux}, so that they may interleave.
	 *
	 * @param mapper
	 * @param <R>
	 *
	 * @return
	 */
	public final <R> Flux<R> flatMap(Function<? super T, ? extends Publisher<? extends R>> mapper) {
		return new FluxFlatMap<>(this, mapper, ReactiveState.SMALL_BUFFER_SIZE, 32);
	}

	/**
	 * Create a {@link Flux} intercepting all source signals with the returned Subscriber that might choose to pass them
	 * alone to the provided Subscriber (given to the returned {@code subscribe(Subscriber)}.
	 *
	 * @param operator
	 * @param <R>
	 *
	 * @return
	 */
	public final <R> Flux<R> lift(Function<Subscriber<? super R>, Subscriber<? super T>> operator) {
		return new FluxLift<>(this, operator);
	}

	/**
	 * @return
	 */
	public final Flux<T> log() {
		return log(null, Level.INFO, FluxLog.ALL);
	}

	/**
	 * @param category
	 *
	 * @return
	 */
	public final Flux<T> log(String category) {
		return log(category, Level.INFO, FluxLog.ALL);
	}

	/**
	 * @param category
	 * @param level
	 *
	 * @return
	 */
	public final Flux<T> log(String category, Level level) {
		return log(category, level, FluxLog.ALL);
	}

	/**
	 * @param category
	 * @param level
	 * @param options
	 *
	 * @return
	 */
	public final Flux<T> log(String category, Level level, int options) {
		return new FluxLog<>(this, category, level, options);
	}

	/**
	 * Transform the items emitted by this {@link Flux} by applying a function to each item.
	 *
	 * @param mapper
	 * @param <R>
	 *
	 * @return
	 */
	public final <R> Flux<R> map(Function<? super T, ? extends R> mapper) {
		return new FluxMap<>(this, mapper);
	}

	/**
	 * Merge emissions of this {@link Flux} with the provided {@link Publisher}, so that they may interleave.
	 *
	 * @param source
	 *
	 * @return
	 */
	public final Flux<T> mergeWith(Publisher<? extends T> source) {
		return merge(just(this, source));
	}

	/**
	 * @param fallbackFunction
	 *
	 * @return
	 */
	public final Flux<T> onErrorResumeNext(Function<Throwable, ? extends Publisher<? extends T>> fallbackFunction) {
		return new FluxResume<>(this, fallbackFunction);
	}

	/**
	 * @param fallbackValue
	 *
	 * @return
	 */
	public final Flux<T> onErrorReturn(final T fallbackValue) {
		return switchOnError(just(fallbackValue));
	}

	/**
	 * Call {@link #subscribe(Subscriber)} and return the passed {@link Subscriber}, allows for chaining, e.g. :
	 * <p>
	 * {@code flux.process(Processors.queue()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param s
	 * @param <E>
	 *
	 * @return
	 */
	public final <E extends Subscriber<? super T>> E process(E s) {
		subscribe(s);
		return s;
	}

	/**
	 * Call {@link #subscribe(Subscriber)} and return the passed {@link Subscriber}, allows for chaining, e.g. :
	 * <p>
	 * {@code Processors.topic().process(Processors.queue()).subscribe(Subscribers.unbounded()) }
	 *
	 * @param group
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final Flux<T> publishOn(ProcessorGroup group) {
		FluxProcessor<T, T> processor = ((ProcessorGroup<T>) group).publishOn();
		subscribe(processor);
		return processor;
	}

	/**
	 * @param fallback
	 *
	 * @return
	 */
	public final Flux<T> switchOnError(final Publisher<? extends T> fallback) {
		return onErrorResumeNext(new Function<Throwable, Publisher<? extends T>>() {
			@Override
			public Publisher<? extends T> apply(Throwable throwable) {
				return fallback;
			}
		});
	}

	/**
	 * @return
	 */
	public final Flux<T> unbounded() {
		return capacity(Long.MAX_VALUE);
	}

	/**
	 * Combine the emissions of multiple Publishers together via a specified function and emit single items for each
	 * combination based on the results of this function.
	 */
	public final <R, V> Flux<V> zipWith(Publisher<? extends R> source2,
			final BiFunction<? super T, ? super R, ? extends V> zipper) {

		return new FluxZip<>(new Publisher[]{this, source2}, new Function<Tuple2<T, R>, V>() {
			@Override
			public V apply(Tuple2<T, R> tuple) {
				return zipper.apply(tuple.getT1(), tuple.getT2());
			}
		}, ReactiveState.XS_BUFFER_SIZE);

	}

	/**
	 * ==============================================================================================================
	 * ==============================================================================================================
	 *
	 * Containers
	 *
	 * ==============================================================================================================
	 * ==============================================================================================================
	 */

	/**
	 * A marker interface for components responsible for augmenting subscribers with features like {@link #lift}
	 *
	 * @param <I>
	 * @param <O>
	 */
	public interface Operator<I, O>
			extends Function<Subscriber<? super O>, Subscriber<? super I>>, ReactiveState.Factory {

	}

	/**
	 * A connecting Flux Publisher (right-to-left from a composition chain perspective)
	 *
	 * @param <I>
	 * @param <O>
	 */
	public static class FluxBarrier<I, O> extends Flux<O>
			implements ReactiveState.Factory, ReactiveState.Bounded, ReactiveState.Named, ReactiveState.Upstream {

		protected final Publisher<? extends I> source;

		public FluxBarrier(Publisher<? extends I> source) {
			this.source = source;
		}

		@Override
		public long getCapacity() {
			return Bounded.class.isAssignableFrom(source.getClass()) ? ((Bounded) source).getCapacity() :
					Long.MAX_VALUE;
		}

		@Override
		public String getName() {
			return ReactiveStateUtils.getName(getClass().getSimpleName())
			                         .replaceAll("Flux|Stream|Operator", "");
		}

		/**
		 * Default is delegating and decorating with Flux API
		 *
		 * @param s
		 */
		@Override
		@SuppressWarnings("unchecked")
		public void subscribe(Subscriber<? super O> s) {
			source.subscribe((Subscriber<? super I>) s);
		}

		@Override
		public String toString() {
			return "{" +
					" operator : \"" + getName() + "\" " +
					'}';
		}

		@Override
		public final Publisher<? extends I> upstream() {
			return source;
		}
	}

	/**
	 * Decorate a Flux with a capacity for downstream accessors
	 *
	 * @param <I>
	 */
	final static class FluxBounded<I> extends FluxBarrier<I, I> {

		final private long capacity;

		public FluxBounded(Publisher<I> source, long capacity) {
			super(source);
			this.capacity = capacity;
		}

		@Override
		public long getCapacity() {
			return capacity;
		}

		@Override
		public String getName() {
			return "Bounded";
		}

		@Override
		public void subscribe(Subscriber<? super I> s) {
			source.subscribe(s);
		}
	}

	/**
	 * i -> i
	 */
	static final class IdentityFunction implements Function {

		@Override
		public Object apply(Object o) {
			return o;
		}
	}
}