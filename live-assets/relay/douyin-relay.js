/*!
 * 大肥鱼 · 抖音直播间弹幕中继（控制台注入版）
 *
 * 用法：
 *   1. 电脑浏览器打开自己的直播间 https://live.douyin.com/<房间号>
 *   2. F12 打开开发者工具 → Console
 *   3. 把本文件全部内容粘进去回车
 *
 * 原理：监听聊天区 DOM 变动，把「进房 / 关注 / 粉丝团 / 送礼 / 弹幕」解析成
 * {type, username, content} POST 到本机后端 POST /danmu，不需要任何签名服务。
 *
 * 注意：中继和 SDK 采集同时开着会把同一条弹幕处理两次，
 *      用中继时把 collector.mode 设成 off（或启动脚本里不加直播间）。
 */
(() => {
  // 后端地址：和 /stage 页面同源；OBS 与本机浏览器都在同一台机器时不用改
  const RELAY_URL = 'http://127.0.0.1:8080/danmu';
  const CHAT_SELECTORS = [
    '[class*="chatroom___list"]',
    '[class*="webcast-chatroom"]',
    '[class*="ChatRoom"]',
  ];

  console.log('%c[大肥鱼] 弹幕中继启动，等待聊天区…', 'color:#38bdf8;font-weight:bold');

  // 同一条消息 MutationObserver 可能连发多次，用「文本 + 时间窗」去重
  const recent = new Map();
  function seen(key) {
    const now = Date.now();
    for (const [k, t] of recent) {
      if (now - t > 3000) recent.delete(k);
    }
    if (recent.has(key)) return true;
    recent.set(key, now);
    return false;
  }

  function send(type, username, content) {
    const key = type + '\u0001' + username + '\u0001' + content;
    if (seen(key)) return;
    fetch(RELAY_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ type, username, content }),
    })
      .then((res) => res.json())
      .then((data) => {
        console.log(`%c[已中继] ${type} ${username}: ${content}`, 'color:#10b981');
        return data;
      })
      .catch((err) => console.warn('[中继失败] 后端没起来？', err.message));
  }

  // 从一段聊天行文本里判断事件类型
  function parseLine(raw) {
    const text = raw.replace(/\s+/g, ' ').trim();
    if (!text || text.length > 200) return null;

    let m;
    if ((m = text.match(/^(.{1,30}?)(?:来了|进入了直播间)$/))) {
      return ['join', m[1].trim(), '进入了直播间'];
    }
    if ((m = text.match(/^(.{1,30}?)关注了(?:主播)?$/))) {
      return ['follow', m[1].trim(), '关注了主播'];
    }
    if ((m = text.match(/^(.{1,30}?)加入了?粉丝团$/))) {
      return ['fansclub', m[1].trim(), '加入粉丝团'];
    }
    if ((m = text.match(/^(.{1,30}?)送出(?:了)?\s*(.+)$/))) {
      return ['gift', m[1].trim(), '送出了 ' + m[2].trim()];
    }
    // 普通弹幕：昵称：内容
    const idx = text.search(/[：:]/);
    if (idx > 0 && idx < 40) {
      const user = text.slice(0, idx).trim();
      const content = text.slice(idx + 1).trim();
      if (user && content) return ['chat', user, content];
    }
    return null;
  }

  const observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      for (const node of mutation.addedNodes) {
        if (node.nodeType !== 1) continue;
        const parsed = parseLine(node.innerText || '');
        if (parsed) send(parsed[0], parsed[1], parsed[2]);
      }
    }
  });

  function attach() {
    const container = CHAT_SELECTORS
      .map((sel) => document.querySelector(sel))
      .find(Boolean) || document.body;
    observer.observe(container, { childList: true, subtree: true });
    console.log('%c[大肥鱼] 已锁定聊天区，开始中继', 'color:#f59e0b;font-weight:bold');
  }

  attach();
  // 抖音是 SPA，路由切换后容器会重建，隔一段时间重新挂一次
  setInterval(() => {
    try {
      observer.disconnect();
      attach();
    } catch (e) {
      /* 页面切走了就等下一轮 */
    }
  }, 15000);
})();
