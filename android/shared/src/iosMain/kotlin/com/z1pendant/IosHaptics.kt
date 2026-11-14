package com.z1pendant

import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.Foundation.NSThread
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UISelectionFeedbackGenerator
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.Volatile

/**
 * iPhone version of the detent feel (Taptic Engine). Mapping of the Android primitives:
 * tick = light impact, major tick (every 10th) = medium, thud (dropped / blocked) = heavy,
 * soft limit = two rigid knocks 60 ms apart, button press = selection click. Intensity follows
 * the strength slider (the selection click has no intensity: it's just on or off).
 */
class IosHaptics : Haptics {
    @Volatile override var strength: Float = 0.7f
    override val description: String get() = "iOS Taptic Engine (UIImpactFeedbackGenerator)"

    private val light by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight) }
    private val medium by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium) }
    private val heavy by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy) }
    private val rigid by lazy { UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleRigid) }
    private val selection by lazy { UISelectionFeedbackGenerator() }
    private val scope = MainScope()

    /** UIKit wants the main thread. */
    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private fun impact(g: () -> UIImpactFeedbackGenerator, scale: Float) {
        if (strength <= 0f) return
        val s = (scale * strength).coerceIn(0.05f, 1f)
        onMain { g().impactOccurredWithIntensity(s.toDouble()) }
    }

    override fun tick() = impact({ light }, 0.8f)
    override fun majorTick() = impact({ medium }, 0.9f)
    override fun thud() = impact({ heavy }, 1f)
    override fun limit() {
        if (strength <= 0f) return
        onMain {
            rigid.impactOccurredWithIntensity(strength.toDouble())
            scope.launch { delay(60); rigid.impactOccurredWithIntensity(strength.toDouble()) }
        }
    }
    override fun press() {
        if (strength <= 0f) return
        onMain { selection.selectionChanged() }
    }
}
