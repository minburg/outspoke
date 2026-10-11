package dev.brgr.outspoke.inference

import android.content.pm.ServiceInfo

/**
 * Foreground-service promotion strategy for [InferenceService].
 *
 * [InferenceService] is started via [android.content.Context.startForegroundService], so Android
 * requires [android.app.Service.startForeground] to be called within 5 s or it ANRs the process
 * (*"Context.startForegroundService() did not then call Service.startForeground()"*).
 *
 * The `microphone` FGS type is while-in-use restricted on Android 14+: when the app is
 * backgrounded (or in a work-profile edge case) [android.app.Service.startForeground] rejects it
 * with a [SecurityException]. The old code caught that exception and continued as a "plain bound
 * service" — but a service started with a start command has no such fallback: if
 * [android.app.Service.startForeground] is never called, the 5 s deadline elapses and the process
 * is ANR'd (issue #37).
 *
 * Strategy:
 *  1. Try `microphone` first — the semantically correct type for an audio-recording service.
 *  2. On rejection, fall back to `specialUse`, which is NOT while-in-use restricted, so the
 *     deadline is always met and the service keeps running as a foreground service with a
 *     notification.
 *  3. [promoteMicrophone] upgrades back to `microphone` once while-in-use eligibility is
 *     restored (an activity resumes, or the IME window is shown).
 *
 * This class is deliberately free of Android framework calls: the real
 * [android.app.Service.startForeground] and [android.app.Service.stopSelf] are supplied as
 * callbacks ([startForeground] / [onAllTypesRejected]), so the strategy is unit-testable on the
 * plain JVM (see [FgsPromoterTest]).
 */
internal class FgsPromoter(
    private val startForeground: (type: Int) -> Unit,
    private val onAllTypesRejected: () -> Unit,
) {
    /** The FGS type currently in effect, or 0 if not yet promoted to the foreground. */
    var currentType: Int = 0
        private set

    /** `true` once [android.app.Service.startForeground] has been called successfully. */
    val isForeground: Boolean
        get() = currentType != 0

    /**
     * Promote to the foreground, trying `microphone` then `specialUse`. No-op when already
     * foreground. Invokes [onAllTypesRejected] if every type is rejected — the service must then
     * stop itself, because a started service that cannot call
     * [android.app.Service.startForeground] will ANR the process.
     */
    fun promote() {
        if (currentType != 0) return
        try {
            startForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            currentType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } catch (e: SecurityException) {
            try {
                startForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                currentType = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } catch (e2: SecurityException) {
                onAllTypesRejected()
            }
        }
    }

    /**
     * Re-attempt the `microphone` type now that while-in-use eligibility is restored.
     *
     * No-op when already `microphone`. When not yet foreground (type 0), delegates to [promote]
     * so the service is still brought to the foreground. When on `specialUse`, a rejected upgrade
     * simply keeps the `specialUse` type (the service is already foreground, so no ANR risk).
     */
    fun promoteMicrophone() {
        if (currentType == ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) return
        if (currentType == 0) {
            promote()
            return
        }
        try {
            startForeground(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            currentType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } catch (e: SecurityException) {
            // Still not eligible; keep the specialUse type.
        }
    }
}
