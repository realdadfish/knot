package de.halfbit.knot

import io.reactivex.Scheduler

fun <State : Any, Change : Any, Action : Any> compositeKnot(
    block: CompositeKnotBuilder<State, Change, Action>.() -> Unit
): CompositeKnot<State, Change, Action> =
    CompositeKnotBuilder<State, Change, Action>()
        .also(block)
        .build()

@KnotDsl
class CompositeKnotBuilder<State : Any, Change : Any, Action : Any>
internal constructor() {

    private var initialState: State? = null
    private var observeOn: Scheduler? = null
    private var reduceOn: Scheduler? = null
    private val changeInterceptors = mutableListOf<Interceptor<Change>>()
    private val stateInterceptors = mutableListOf<Interceptor<State>>()
    private val actionInterceptors = mutableListOf<Interceptor<Action>>()

    /** A section for [State] related declarations. */
    fun state(block: StateBuilder<State>.() -> Unit) {
        StateBuilder<State>()
            .also {
                block(it)
                initialState = it.initial
                observeOn = it.observeOn
            }
    }

    /** A section for [Change] related declarations. */
    fun changes(block: ChangesBuilder<Change>.() -> Unit) {
        ChangesBuilder(changeInterceptors)
            .also {
                block(it)
                reduceOn = it.reduceOn
            }
    }

    /** A section for declaring watchers for [State], [Change] or [Action]. */
    fun watch(block: WatchBuilder<State, Change, Action>.() -> Unit) {
        WatchBuilder(stateInterceptors, changeInterceptors, actionInterceptors).also(block)
    }

    internal fun build(): CompositeKnot<State, Change, Action> = DefaultCompositeKnot(
        initialState = checkNotNull(initialState) { "state { initial } must be set" },
        observeOn = observeOn,
        reduceOn = reduceOn,
        stateInterceptors = stateInterceptors,
        changeInterceptors = changeInterceptors,
        actionInterceptors = actionInterceptors
    )

    @KnotDsl
    class StateBuilder<State : Any>
    internal constructor() {

        /** Mandatory initial [State] of the [Knot]. */
        var initial: State? = null

        /** An optional [Scheduler] used for dispatching state changes. */
        var observeOn: Scheduler? = null
    }

    @KnotDsl
    class ChangesBuilder<Change : Any>
    internal constructor(
        private val changeInterceptors: MutableList<Interceptor<Change>>
    ) {
        /** An optional [Scheduler] used for reduce function. */
        var reduceOn: Scheduler? = null

        /** A function for intercepting [Change] emissions. */
        fun intercept(interceptor: Interceptor<Change>) {
            changeInterceptors += interceptor
        }

        /** A function for watching [Change] emissions. */
        fun watchAll(watcher: Watcher<Change>) {
            changeInterceptors += WatchingInterceptor(watcher)
        }

        /** A function for watching emissions of all `Changes`. */
        inline fun <reified T : Change> watch(noinline watcher: Watcher<T>) {
            watchAll(TypedWatcher(T::class.java, watcher))
        }
    }
}
