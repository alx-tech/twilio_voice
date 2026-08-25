package com.twilio.twilio_voice.audio

import com.twilio.voice.AcceptOptions
import com.twilio.voice.AudioCodec
import com.twilio.voice.OpusCodec
import com.twilio.voice.PcmuCodec

/**
 * Codec preference for every call this plugin sets up.
 *
 * The SDK's own default order is PCMU first, and PCMU is G.711: a flat 64 kbps
 * with no packet-loss concealment, so jitter on a cellular or dealership Wi-Fi
 * link is heard directly as choppy or robotic audio. Opus adapts its bitrate and
 * conceals loss, which is what Twilio recommends for exactly those conditions.
 *
 * PCMU is kept as the fallback so a leg that cannot negotiate Opus still
 * connects rather than failing outright.
 */
internal object TVAudioCodecs {

    private fun preferred(): List<AudioCodec> = listOf(OpusCodec(), PcmuCodec())

    /** Options for answering an incoming call invite. */
    fun acceptOptions(): AcceptOptions =
        AcceptOptions.Builder().preferAudioCodecs(preferred()).build()

    /** Applies the same preference to an outgoing call. */
    fun applyTo(builder: com.twilio.voice.ConnectOptions.Builder): com.twilio.voice.ConnectOptions.Builder =
        builder.preferAudioCodecs(preferred())
}
