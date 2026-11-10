package io.github.ayatinene.sonycompanion.pairing

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import java.io.IOException

/** Foreground NDEF dispatch, also used by the official app. No Android Beam or tag writes. */
class NfcPairing(private val activity: Activity) {
    private val adapter = NfcAdapter.getDefaultAdapter(activity)
    private var dispatching = false
    val available: Boolean get() = adapter != null
    val enabled: Boolean get() = adapter?.isEnabled == true
    fun resume() {
        if (!enabled || dispatching) return
        val target = Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(activity, 0, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val filter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply { addDataType(SonyNfcParser.MIME) }
        adapter?.enableForegroundDispatch(activity, pending, arrayOf(filter), null)
        dispatching = true
    }
    fun pause() {
        if (dispatching) adapter?.disableForegroundDispatch(activity)
        dispatching = false
    }
    companion object {
        @Suppress("DEPRECATION")
        fun read(intent: Intent): PairingCredentials {
            val messages = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                ?.filterIsInstance<NdefMessage>().orEmpty()
            return readRecords(messages.flatMap { it.records.toList() })
        }
        fun readRecords(records: List<NdefRecord>): PairingCredentials {
            val candidates = records.filter { it.tnf == NdefRecord.TNF_MIME_MEDIA && it.type.toString(Charsets.US_ASCII) == SonyNfcParser.MIME }
            if (candidates.size != 1) throw IOException("未读到唯一的 Sony NFC 配对记录，请重新触碰或扫描二维码")
            return SonyNfcParser.parse(SonyNfcParser.MIME, candidates.single().payload)
        }
    }
}
