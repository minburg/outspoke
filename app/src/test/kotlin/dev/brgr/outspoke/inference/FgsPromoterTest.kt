package dev.brgr.outspoke.inference

import android.content.pm.ServiceInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Unit tests for [FgsPromoter] — the foreground-service promotion strategy that prevents the
 * ANR *"Context.startForegroundService() did not then call Service.startForeground()"*.
 *
 * The invariant under test: [InferenceService] is started via [android.content.Context.
 * startForegroundService], so the system requires [android.app.Service.startForeground] within
 * 5 s or it ANRs the process. The `microphone` FGS type is while-in-use restricted on Android
 * 14+: when the app is backgrounded (or in a work-profile edge case) [android.app.Service.
 * startForeground] rejects it with a [SecurityException]. The promoter must therefore ALWAYS
 * end in a foreground state — falling back to `specialUse` (not while-in-use restricted) —
 * rather than skipping [android.app.Service.startForeground] entirely (which is what the old
 * catch-and-log did, and what triggered the ANR in issue #37).
 *
 * [FgsPromoter] is deliberately free of Android framework calls: the real
 * [android.app.Service.startForeground] / [android.app.Service.stopSelf] are supplied as
 * callbacks, so this strategy is testable on the plain JVM.
 */
class FgsPromoterTest {

    private val microphone = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
    private val specialUse = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

    /**
     * A configurable fake for the [android.app.Service.startForeground] seam. [rejectMicrophone]
     * / [rejectSpecialUse] can be flipped between calls to model the app regaining (or losing)
     * while-in-use eligibility. [calls] records every type the promoter tried, in order.
     */
    private inner class FakeFgs(
        var rejectMicrophone: Boolean,
        var rejectSpecialUse: Boolean = false,
        val calls: MutableList<Int> = mutableListOf(),
        val onAllRejected: () -> Unit = { error("onAllTypesRejected must not be called") },
    ) {
        val promoter = FgsPromoter(
            startForeground = { type ->
                calls.add(type)
                if (type == microphone && rejectMicrophone) {
                    throw SecurityException("simulated while-in-use rejection of microphone FGS")
                }
                if (type == specialUse && rejectSpecialUse) {
                    throw SecurityException("simulated rejection of specialUse FGS")
                }
            },
            onAllTypesRejected = onAllRejected,
        )
    }

    // ── promote(): initial promotion ────────────────────────────────────────────────

    @Test
    fun `promote uses microphone when the app is eligible`() {
        val fake = FakeFgs(rejectMicrophone = false)
        fake.promoter.promote()
        assertThat(fake.calls).containsExactly(microphone)
        assertThat(fake.promoter.currentType).isEqualTo(microphone)
        assertThat(fake.promoter.isForeground).isTrue()
    }

    @Test
    fun `promote falls back to specialUse when microphone is rejected`() {
        val fake = FakeFgs(rejectMicrophone = true)
        fake.promoter.promote()
        // microphone was attempted first, then specialUse succeeded — so startForeground WAS
        // called and the 5 s startForegroundService() deadline is met (no ANR).
        assertThat(fake.calls).containsExactly(microphone, specialUse)
        assertThat(fake.promoter.currentType).isEqualTo(specialUse)
        assertThat(fake.promoter.isForeground).isTrue()
    }

    @Test
    fun `promote stops the service when every type is rejected`() {
        var stopped = false
        val fake = FakeFgs(
            rejectMicrophone = true,
            rejectSpecialUse = true,
            onAllRejected = { stopped = true },
        )
        fake.promoter.promote()
        assertThat(fake.calls).containsExactly(microphone, specialUse)
        // A service that cannot call startForeground() at all must stop itself, or the system
        // ANRs the process. Stopping clears the pending start command and its deadline.
        assertThat(stopped).isTrue()
        assertThat(fake.promoter.currentType).isEqualTo(0)
        assertThat(fake.promoter.isForeground).isFalse()
    }

    @Test
    fun `promote is idempotent once foreground`() {
        val fake = FakeFgs(rejectMicrophone = true)
        fake.promoter.promote()
        fake.promoter.promote()
        // The second call is a no-op — no repeated startForeground() attempts.
        assertThat(fake.calls).containsExactly(microphone, specialUse)
        assertThat(fake.promoter.currentType).isEqualTo(specialUse)
    }

    // ── promoteMicrophone(): upgrade once eligibility is restored ───────────────────

    @Test
    fun `promoteMicrophone is a no-op when already microphone`() {
        val fake = FakeFgs(rejectMicrophone = false)
        fake.promoter.promote()
        fake.promoter.promoteMicrophone()
        assertThat(fake.calls).containsExactly(microphone)
        assertThat(fake.promoter.currentType).isEqualTo(microphone)
    }

    @Test
    fun `promoteMicrophone upgrades specialUse to microphone once eligible`() {
        val fake = FakeFgs(rejectMicrophone = true)
        fake.promoter.promote()
        assertThat(fake.promoter.currentType).isEqualTo(specialUse)

        // The app regains while-in-use eligibility (activity resumed / IME window shown).
        fake.rejectMicrophone = false
        fake.promoter.promoteMicrophone()

        assertThat(fake.calls).containsExactly(microphone, specialUse, microphone)
        assertThat(fake.promoter.currentType).isEqualTo(microphone)
    }

    @Test
    fun `promoteMicrophone keeps specialUse when still not eligible`() {
        val fake = FakeFgs(rejectMicrophone = true)
        fake.promoter.promote()
        // Still backgrounded: the upgrade attempt is rejected and we stay on specialUse.
        // Crucially this does NOT stop the service (it is already foreground).
        fake.promoter.promoteMicrophone()
        assertThat(fake.calls).containsExactly(microphone, specialUse, microphone)
        assertThat(fake.promoter.currentType).isEqualTo(specialUse)
        assertThat(fake.promoter.isForeground).isTrue()
    }

    @Test
    fun `promoteMicrophone performs a full promote when not yet foreground`() {
        // Edge: the service was never promoted (e.g. it was stopped after a total rejection and
        // recreated, or promotion is deferred). promoteMicrophone() must still bring it to the
        // foreground, falling back to specialUse if microphone is unavailable.
        val fake = FakeFgs(rejectMicrophone = true)
        fake.promoter.promoteMicrophone()
        assertThat(fake.calls).containsExactly(microphone, specialUse)
        assertThat(fake.promoter.currentType).isEqualTo(specialUse)
        assertThat(fake.promoter.isForeground).isTrue()
    }
}
