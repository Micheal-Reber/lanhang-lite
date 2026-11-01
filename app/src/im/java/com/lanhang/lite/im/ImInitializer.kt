package com.lanhang.lite.im

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.lanhang.lite.core.ImBridge

/**
 * 把私信入口注入 [ImBridge]（2026-10-31）。
 *
 * 用 ContentProvider 而不是在 Application 里判断包名：ContentProvider 的 onCreate 在
 * **Application.onCreate 之前**同步执行，早于任何 Activity，入口从第一帧起就是可用的；
 * 而且这份声明只存在于 `src/im/AndroidManifest.xml`，lite 变体的包里根本没有这个组件。
 */
class ImInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        ImBridge.install(
            list = { context -> ImConversationsActivity.start(context) },
            chat = { context, peerId -> ImChatActivity.startWithPeer(context, peerId) },
            unread = { ImClient.conversations.sumOf { it.unread } },
            warmUp = { context -> ImClient.warmUp(context) },
        )
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
