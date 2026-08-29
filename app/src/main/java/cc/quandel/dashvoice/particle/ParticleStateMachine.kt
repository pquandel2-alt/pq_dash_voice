package cc.quandel.dashvoice.particle

import cc.quandel.dashvoice.util.AppLog as Log
import java.util.concurrent.atomic.AtomicReference

class ParticleStateMachine {
    private val currentState = AtomicReference(ParticleState.OFF)
    var onStateChanged: ((previous: ParticleState, new: ParticleState) -> Unit)? = null

    fun getState(): ParticleState = currentState.get()

    fun transitionTo(newState: ParticleState): Boolean {
        val prev = currentState.getAndSet(newState)
        if (prev != newState) {
            Log.i("ParticleState", "Transition: $prev → $newState")
            onStateChanged?.invoke(prev, newState)
            return true
        }
        return false
    }

    fun isActive(): Boolean = getState() != ParticleState.OFF

    companion object {
        private const val TAG = "ParticleState"
    }
}
