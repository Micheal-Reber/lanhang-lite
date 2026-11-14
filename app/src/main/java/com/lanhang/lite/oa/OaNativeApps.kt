package com.lanhang.lite.oa

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
}
