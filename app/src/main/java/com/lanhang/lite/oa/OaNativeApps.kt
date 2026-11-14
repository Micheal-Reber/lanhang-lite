package com.lanhang.lite.oa

import android.content.Context
import androidx.annotation.DrawableRes
import com.lanhang.lite.R

/**
 * 服务端 `/apps/all` 里没有、由 App 自己在「工作」页补上的入口。
 *
 * 常量和宫格代码放在 app 模块（**不要放进 `engine/`**：那是闭源网络层，公开仓 clone 下来没有它）。
 */
object OaNativeApps {

    /**
     * 「赣青二课」在工作页里的条目 id。
     *
     * 2026-11-14 用户要求「把赣青二课接入工作功能」。逆向青新闻 3.3.x 后确认它**不在客户端里**
     * （全包搜「赣青二课 / 二课 / 第二课堂 / 素质拓展」零命中），是服务端菜单下发的外部 H5，
     * 真实地址 `https://gqhw.jxmediacenter.com/#/`（页面标题就叫「第二课堂」，
     * Vue + uni-app 打的单页应用）。
     *
     * 所以这里不做原生页：那套系统有上百条前端路由（活动 / 实践 / 志愿 / 成绩单 / 申报 /
     * 答题 / 竞赛 / 组织 / 校友圈…）且全程要登录态，WebView 承载就是完整接入。
     * 这个 id 用来区分两条打开路径：它**不走 OA 网关、不取 appToken**，直接开校外站点。
     */
    const val GQHW_APP_ID = "native:gqhw"

    /**
     * 赣青二课入口地址。
     *
     * `#/` 结尾是必须的：Vue 的 hash 路由，去掉就是一张空白页。
     */
    const val GQHW_URL = "https://gqhw.jxmediacenter.com/#/"

    /**
     * 「校园网」（深澜一键认证）与「图书馆座位」在工作页里的条目 id。
     *
     * 2026-11-14 用户要求把它们从设置「工具」页挪进「工作」Tab：两者都是**原生 Activity**，
     * 不走 WebView 也不取 appToken，点击时由 [com.lanhang.lite.oa.OaHallFragment] 按 id 分发。
     */
    const val CAMPUS_NET_APP_ID = "native:campus_net"
    const val LIBRARY_SEAT_APP_ID = "native:library_seat"

    /**
     * 三个本地入口的宫格条目（顺序即默认展示顺序）。
     *
     * 名称/说明复用设置页与服务端同口径的字符串；图标走 [localIcon]，
     * 不进 `logoUrl` —— 本地矢量图让 Coil 去联网缓存没有意义。
     */
    fun all(ctx: Context): List<OaApp> = listOf(
        OaApp(
            id = CAMPUS_NET_APP_ID,
            name = ctx.getString(R.string.settings_campus_net),
            description = ctx.getString(R.string.settings_campus_net_subtitle),
            orderNum = 0
        ),
        OaApp(
            id = LIBRARY_SEAT_APP_ID,
            name = ctx.getString(R.string.settings_library_seat),
            description = ctx.getString(R.string.settings_library_seat_subtitle),
            orderNum = 1
        ),
        OaApp(
            id = GQHW_APP_ID,
            name = ctx.getString(R.string.gqhw_name),
            description = ctx.getString(R.string.gqhw_desc),
            homePageUrl = GQHW_URL,
            orderNum = 2
        )
    )

    /**
     * 本地入口的图标（服务端条目返回 null，走 Coil 按 logoUrl 加载）。
     * 全是项目里已有的 Material 矢量图，不新增资源：
     * 校园网 = wifi，图书馆座位 = 椅子，赣青二课 = 学士帽（第二课堂 → 学业场景）。
     */
    @DrawableRes
    fun localIcon(id: String?): Int? = when (id) {
        CAMPUS_NET_APP_ID -> R.drawable.ic_wifi
        LIBRARY_SEAT_APP_ID -> R.drawable.ic_chair
        GQHW_APP_ID -> R.drawable.ic_school
        else -> null
    }
}
