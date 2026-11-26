package dev.brgr.outspoke.audio

/**
 * The app has `RECORD_AUDIO` and [android.media.AudioRecord] initialised, but the platform will
 * not let this session actually capture audio because another client owns the microphone.
 *
 * Android does **not** report this through the start handshake. Since Android 10 the loser of
 * input arbitration keeps a perfectly healthy-looking recorder that is *client-silenced*
 * ([android.media.AudioRecordingConfiguration.isClientSilenced]): `read()` keeps returning
 * valid, all-zero buffers and `recordingState` stays `RECORDSTATE_RECORDING`. Without an explicit
 * check the keyboard therefore shows "Listening" with a flat meter, the engine receives digital
 * silence, no text appears, and no error is ever raised.
 *
 * [AudioCaptureManager.startCapture] throws this in the two situations that can be observed:
 * the start handshake refuses the recorder, or the active recording configuration reports
 * `isClientSilenced` repeatedly while reading.
 */
class MicrophoneBusyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
