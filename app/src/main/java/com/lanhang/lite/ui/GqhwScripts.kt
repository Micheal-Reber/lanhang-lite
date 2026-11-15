package com.lanhang.lite.ui

/**
 * 「赣青二课」站点（`gqhw.jxmediacenter.com`）专用的两段注入脚本。
 *
 * 2026-11-15 从分叉 `Nuclear-Potatoes/lanhang-lite-miuix` 第 77 轮移植。
 * 站点本身有两个绕不过去的问题，都只能在这侧兜住（改不了 H5）：
 *
 * 1. **扫码按钮点了没反应**：`window.CP2.showScanQr` 是青新闻 App 注入的桥，站点自己铺的
 *    stub 返回的 Promise 永不 resolve。→ [SCAN_BRIDGE_JS] 换掉这个方法，接到原生相机。
 * 2. **「我的活动 → 更多」整页白屏**：① 该页 `onLoad` 第一句就写 `this.$refs.draftsItem.handleSearchData("")`，
 *    而 `draftsItem` 是「草稿箱」tab 里的组件，默认 tab 下根本没渲染 → TypeError → 页面挂载中断；
 *    ② 即使挂上了，` .content-list → .uni-scroll-view` 整条祖先链高度全是 0，而滚动容器带
 *    `overflow:hidden`，内容被整块裁掉，看起来也是白屏；
 *    ③ 从活动详情**返回列表时又白屏**（只有点刷新才恢复）：uni-app 的页面是复用的，
 *    返回走 `onShow` 而不是 `mounted`，而缓存页面重新摆上来时高度又塌回 0。
 *    → [COMPAT_JS] 打桩 + 补高度，并把补高度分别挂在 `mounted` / `onShow` / 700ms 轮询三个触发点上。
 *
 * 两段脚本都带幂等守卫（`window.__lh*`），同一次页面里重复注入无副作用。
 */
internal object GqhwScripts {

    /** 接管 `CP2.showScanQr`：调原生扫码，扫到后 resolve `{code:1,data:{qrCode}}` */
    val SCAN_BRIDGE_JS = """
        (function () {
            if (window.__lhScanPatched) return;
            // CP2 是站点/宿主 App 挂的桥对象，onPageFinished 时可能还没就绪 ——
            // uni-app 的 SPA 内跳转不会再触发 onPageFinished，所以这里自己等一会儿
            //（最多 10s），等不到就放弃，绝不影响其它功能。
            var tries = 0;
            function patch() {
                if (window.__lhScanPatched) return;
                if (!window.CP2) {
                    if (tries++ < 40) { setTimeout(patch, 250); return; }
                    console.log('LhScan: CP2 not ready');
                    return;
                }
                window.__lhScanPatched = true;
                window.CP2.showScanQr = function () {
                    console.log('LhScan: intercept showScanQr');
                    return new Promise(function (resolve) {
                        window.__lhScanResolve = resolve;
                        try { LhScan.scan(); } catch (e) { console.log('LhScan: call failed ' + e); }
                    });
                };
                console.log('LhScan: bridge ready');
            }
            patch();
        })()
    """.trimIndent()

    /** 站点自身的两个 H5 bug：`draftsItem` 空引用 + 「我的活动」列表祖先链高度 0 */
    val COMPAT_JS = """
        (function () {
            if (window.__lhCompatDone) return;
            window.__lhCompatDone = true;
            function getVue() {
                if (window.Vue && window.Vue.config) return window.Vue;
                try {
                    var app = window.getApp && window.getApp();
                    var vm = app && app['${'$'}vm'];
                    var C = vm && vm.constructor;
                    if (C && C.config) return C;
                } catch (e) { }
                return null;
            }
            var tries = 0;
            function patch() {
                var V = getVue();
                if (!V || !V.config) { if (tries++ < 40) setTimeout(patch, 200); return; }
                // ① onLoad / onShow 里一条与渲染无关的异常会让 uni-app 把整页标记为失败，
                //    这里只记日志再交给原处理器，避免页面被一条异常打死。
                var prev = V.config.errorHandler;
                V.config.errorHandler = function (err, vm, info) {
                    try { console.log('LhCompat swallow: ' + err + ' | ' + info); } catch (e) { }
                    if (prev) { try { prev(err, vm, info); } catch (e) { } }
                };
                if (!V.__lhDraftsMixin) {
                    V.__lhDraftsMixin = true;
                    V.mixin({
                        beforeCreate: function () {
                            var self = this;
                            try {
                                if (!self['${'$'}refs']) return;
                                // 桩里的 handleSearchData 不能是空实现 —— 它正是首屏加载的触发器，
                                // 而 beforeCreate 时 methods 还没初始化，所以延后 300ms 转发给实例自己。
                                var stub = {
                                    handleSearchData: function (v) {
                                        setTimeout(function () {
                                            try {
                                                if (typeof self.handleSearchData === 'function') self.handleSearchData(v || '');
                                            } catch (e) { console.log('LhCompat load failed ' + e); }
                                        }, 300);
                                    },
                                    handleSearchClearData: function () { },
                                    handleInputData: function () { }
                                };
                                Object.defineProperty(self['${'$'}refs'], 'draftsItem', {
                                    configurable: true,
                                    get: function () { return self.__lhDrafts || stub; },
                                    set: function (v) { self.__lhDrafts = v; }
                                });
                            } catch (e) { }
                        }
                    });
                }
                console.log('LhCompat ready');
            }
            patch();

            // ② 「我的活动」列表不可见：DOM 里 8 条记录都在（总高 1300+ px），
            //    但从 .content-list 往上整条祖先链高度全是 0，而 .uni-scroll-view 带
            //    overflow:hidden，内容被整块裁掉 —— 看上去就是白屏。
            //    这条链本该靠 flex 撑开，页面在这个 WebView 里没撑起来，
            //    所以高度塌陷时按视口给滚动容器一个确定高度，让子层重新有尺寸。
            function lhFixList() {
                try {
                    var first = document.querySelector('.list-bg');
                    if (!first) return;
                    var el = first.parentElement;
                    var guard = 0;
                    while (el && guard < 12) {
                        var cs = getComputedStyle(el);
                        if (String(cs.overflow).indexOf('hidden') >= 0) {
                            var box = el.getBoundingClientRect();
                            if (Math.round(box.height) <= 40) {
                                var want = Math.max(240, window.innerHeight - Math.round(box.top) - 6);
                                el.style.height = want + 'px';
                            }
                            break;
                        }
                        el = el.parentElement;
                        guard++;
                    }
                } catch (e) { }
            }
            var lhApp = window.getApp && window.getApp();
            var lhVue = lhApp && lhApp['${'$'}vm'] && lhApp['${'$'}vm'].constructor;
            if (lhVue) {
                lhVue.mixin({
                    mounted: function () {
                        if (!this.seachList && !this.listData) return;
                        // 列表是异步渲染的，几个时间点各补一次
                        setTimeout(lhFixList, 1200);
                        setTimeout(lhFixList, 2600);
                        setTimeout(lhFixList, 5000);
                    },
                    // ③ 从活动详情返回列表页时这个页面组件是**复用**的，mounted 不会再跑一次；
                    //    而 uni-app 把缓存页面重新摆上来时那条 flex 高度链又塌回 0，于是又白屏
                    //    （用户点右下角刷新之所以能恢复，正是因为刷新重建了 DOM 并重新触发 mounted）。
                    //    所以在这里给页面的 onShow 包一层，返回时立刻补修。
                    created: function () {
                        try {
                            var opts = this['${'$'}options'] || {};
                            if (opts.__lhShowWrapped) return;
                            if (typeof opts.onShow !== 'function') return;
                            opts.__lhShowWrapped = true;
                            var orig = opts.onShow;
                            opts.onShow = function () {
                                var r = orig.apply(this, arguments);
                                // 三次延时都是必要的：120ms 抓「DOM 已重建」、
                                // 500ms 抓「数据回来重排完」、1500ms 收尾兜住慢网。
                                setTimeout(lhFixList, 120);
                                setTimeout(lhFixList, 500);
                                setTimeout(lhFixList, 1500);
                                return r;
                            };
                        } catch (e) { }
                    }
                });
            }
            // ④ 还有些折返路径压根不走 onShow（例如从别的 tab 切回来、栈被重置），
            //    低频轮询兜底。lhFixList 第一句就是找 .list-bg，找不到立即 return，
            //    开销可以忽略；页面在后台时（document.hidden）直接跳过，不占 CPU。
            if (!window.__lhFixTimer) {
                window.__lhFixTimer = setInterval(function () {
                    if (document.hidden) return;
                    lhFixList();
                }, 700);
            }
        })()
    """.trimIndent()
}
