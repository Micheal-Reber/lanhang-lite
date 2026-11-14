package com.lanhang.lite.im

import android.os.Parcel
import android.os.Parcelable
import io.rong.imlib.MessageTag
import io.rong.imlib.model.MessageContent
import org.json.JSONObject

/**
 * 好友事件通知消息 `RCE:ContactNtfy`（IM 版专属）。
 *
 * 这条消息是**触发器不是数据源**：官方 `FriendTask` 收到后也是回头重拉
 * `GET /friends/request_list`，HTTP 才是权威（见 `docs/im-friend-requests-research.md` §4）。
 * 我们用它只为两件事：① 判断这条事件该不该提醒；② 拿到「谁」发起的，好把姓名写进通知。
 *
 * 🔴 **必须在 `RongIMClient.init` 之前 `registerMessageType`**：不注册的话 SDK 只会
 * 解出 [io.rong.imlib.model.UnknownMessage]，收消息回调里就分不清「好友事件」和
 * 「普通私信」，结果是好友事件被当成私信弹一条点进去空白的「新私信」（2026-11-12 用户反馈）。
 *
 * 字段与官方 `cn.rongcloud.rce.lib.message.FriendNotificationMessage` 一一对齐
 * （JSON：`{actionType:int, operator:{userId,name}, target:{userId,name}, data:string}`），
 * 但**不 import 官方类**——那是 RCE SDK 的类，我们只有 IMLib 的 aar。
 *
 * 构造器与 Parcelable 不能省：`NativeClient.registerMessageType` 用
 * `getDeclaredConstructor(byte[].class)` 反射解码，`Message.writeToParcel` 走
 * `Parcel.writeParcelable` 需要 `CREATOR`（缺了就跨进程回不来，`:ipc` 正是另一个进程）。
 */
@MessageTag(flag = 0, value = ContactNtfyMessage.OBJECT_NAME)
class ContactNtfyMessage : MessageContent {

    /** 1=申请加好友 2=同意 3=拒绝 4=删除；0 = JSON 里没有/解析不出来 */
    var actionType: Int = ACTION_NONE

    /** 发起操作的人 */
    var operatorUserId: String? = null
    var operatorName: String? = null

    /** 被操作的人（申请加我时 target 就是我） */
    var targetUserId: String? = null
    var targetName: String? = null

    var data: String? = null

    /** 解码前的原始字节：`encode()` 原样吐回去，解出来再写出不丢字段 */
    private var raw: ByteArray = ByteArray(0)

    /** 供反射（`getDeclaredConstructor(byte[].class)`）与本地构造使用 */
    @Suppress("unused")
    constructor() : super()

    constructor(bytes: ByteArray?) : super() {
        if (bytes == null || bytes.isEmpty()) return
        raw = bytes
        val json = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            // 半截 JSON / 非 JSON 都算「读不出」：actionType 留 0，调用方按未知事件静默处理
            return
        }
        actionType = json.optInt(KEY_ACTION, ACTION_NONE)
        json.optJSONObject(KEY_OPERATOR)?.let { op ->
            operatorUserId = op.optString(KEY_USER_ID).ifBlank { null }
            operatorName = op.optString(KEY_NAME).ifBlank { null }
        }
        json.optJSONObject(KEY_TARGET)?.let { t ->
            targetUserId = t.optString(KEY_USER_ID).ifBlank { null }
            targetName = t.optString(KEY_NAME).ifBlank { null }
        }
        data = json.optString(KEY_DATA).ifBlank { null }
    }

    private constructor(parcel: Parcel) : this() {
        actionType = parcel.readInt()
        operatorUserId = parcel.readString()
        operatorName = parcel.readString()
        targetUserId = parcel.readString()
        targetName = parcel.readString()
        data = parcel.readString()
    }

    /** 我们从不发送这条消息，encode 只服务「解出来再写出去」的回环 */
    override fun encode(): ByteArray = raw

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(actionType)
        dest.writeString(operatorUserId)
        dest.writeString(operatorName)
        dest.writeString(targetUserId)
        dest.writeString(targetName)
        dest.writeString(data)
    }

    companion object {
        /** 融云 objectName（`@MessageTag.value`），同时也是识别这条消息的判据 */
        const val OBJECT_NAME = "RCE:ContactNtfy"

        const val ACTION_NONE = 0
        const val ACTION_ADD = 1
        const val ACTION_ACCEPT = 2
        const val ACTION_REJECT = 3
        const val ACTION_DELETE = 4

        private const val KEY_ACTION = "actionType"
        private const val KEY_OPERATOR = "operator"
        private const val KEY_TARGET = "target"
        private const val KEY_DATA = "data"

        /** 与官方 `RongLibConst.KEY_USERID` / `UserData.NAME_KEY` 一致 */
        private const val KEY_USER_ID = "userId"
        private const val KEY_NAME = "name"

        @JvmField
        val CREATOR: Parcelable.Creator<ContactNtfyMessage> =
            object : Parcelable.Creator<ContactNtfyMessage> {
                override fun createFromParcel(parcel: Parcel): ContactNtfyMessage =
                    ContactNtfyMessage(parcel)

                override fun newArray(size: Int): Array<ContactNtfyMessage?> = arrayOfNulls(size)
            }
    }
}
