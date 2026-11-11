package io.github.sms2email.sms2email

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

    val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
    val sender = messages.firstOrNull()?.originatingAddress ?: return
    val body = messages.joinToString("") { it.messageBody }

    // goAsync() tells the system the broadcast is still being processed, so it
    // will NOT reclaim the process (and kill the send thread) the moment
    // onReceive returns. Without this, forwarding silently fails whenever the
    // app was previously killed by the system or the OEM battery manager.
    val pendingResult = goAsync()
    Thread {
      try {
        sendMailSync(context, sender, body)
      } finally {
        pendingResult.finish()
      }
    }.start()
  }
}
