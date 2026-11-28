package com.rawr.camera.architecture

/**
 * Rawr 17U fork: the S/I priority switch (model/PriorityToggle.kt). [toAuto] = back to Auto (long press on the top-bar
 * button); otherwise S goes to I and anything else to S, with the saved value of the side entered.
 */
data class TogglePriority(val toAuto: Boolean = false) : ApplicationCaptureAction
