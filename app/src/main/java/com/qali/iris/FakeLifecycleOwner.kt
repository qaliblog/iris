package com.qali.iris

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Fake LifecycleOwner for AccessibilityService
 * AccessibilityService is not a LifecycleOwner, so we create a fake one
 * This allows CameraX to bind the camera even when the app is in background
 */
class FakeLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    
    init {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    
    fun markState(state: Lifecycle.State) {
        lifecycleRegistry.currentState = state
    }
}
