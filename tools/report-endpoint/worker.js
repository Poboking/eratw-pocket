/**
 * eratw 报错收集端点（方案③：Cloudflare Workers + KV，2026-10-27）。
 *
 * 方案①（本地报告包）落盘的报告全文**原样** POST 到这里，写进 KV；开发者用
 * 浏览器拉列表与原文查看，不需要任何后端运维。
 *
 * ## 部署 runbook（部署者账号自备，全程免费档够用）
 *
 * 1. `npm install -g wrangler && wrangler login`
 * 2. `wrangler kv namespace create REPORTS` → 把输出里的 `id` 填进 wrangler.toml
 * 3. `wrangler secret put REPORT_TOKEN` → 输入一个自选随机串（与出包参数同值）
 * 4. `wrangler deploy` → 得到 `https://eratw-report-endpoint.<你的子域>.workers.dev`
 * 5. 出包（app 侧启用上报）：
 *    `gradlew assembleRelease -PeratwReportEndpoint=https://<第4步的域名>/report -PeratwReportToken=<第3步的值>`
 *
 * ## 查看报错（两个只读面，都要令牌）
 *
 * - `GET /reports?token=<值>`：最近 100 份的 JSON 清单（键名 = 日期/毫秒-文件名）；
 * - `GET /report/<键名>?token=<值>`：单份报告原文（text/plain）。
 *
 * ## 安全与隐私口径
 *
 * - 写入必须带 `X-Eratw-Token`（与 wrangler secret 比对），挡无凭据垃圾；
 *   这是共享口令不是强鉴权，别把它当安全边界；
 * - 读取同样要令牌：报错里可能有玩家屏幕上的口上文段，不公开；
 * - 报告内容是 app 侧已脱敏的方案①产物（无变量取值、无存档数据）；端点侧只做
 *   形状校验（必须含报告头），不做内容加工；
 * - **自动上报的披露责任在部署者**：出包带上 -PeratwReportEndpoint 即等于启用
 *   自动上报，请在发布说明里告知玩家（分发给固定群聊的自用包可从简）。
 */

const MAX_BODY_BYTES = 512 * 1024;
const REPORT_MARKER = "eraTW 安卓移植版 错误报告";
const LIST_LIMIT = 100;

function json(obj, status) {
  return new Response(JSON.stringify(obj, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function readToken(request, url) {
  return request.headers.get("X-Eratw-Token") || url.searchParams.get("token") || "";
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (!env.REPORT_TOKEN) {
      return json({ error: "REPORT_TOKEN 未配置：wrangler secret put REPORT_TOKEN" }, 500);
    }
    if (!env.REPORTS) {
      return json({ error: "REPORTS KV 未绑定：见 wrangler.toml" }, 500);
    }

    // ---- 写入面 ----------------------------------------------------------
    if (request.method === "POST" && url.pathname === "/report") {
      if (readToken(request, url) !== env.REPORT_TOKEN) return json({ error: "token 不符" }, 403);
      const body = await request.text();
      if (!body) return json({ error: "空 body" }, 400);
      if (body.length > MAX_BODY_BYTES) return json({ error: "body 超过 512KB" }, 413);
      if (!body.includes(REPORT_MARKER)) return json({ error: "不是 eratw 错误报告" }, 400);
      // KV 键 = eratw/<ISO日期>/<毫秒>-<净化后的报告名>：字典序即时间序，list 直接翻最新。
      const rawName = request.headers.get("X-Eratw-Report-Name") || "";
      const safeName = rawName.replace(/[^\w.-]/g, "").slice(0, 80) || `report-${Date.now()}.txt`;
      const key = `eratw/${new Date().toISOString().slice(0, 10)}/${Date.now()}-${safeName}`;
      await env.REPORTS.put(key, body);
      return new Response(null, { status: 204 });
    }

    // ---- 查看面（GET，都要令牌） ------------------------------------------
    if (request.method === "GET") {
      if (readToken(request, url) !== env.REPORT_TOKEN) return json({ error: "token 不符" }, 403);

      if (url.pathname === "/reports") {
        // KV list 按字典序升序；为取「最新 100」，有游标就继续翻（上限 10 页，足够免费档用量）。
        const names = [];
        let cursor;
        for (let page = 0; page < 10; page++) {
          const list = await env.REPORTS.list({ prefix: "eratw/", cursor });
          for (const k of list.keys) names.push(k.name);
          if (list.list_complete) break;
          cursor = list.cursor;
        }
        const latest = names.slice(-LIST_LIMIT).reverse();
        return json({ count: latest.length, reports: latest });
      }

      if (url.pathname.startsWith("/report/")) {
        const key = decodeURIComponent(url.pathname.slice("/report/".length));
        const text = await env.REPORTS.get(key);
        if (text === null) return json({ error: "报告不存在" }, 404);
        return new Response(text, {
          status: 200,
          headers: {
            "content-type": "text/plain; charset=utf-8",
            "content-disposition": `attachment; filename="${key.split("/").pop()}"`,
          },
        });
      }

      return json({
        ok: true,
        usage: {
          上报: "POST /report（头 X-Eratw-Token，body=报告全文）",
          清单: "GET /reports?token=<值>",
          原文: "GET /report/<键名>?token=<值>",
        },
      });
    }

    return json({ error: "not found" }, 404);
  },
};
