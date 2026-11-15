package com.example.callredirect

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CallRedirectService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var armedAt = 0L          // time the user tapped "Voice call"
    private var chatName: String? = null

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.packageName?.toString() != "com.whatsapp") return
        val now = System.currentTimeMillis()

        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val desc = (e.contentDescription ?: e.source?.contentDescription)?.toString()
                if (desc.equals("Voice call", ignoreCase = true)) {
                    armedAt = now
                    chatName = rootInActiveWindow
                        ?.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_contact_name")
                        ?.firstOrNull()?.text?.toString()
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val cls = e.className?.toString() ?: return
                // Only act if YOU just tapped the call button (ignores incoming calls)
                if (armedAt != 0L && now - armedAt < 8000 && cls.contains("VoipActivity")) {
                    armedAt = 0L
                    handler.postDelayed({ redirect() }, 400)
                }
            }
        }
    }

    private fun redirect() {
        val root = rootInActiveWindow
        val candidates = linkedSetOf<String>()
        chatName?.let { candidates.add(it) }
        root?.let { collectTexts(it, candidates) }

        val number = candidates.firstNotNullOfOrNull { lookupNumber(it) }

        // Cancel the WhatsApp call
        val endBtn = root?.let { findByDesc(it, listOf("end call", "leave", "cancel")) }
        if (endBtn != null) endBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        else performGlobalAction(GLOBAL_ACTION_BACK)

        if (number != null) {
            handler.postDelayed({
                val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { startActivity(i) } catch (_: SecurityException) { }
            }, 600)
        }
    }

    private fun collectTexts(n: AccessibilityNodeInfo, out: MutableSet<String>) {
        n.text?.toString()?.takeIf { it.isNotBlank() && it.length < 60 }?.let { out.add(it) }
        for (i in 0 until n.childCount) n.getChild(i)?.let { collectTexts(it, out) }
    }

    private fun findByDesc(n: AccessibilityNodeInfo, keys: List<String>): AccessibilityNodeInfo? {
        val d = n.contentDescription?.toString()?.lowercase()
        if (d != null && keys.any { d.contains(it) } && n.isClickable) return n
        for (i in 0 until n.childCount) {
            n.getChild(i)?.let { c -> findByDesc(c, keys)?.let { return it } }
        }
        return null
    }

    private fun lookupNumber(name: String): String? {
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
            arrayOf(name), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    override fun onInterrupt() {}
}
