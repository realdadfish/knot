package de.halfbit.knot

import io.reactivex.Maybe
import io.reactivex.Observable
import kotlin.reflect.KClass

fun <State : Any, Change : Any, Action : Any> prime(
    block: PrimeBuilder<State, Change, Action>.() -> Unit
): Prime<State, Change, Action> =
    PrimeBuilder<State, Change, Action>()
        .also(block)
        .build()

@KnotDsl
class PrimeBuilder<State : Any, Change : Any, Action : Any>
internal constructor() {
    private val reducers = mutableMapOf<KClass<out Change>, Reducer<State, Change, Action>>()
    private val eventTransformers = mutableListOf<EventTransformer<Change>>()
    private val actionTransformers = mutableListOf<ActionTransformer<Action, Change>>()
    private val stateInterceptors = mutableListOf<Interceptor<State>>()
    private val changeInterceptors = mutableListOf<Interceptor<Change>>()
    private val actionInterceptors = mutableListOf<Interceptor<Action>>()
    private val stateTriggers = mutableListOf<StateTrigger<State, Change>>()

    /** A section for [State] related declarations. */
    fun state(block: StateBuilder<State, Change>.() -> Unit) {
        StateBuilder(stateInterceptors, stateTriggers).also(block)
    }

    /** A section for [Change] related declarations. */
    fun changes(block: ChangesBuilder<State, Change, Action>.() -> Unit) {
        ChangesBuilder(reducers, changeInterceptors).also(block)
    }

    /** A section for [Action] related declarations. */
    fun actions(block: ActionsBuilder<Change, Action>.() -> Unit) {
        ActionsBuilder(actionTransformers, actionInterceptors).also(block)
    }

    /** A section for *Event* related declarations. */
    fun events(block: EventsBuilder<Change>.() -> Unit) {
        EventsBuilder(eventTransformers).also(block)
    }

    /** A section for declaring interceptors of [State], [Change] or [Action]. */
    fun intercept(block: InterceptBuilder<State, Change, Action>.() -> Unit) {
        InterceptBuilder(stateInterceptors, changeInterceptors, actionInterceptors).also(block)
    }

    /** A section for declaring watchers of [State], [Change] or [Action]. */
    fun watch(block: WatchBuilder<State, Change, Action>.() -> Unit) {
        WatchBuilder(stateInterceptors, changeInterceptors, actionInterceptors).also(block)
    }

    internal fun build(): Prime<State, Change, Action> = DefaultPrime(
        reducers = reducers,
        eventTransformers = eventTransformers,
        actionTransformers = actionTransformers,
        stateInterceptors = stateInterceptors,
        changeInterceptors = changeInterceptors,
        actionInterceptors = actionInterceptors,
        stateTriggers = stateTriggers
    )

    @KnotDsl
    class StateBuilder<State : Any, Change : Any>
    internal constructor(
        private val stateInterceptors: MutableList<Interceptor<State>>,
        private val stateTriggers: MutableList<StateTrigger<State, Change>>
    ) {

        /** A function for intercepting [State] mutations. */
        fun intercept(interceptor: Interceptor<State>) {
            stateInterceptors += interceptor
        }

        /** A function for watching mutations of any [State]. */
        fun watchAll(watcher: Watcher<State>) {
            stateInterceptors += WatchingInterceptor(watcher)
        }

        /** A function for watching mutations of all `States`. */
        inline fun <reified T : State> watch(noinline watcher: Watcher<T>) {
            watchAll(TypedWatcher(T::class.java, watcher))
        }

        @PublishedApi
        internal fun onState(stateTrigger: StateTrigger<State, Change>) {
            stateTriggers += stateTrigger
        }

        /**
         * A functions emitting a [Change] when the [State] turns into a state of given type.
         *
         * This function is used when `State` follows State Machine design pattern. It's called
         * just once, right after the state of given type has been entered. If a `Prime` is
         * responsible for state initialization, this is the initial trigger to launch such
         * initialization.
         *
         * *Experimental, can be changed in next version*
         */
        inline fun <reified S : State> onEnter(noinline stateTrigger: StateTrigger<State, Change>) {
            onState(OnEnterStateTrigger(S::class.java, stateTrigger))
        }
    }

    @KnotDsl
    class ChangesBuilder<State : Any, Change : Any, Action : Any>
    internal constructor(
        private val reducers: MutableMap<KClass<out Change>, Reducer<State, Change, Action>>,
        private val changeInterceptors: MutableList<Interceptor<Change>>
    ) {

        /**
         * Mandatory reduce function which receives the current [State] and a [Change]
         * and must return [Effect] with a new [State] and an optional [Action].
         *
         * New `State` and `Action` can be joined together using overloaded [State.plus()]
         * operator. For returning `State` without action call *.only* on the state.
         *
         * Example:
         * ```
         *  changes {
         *      reduce { change ->
         *          when (change) {
         *              is Change.Load -> copy(value = "loading") + Action.Load
         *              is Change.Load.Success -> copy(value = change.payload).only
         *              is Change.Load.Failure -> copy(value = "failed").only
         *          }
         *      }
         *  }
         * ```
         */
        fun reduce(changeType: KClass<out Change>, reduce: Reducer<State, Change, Action>) {
            reducers[changeType] = reduce
        }

        inline fun <reified C : Change> reduce(noinline reduce: Reducer<State, C, Action>) {
            @Suppress("UNCHECKED_CAST")
            reduce(C::class, reduce as Reducer<State, Change, Action>)
        }

        /** A function for intercepting [Change] emissions. */
        fun intercept(interceptor: Interceptor<Change>) {
            changeInterceptors += interceptor
        }

        /** A function for watching [Change] emissions. */
        fun watch(watcher: Watcher<Change>) {
            changeInterceptors += WatchingInterceptor(watcher)
        }

        /** Turns [State] into an [Effect] without [Action]. */
        val State.only: Effect<State, Action> get() = Effect(this)

        /** Combines [State] and [Action] into [Effect]. */
        operator fun State.plus(action: Action) = Effect(this, action)

        /** Throws [IllegalStateException] with current [State] and given [Change] in its message. */
        fun State.unexpected(change: Change): Nothing = error("Unexpected $change in $this")
    }
}

@PublishedApi
internal class OnEnterStateTrigger<State : Any, Change : Any, S : State>(
    private val type: Class<S>,
    private val stateTrigger: StateTrigger<State, Change>
) : StateTrigger<State, Change> {
    override fun invoke(state: Observable<State>): Observable<Change> =
        stateTrigger(
            state
                .map { Optional(it) }
                .scan(Optional<State>()) { old, new ->
                    if (new.isOfType(type) && !old.isOfType(type)) new
                    else Optional()
                }
                .flatMapMaybe {
                    if (it.value != null) Maybe.just(it.value)
                    else Maybe.empty()
                }
        )
}

internal class Optional<T : Any>(val value: T? = null) {
    fun isOfType(type: Class<out T>): Boolean =
        value != null && value::class.java.isAssignableFrom(type)
}
