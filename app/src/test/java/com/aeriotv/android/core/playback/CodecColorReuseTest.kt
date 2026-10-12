package com.aeriotv.android.core.playback

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Android#133: color-driven decoder reuse veto. */
class CodecColorReuseTest {
    private fun hevc(codecs: String?, color: ColorInfo?) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(codecs).setColorInfo(color).build()

    private val sdr = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT709)
        .setColorTransfer(C.COLOR_TRANSFER_SDR).setColorRange(C.COLOR_RANGE_LIMITED).build()
    private val hdr10 = ColorInfo.Builder().setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorTransfer(C.COLOR_TRANSFER_ST2084).setColorRange(C.COLOR_RANGE_LIMITED).build()

    @Test fun sdrToHdr10Vetoed() =
        assertNotNull(CodecColorReuse.vetoReason(hevc(null, sdr), hevc(null, hdr10), false))

    @Test fun nullToHdrVetoed() =
        assertNotNull(CodecColorReuse.vetoReason(hevc(null, null), hevc(null, hdr10), false))

    @Test fun sameColorReused() =
        assertNull(CodecColorReuse.vetoReason(hevc(null, sdr), hevc(null, sdr), true))

    @Test fun bothNullHevcLiveUnknownVetoed() =
        assertNotNull(CodecColorReuse.vetoReason(hevc(null, null), hevc(null, null), true))

    @Test fun bothNullHevcMain10LiveVetoed() =
        assertNotNull(CodecColorReuse.vetoReason(hevc("hvc1.2.4.L153.B0", null), hevc("hvc1.2.4.L153.B0", null), true))

    @Test fun bothNullHevcMain8LiveReused() =
        assertNull(CodecColorReuse.vetoReason(hevc("hvc1.1.6.L120.90", null), hevc("hev1.1.6.L150.90", null), true))

    @Test fun bothNullHevcNotLiveReused() =
        assertNull(CodecColorReuse.vetoReason(hevc(null, null), hevc(null, null), false))

    @Test fun bothNullH264LiveReused() {
        val avc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        assertNull(CodecColorReuse.vetoReason(avc, avc, true))
    }
}
