package dev.brgr.outspoke.audio

/**
 * The app has `RECORD_AUDIO` and [android.media.AudioRecord] initialised, but the platform will
 * not let this session capture audio because another client owns the microphone.
 *
 * A client-silenced session ([android.media.AudioRecordingConfiguration.isClientSilenced]) keeps
 * reporting `RECORDSTATE_RECORDING` and returns valid all-zero buffers, so capture looks healthy
 * while the engine decodes silence.
 */
class MicrophoneBusyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
