package com.z1pendant

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Detent feel for the handwheel. Uses the vibration motor's "primitives" (short, sharp waveforms
 * tuned per device; both the Pixel 10 and S24 Ultra support them) and falls back to the predefined
 * tick/click effects on phones that don't.
 */
class AndroidHaptics(ctx: Context) : Haptics {
    private val vib: Vibrator =
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator

    /** 0 = off, 1 = full. */
    @Volatile override var strength: Float = 0.7f

    private val hasTick = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_TICK)
    private val hasClick = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_CLICK)
    private val hasThud = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_THUD)
    private val hasLowTick = vib.areAllPrimitivesSupported(Composition.PRIMITIVE_LOW_TICK)
    override val description: String
        get() = "primitives: tick=$hasTick click=$hasClick thud=$hasThud lowTick=$hasLowTick"

    private fun prim(p: Int, supported: Boolean, scale: Float, fallback: Int) {
        if (strength <= 0f) return
        val s = (scale * strength).coerceIn(0.05f, 1f)
        val effect = if (supported) VibrationEffect.startComposition().addPrimitive(p, s).compose()
        else VibrationEffect.createPredefined(fallback)
        vib.vibrate(effect)
    }

    /** One detent. */
    override fun tick() = prim(Composition.PRIMITIVE_TICK, hasTick, 0.8f, VibrationEffect.EFFECT_TICK)
    /** Every 10th detent. */
    override fun majorTick() = prim(Composition.PRIMITIVE_CLICK, hasClick, 0.9f, VibrationEffect.EFFECT_CLICK)
    /** Clicks dropped because the machine can't keep up, or blocked (alarm, disconnected). */
    override fun thud() = prim(if (hasThud) Composition.PRIMITIVE_THUD else Composition.PRIMITIVE_CLICK,
        hasThud || hasClick, 1f, VibrationEffect.EFFECT_HEAVY_CLICK)
    /** Soft-limit reached: a double knock. */
    override fun limit() {
        if (strength <= 0f) return
        if (hasClick) vib.vibrate(VibrationEffect.startComposition()
            .addPrimitive(Composition.PRIMITIVE_CLICK, strength)
            .addPrimitive(Composition.PRIMITIVE_CLICK, strength, 60).compose())
        else vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    }
    /** Button press confirmation. */
    override fun press() = prim(if (hasLowTick) Composition.PRIMITIVE_LOW_TICK else Composition.PRIMITIVE_TICK,
        hasLowTick || hasTick, 1f, VibrationEffect.EFFECT_TICK)
}
