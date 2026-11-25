// 荔枝直播 TV 壳的 content script
//
// 这里解决的是同一类问题：手机版页面给的是"手机版面"，不是电视画面。
//   · gdtv（m.gdtv.cn）  —— 顶部/底部固定导航条、App 下载条
//   · cctv（tv.cctv.com/live/{ch}/m/）—— 顶部 header、广告位、右侧分享条、下方节目单，
//     而且播放器宽度被页面写死成 min(视口, 750) CSS px，在 1280x720 的电视上只占 60%。
//
// 手段是王道：加一层 CSS + 少量几何计算，把播放器摆到铺满整屏，其余全部隐藏。
// 页面自己的 JS 不动（它那套 hls.js 管线虽然掉帧，但至少连续出画，比我们自己重写的强）。
//
// 诊断输出：GeckoView 69 的 console.log 不进 logcat，所以关键结论写进 document.title，
// 由 MainActivity 的 onTitleChange 落到 `LizhiGecko: title ...`。

(function () {
  'use strict';

  var TAG = 'LZEXT';
  var HOST = String(location.hostname || '');
  var IS_CCTV = /(^|\.)cctv\.com$/i.test(HOST);

  function vw() { return window.innerWidth || document.documentElement.clientWidth; }
  function vh() { return window.innerHeight || document.documentElement.clientHeight; }

  function findVideo() {
    var vs = document.querySelectorAll('video');
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect();
      if (r.width > 100 && r.height > 60) return vs[i];
    }
    return vs.length ? vs[0] : null;
  }

  function describe(el) {
    var r = el.getBoundingClientRect();
    var cs = getComputedStyle(el);
    return [
      el.tagName,
      '.' + String(el.className || '').replace(/\s+/g, '.').slice(0, 50),
      'pos=' + cs.position,
      'rect=' + Math.round(r.left) + ',' + Math.round(r.top) + ',' + Math.round(r.width) + 'x' + Math.round(r.height),
      'z=' + cs.zIndex
    ].join(' ');
  }

  // ---------------------------------------------------------------- 诊断

  function diagnose() {
    var v = findVideo();
    var lines = [];
    lines.push('viewport=' + vw() + 'x' + vh());
    lines.push(v ? ('video ' + describe(v) + ' paused=' + v.paused + ' rs=' + v.readyState +
      ' srcLen=' + String(v.currentSrc || v.src || '').length) : 'video NONE');

    var all = document.querySelectorAll('body *');
    var fixed = [];
    for (var i = 0; i < all.length; i++) {
      var e = all[i];
      var cs = getComputedStyle(e);
      if (cs.position !== 'fixed' && cs.position !== 'sticky') continue;
      if (cs.display === 'none') continue;
      var r = e.getBoundingClientRect();
      if (r.width < vw() * 0.4 || r.height < 16 || r.height > 400) continue;
      if (v && e.contains(v)) continue;
      fixed.push(e);
    }
    lines.push('fixedBars=' + fixed.length);
    for (var k = 0; k < fixed.length && k < 8; k++) {
      lines.push('  bar' + k + ' ' + describe(fixed[k]));
    }
    console.log(TAG + ' DIAG ' + lines.join(' || '));
    return fixed;
  }

  function hideBars(bars) {
    var hidden = [];
    for (var i = 0; i < bars.length; i++) {
      var el = bars[i];
      var r = el.getBoundingClientRect();
      var top = r.top < vh() * 0.35;
      var bottom = r.bottom > vh() * 0.65;
      if (!top && !bottom) continue;
      el.setAttribute('data-lz-hidden', '1');
      el.style.setProperty('display', 'none', 'important');
      hidden.push(describe(el));
    }
    if (hidden.length) console.log(TAG + ' HIDDEN ' + hidden.join(' || '));
  }

  // ---------------------------------------------------------------- CCTV 影院模式

  /*
   * 页面结构（tv.cctv.com/live/{ch}/m/，2026-11 实测）：
   *
   *   .page_wrap
   *     .headernew                      ← 顶部 CBox 导航栏，必须先移走
   *     .ind_video_xq18570
   *       .video_box#player             ← 播放器容器，宽度被 _videoW 写死成 min(视口,750)
   *       .ggcontainer#guanggao         ← 广告位
   *     .ind_share_xq18570              ← 分享/收藏条
   *     .md                             ← 节目单（日期 swiper + 节目列表）
   *
   * _videoW 是页面内联脚本在加载时算好的：
   *     var _videoW = (clientWidth > 750) ? 750 : clientWidth;
   * 我们的视口是 1280x720 CSS，所以播放器恒为 750x421 —— 这就是"没全屏"的真身。
   */
  var CINEMA_CSS = [
    'html, body { margin:0 !important; padding:0 !important; overflow:hidden !important;',
    '  background:#000 !important; height:100% !important; }',
    // 顶栏 / 广告 / 分享 / 节目单：整块不要
    '.headernew, .ggcontainer, #guanggao, .ind_share_xq18570, .md, .swiper-container,',
    '.copy, .video_btnBar, footer { display:none !important; }',
    '.page_wrap { padding:0 !important; margin:0 !important; }',
    // 播放器所在的整块区域钉成整屏，盖住一切
    '.ind_video_xq18570 { position:fixed !important; left:0 !important; top:0 !important;',
    '  width:100vw !important; height:100vh !important; margin:0 !important; padding:0 !important;',
    '  z-index:2147483000 !important; background:#000 !important; overflow:hidden !important; }',
    // 播放器容器跟着铺满。内联的 width/height 会被 !important 干掉
    '#player { position:absolute !important; left:0 !important; top:0 !important;',
    '  width:100vw !important; height:100vh !important; margin:0 !important;',
    '  transform-origin:0 0 !important; background:#000 !important; }',
    // 里面的 video 也拉到全屏，用 fill 保证 16:9 的流铺满 16:9 的屏（零变形、无黑边）
    '#player video { position:absolute !important; left:0 !important; top:0 !important;',
    '  width:100% !important; height:100% !important; object-fit:fill !important;',
    '  background:#000 !important; }'
  ].join('\n');

  var STYLE_ID = 'lz-cinema';

  function injectCinemaCss() {
    if (document.getElementById(STYLE_ID)) return;
    var s = document.createElement('style');
    s.id = STYLE_ID;
    s.textContent = CINEMA_CSS;
    (document.head || document.documentElement).appendChild(s);
  }

  /*
   * 摆正播放器。CSS 已经把 #player 拉成 100vw x 100vh，但播放器内部是 JS 计算布局的
   * （它按 750x421 算好了自己的 canvas/video/控件坐标），光改容器会让画面缩在左上角。
   *
   * 所以分两步，先试"直接铺"：
   *   ① CSS 把容器和 video 都设成 100vw/100vh —— 如果 video 已经是全屏，收工；
   *   ② 如果 video 还停在 750 宽（内部布局拒绝配合），改用几何缩放：
   *      对 #player 做 transform: scale(视口/750)，等比放大到铺满，
   *      多留 2‰ 余量防边缘 1px 黑缝。画面略软，但绝对铺满且不变形。
   */
  function fitPlayer() {
    var box = document.querySelector('#player');
    if (!box) return '';
    var v = findVideo();
    var target = v || box;
    var r = target.getBoundingClientRect();
    if (r.width < 40 || r.height < 30) return '';

    // 已经铺满（误差 2% 内）
    if (r.width >= vw() * 0.98 && r.height >= vh() * 0.98) {
      box.style.setProperty('transform', 'none', 'important');
      return 'full';
    }
    // 内部布局拒绝配合：整块等比放大
    var w = box.offsetWidth || r.width;
    var h = box.offsetHeight || r.height;
    if (w < 40 || h < 30) return '';
    var s = Math.max(vw() / w, vh() / h) * 1.002;
    box.style.setProperty('transform', 'scale(' + s.toFixed(4) + ')', 'important');
    return 'fit=scale' + s.toFixed(3) + ' box=' + w + 'x' + h;
  }

  // ---------------------------------------------------------------- 兜底起播

  // 页面没自动起播时，点一下明显的播放按钮
  function nudgePlay() {
    var v = findVideo();
    if (v && v.paused) {
      try {
        v.muted = true;
        var p = v.play();
        if (p && p.catch) p.catch(function () {});
        console.log(TAG + ' play() called');
      } catch (e) {
        console.log(TAG + ' play ex ' + e);
      }
    }
    if (!v) {
      var b = document.querySelector('.vjs-big-play-button,[class*=play-btn],[class*=playBtn],'
        + '[class*=player-play],.video-play,[class*=bigPlay]');
      if (b) {
        try { b.click(); console.log(TAG + ' click ' + b.className); } catch (e) {}
      }
    }
  }

  // ---------------------------------------------------------------- 主循环

  var tries = 0;
  var lastTitle = '';

  function tick() {
    tries++;
    injectCinemaCss();

    // 影院模式每轮都重新摆一遍：播放器是异步创建的，而且它自己会改布局。
    // 只在几何变了的时候写 title，避免刷 logcat。
    var info = '';
    if (IS_CCTV) {
      info = fitPlayer();
      if (info && info !== lastTitle) {
        lastTitle = info;
        try { document.title = 'CCTV|' + info; } catch (e) {}
      }
    }

    var bars = diagnose();
    if (bars.length) hideBars(bars);
    nudgePlay();

    // 前 20 秒每 800ms 一次（播放器创建、广告插入、resize 都在这段），之后退到 3 秒一次兜底
    if (tries >= 25) {
      clearInterval(timer);
      timer = setInterval(tick, 3000);
    }
  }

  var timer = setInterval(tick, 800);
})();
