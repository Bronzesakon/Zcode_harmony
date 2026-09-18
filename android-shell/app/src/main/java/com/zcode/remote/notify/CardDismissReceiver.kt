package com.zcode.remote.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zcode.remote.ShellRuntime

/**
 * The 「知道了」 action on a 已完成 card (2026-09-17).
 *
 * Why an action and not just "let the user swipe it": a card that holds a promotion
 * slot must be `ongoing` (平台硬条件，见 LiveUpdate), and an ongoing notification
 * cannot be swiped away on AOSP. So the card that the user is looking at — the one
 * that says 已完成 — needs its own way out; that is this. A card that has *lost* its
 * slot is demoted to a plain dismissible notification and can be swiped as usual.
 *
 * Not exported: only our own PendingIntent can reach it.
 */
class CardDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        ShellRuntime.notifier().dismissFinishedCard(id)
    }

    companion object {
        const val ACTION_DISMISS = "com.zcode.remote.action.DISMISS_CARD"
        const val EXTRA_ID = "card_id"
    }
}
