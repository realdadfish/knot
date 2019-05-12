package de.halfbit.knot

import io.reactivex.Observable
import io.reactivex.subjects.PublishSubject
import org.junit.Test

class KnotOnEnterStateTest {

    private sealed class State {
        data class Empty(val loading: Boolean) : State()
        object Content : State()
    }

    private sealed class Change {
        object Load : Change() {
            object Done : Change()
        }
    }

    private sealed class Action {
        object Load : Action()
    }

    @Test
    fun `state { onEnter } called for initial state`() {
        val enteredStateObserver = PublishSubject.create<State>()
        val observer = enteredStateObserver.test()

        val knot = knot<State, Change, Action> {
            state {
                initial = State.Empty(loading = false)
                onEnter<State.Empty> { state ->
                    state
                        .doOnNext { enteredStateObserver.onNext(it) }
                        .flatMap { Observable.fromArray(Change.Load, Change.Load) }
                }
            }
            changes {
                reduce { change ->
                    when (this) {
                        is State.Empty -> when (change) {
                            Change.Load -> copy(loading = true).only
                            else -> unexpected(change)
                        }
                        else -> unexpected(change)
                    }
                }
            }
        }

        val states = knot.state.test()

        states.assertValues(
            State.Empty(loading = false),
            State.Empty(loading = true)
        )

        observer.assertValues(
            State.Empty(loading = false)
        )
    }

    @Test
    fun `state { onEnter } not called for same state type`() {
        TODO()
    }

    @Test
    fun `state { onEnter } called when state type changes`() {
        TODO()
    }

}