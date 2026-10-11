package com.aeriotv.android.core.playback

import android.util.Log
import android.util.SparseArray
import androidx.annotation.OptIn
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.ts.Ac3Reader
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader

/**
 * A TsExtractor, built exactly as DefaultExtractorsFactory builds it in
 * MODE_SINGLE_PMT, whose AC-3 / E-AC-3 reader survives corrupt syncframe
 * headers.
 *
 * media3 1.4.1 Ac3Util indexes its bitrate and frame-size tables with the
 * header's frmsizecod unchecked, so a damaged header (sync word present,
 * frmsizecod above 37) throws ArrayIndexOutOfBoundsException out of
 * Ac3Reader.consume. That fails the whole load ("Unexpected exception
 * loading stream", ERROR_CODE_IO_UNSPECIFIED) and the tile or player goes
 * through a full reconnect. Measured on the Nothing Phone 2026-10-11 16:13:53
 * and 16:14:52 (composite tile ESPNU HD: "length=19; index=30" in
 * Ac3Reader), while a capture of the same stream at that time showed AC-3
 * "invalid bitstream id" / "invalid frame size" errors in ffmpeg. Here the
 * reader drops back to sync search instead, losing only the damaged frame.
 */
@OptIn(UnstableApi::class)
fun safeTsExtractor(): TsExtractor = TsExtractor(
    TsExtractor.MODE_SINGLE_PMT,
    /* extractorFlags = */ 0,
    DefaultSubtitleParserFactory(),
    TimestampAdjuster(0),
    SafeTsPayloadReaderFactory(DefaultTsPayloadReaderFactory(0)),
    TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES,
)

@OptIn(UnstableApi::class)
private class SafeTsPayloadReaderFactory(
    private val inner: DefaultTsPayloadReaderFactory,
) : TsPayloadReader.Factory {
    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = inner.createInitialPayloadReaders()

    override fun createPayloadReader(streamType: Int, esInfo: TsPayloadReader.EsInfo): TsPayloadReader? =
        when (streamType) {
            TsExtractor.TS_STREAM_TYPE_AC3, TsExtractor.TS_STREAM_TYPE_E_AC3 ->
                PesReader(ResyncingReader(Ac3Reader(esInfo.language, esInfo.roleFlags), "AC-3"))
            else -> inner.createPayloadReader(streamType, esInfo)
        }
}

@OptIn(UnstableApi::class)
private class ResyncingReader(
    private val inner: ElementaryStreamReader,
    private val name: String,
) : ElementaryStreamReader by inner {
    private var drops = 0

    override fun consume(data: ParsableByteArray) {
        try {
            inner.consume(data)
        } catch (e: RuntimeException) {
            resync(e)
            data.setPosition(data.limit())
        }
    }

    override fun packetFinished(isEndOfInput: Boolean) {
        try {
            inner.packetFinished(isEndOfInput)
        } catch (e: RuntimeException) {
            resync(e)
        }
    }

    private fun resync(e: RuntimeException) {
        drops++
        if (drops <= 5 || drops % 100 == 0) {
            Log.w("AerioPlayer", "[TS-AUDIO] corrupt $name header dropped, resyncing (${e.javaClass.simpleName}: ${e.message}) drops=$drops")
        }
        inner.seek()
    }
}
