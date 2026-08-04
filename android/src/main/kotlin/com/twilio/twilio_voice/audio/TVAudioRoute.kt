package com.twilio.twilio_voice.audio

/**
 * An output the user can send call audio to.
 *
 * @param id matches [android.media.AudioDeviceInfo.getId] from API 31, or a synthetic
 *   negative id below that, where the platform has no per-device routing.
 * @param name what to show the user — a Bluetooth device's own name where it reports one,
 *   so two paired devices are distinguishable.
 * @param type the underlying [android.media.AudioDeviceInfo] type, for choosing an icon.
 * @param isActive whether call audio is currently going here.
 */
data class TVAudioRoute(
    val id: Int,
    val name: String,
    val type: Int,
    val isActive: Boolean,
)
