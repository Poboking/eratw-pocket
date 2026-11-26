/**
 * eratw 报错收集端点 + 更新产物分发（方案③：Cloudflare Workers + KV + R2）。
 *
 * ## 四个面
 *
 * 1. **上报面**（POST /report、GET /reports、GET /report/<键>）：需要令牌，用 KV 存报告；
 *    同一个 POST /report 还接收带 `kind: "update-funnel"` 的更新漏斗批（按事件名+UTC 日
 *    聚合计数进同一个 KV，只读出口 GET /funnel-stats）与 `kind: "player-presence"` 的
 *    匿名玩家登记（按 id 去重进 **R2**，不占 KV 免费额度），与纯文本错误报告按 kind 分流；
 * 2. **判读面**（GET /stats、GET /sample/<指纹>、POST /reindex）：同样要令牌，只做聚合与历史回填；
 * 3. **更新面**（GET /updates/<file>）：公开只读，从 R2 桶 `eratw-updates` 取产物（APK/zip/manifest），
 *    支持 Range 断点续传，CORS 全开。
 * 4. **统计面**（GET /players、GET /players/raw）：公开只读，**只回总数**，一个匿名编号
 *    都不出网。**主要读面是 GitHub Actions**：公开仓的 workflow 每 30 分钟拉一次 /players/raw，
 *    把数字写进 badges/*.json，shields.io 从 raw.githubusercontent.com 读静态文件，
 *    不直接打本 Worker；/players 本身保留成可选的直连 endpoint 徽章。玩家键存在
 *    `env.UPDATES` 这个 R2 桶的 `player/` 与 `active/` 前缀下，**整个统计面一次 KV 都不碰**：
 *    KV 免费档每天 1000 次写 / 1000 次 list 全留给错误报告与漏斗遥测。
 *
 * 部署 runbook（含 KV id 与 secret 的填法）见同目录 wrangler.toml 注释；
 * 本地对拍跑同目录 worker.test.mjs（node，不部署）。
 *
 * ## 安全与隐私口径
 *
 * - 写入必须带 `X-Eratw-Token`（与 wrangler secret 比对），挡无凭据垃圾；
 *   这是共享口令不是强鉴权，别把它当安全边界；
 * - 读取同样要令牌：报错里可能有玩家屏幕上的口上文段，不公开；
 * - 更新面公开（清单暴露版本号与文件名是分发本来就是公开的，不新增隐私面）；
 * - 统计面公开但**只有聚合值**：`player/<id>` 里的 id 是 App 本地 SecureRandom 生成的
 *   32 字节随机串，与设备号 / Android ID / 账号 / 存档一概无关；服务端不存 IP、UA、
 *   客户端时间、版本号，累积键的值只有一个首次见到的 UTC 日。`GET /players` 回包里
 *   连一个 id 都没有，shields.io 与 GitHub 的图片代理只见得到 label 与一个数字；
 * - 写入侧（含玩家登记）仍走要令牌的 POST /report，不给匿名写开新口子；
 * - R2 桶 `eratw-updates`：更新产物只读、不要令牌，客户端用 Range 续传大文件（APK ~743 MiB）；
 *   桶内另有 `player/` 与 `active/` 两个统计内部前缀，由写入面维护，更新面拒绝把它们
 *   当产物下载（见 handleUpdates）。R2 免费档 Class A 写 100 万次/月、Class B 读
 *   1000 万次/月，与 KV 的 1000 写/天、1000 list/天 是两个独立池子 —— 玩家每天
 *   一次心跳不会挤占任何 KV 额度，反过来 KV 挂掉也不影响统计与更新两面。
 *
 * ## 去重口径（2026-11-04 inbox 波；判读侧的全部账都记在这段）
 *
 * 一次上传最多写三类键，正文最多落一份：
 *
 * - `occ/<指纹>/<上报毫秒>-<类型码>-<退化位>-<版本位>-<机型码>-<签名码>`：每次上传一条，**只增不改**。
 *   它是计数真相 —— 读改写在两个 Worker 实例并发时会丢增量，不可变键不会；聚合维度全写进键名，
 *   所以 GET /stats 一次 list 就出表，一个正文都不拉。
 * - `idx/<指纹>`：这条内容的首份正文落在哪个键（签名码、类型、版本、机型、退化位、条数）。
 *   读它才决定「要不要再存一遍正文」。
 * - `lbl/<签名码>`：签名码对应的原文（归一后的信息 + 脚本位置），一份签名只写一次，出表时贴标签用。
 *
 * 指纹吃什么：只吃**正文**。`X-Eratw-Report-Name` 那套秒级避让文件名、上报毫秒、令牌都不进哈希——
 * 同一份文件重发时这些必然变，变了也不代表换了件事。归一掉的正文字段只有一个：`报告时间：` 整行
 * （core-vm/src/main/kotlin/com/eratw/port/core/diag/ErrorReport.kt:122 与
 * app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:563 都在生成时写这一行；
 * 最小退化报告在连格式化时间都失败时退化成裸毫秒，ErrorReportCollector.kt:552，同一行规则一起归一）。
 * 除此之外只做折行（CRLF 折 LF）与行尾空白清理，其余逐字节进哈希。
 *
 * 误判方向（宁可留双份的具体边界）：归一掉时间后，「正文只差时间」的两份会被并成一条。这只在正文
 * 已经有能区分两次事故的东西时才允许 —— `信息：` / `脚本位置：` / 至少一条 `t=<数字>ms` 轨迹行，
 * 三者有其一才算「有身份」。没身份的（连信息都没写出来的最小退化报告）把 `报告时间：` 原值**加回**
 * 哈希，于是它只在整份正文含时间完全相同时才合并：2026-11-03 那三份「内存见底、正文几乎一字不差」
 * 的退化报告因此各留一条，不会被并成「一次事故」。
 *
 * 命中重复时怎么办：**不拒收**，照样回 204（App 侧 2xx 即算发成功并把文件名落进 `.uploaded` 索引，
 * app/src/main/kotlin/com/eratw/port/android/ErrorReportUploader.kt:169-170；回 4xx 会让它无限补发），
 * 正文不再落第二份，只多一条 occ 计数并把 idx 的条数尽力 +1。代价写清楚：
 * 一、/reports 的条数从此**不等于**上报数，真实上报数读 /stats 的 occurrences；
 * 二、重复上传只留首份正文 —— 同机同签名反复上传的那几份不再各占一条 KV；
 * 三、idx 是读改写，并发最坏丢一次增量（occ 不受影响），两个实例同时首传同一内容时会多存一份正文；
 * 四、正文写入真失败（KV 抛）就回 5xx，让 App 侧留文件下次补发 —— 计数可以不准，报告不许丢。
 */

const MAX_BODY_BYTES = 512 * 1024;
const REPORT_MARKER = "eraTW 安卓移植版 错误报告";
// 清单返回的最近报告条数。2026-11-04 从 100 提到 1000：当天 KV 实测共 617 份
// （最早 10-27），500 仍会漏最旧的 117 份；1000 覆盖全量并留 ~380 份余量。
// 分桶工具只看得见 LIST_LIMIT 以内的条目，「有可能漏」的正是这一段。
const LIST_LIMIT = 1000;

// 更新漏斗：POST /report 里带这个 kind 的 JSON 批按「事件名 + UTC 日」聚合计数，
// 与纯文本错误报告共用同一个端点和令牌。事件名白名单与 core 的 UpdateTelemetry 一致，
// 只数这十二个，未知事件与坏输入整条跳过，不污染聚合表。
// install_result（第 6 拍，安装终态）同样只数事件名：outcome / status 两维**不进键名**，
// 也不进任何别的键 —— 漏斗键的构成恒为 `funnel/<UTC 日>/<事件名>` 两格，聚合侧拿不到
// 任何能定位到设备的读数（隐私三条与 core 的 UpdateTelemetry 类头同口径）。
// assets_applied（第 7 拍，数据包应用终态，U10）是唯一例外：它的 kind（应用走的是
// 块级差量 / 文件级差量 / 全量哪一档）是聚合表要分桶的维度，落成
// `funnel/<UTC 日>/assets_applied/<kind>` 三格键 —— 档位是词表枚举不是自由文本，
// 不构成新的隐私面；失败档 kind 可缺（归档连打开都打不开时档位没探出来），落无后缀桶。
const FUNNEL_KIND = "update-funnel";
const FUNNEL_PREFIX = "funnel/";
const FUNNEL_EVENT_NAMES = [
  "check_started", "check_ok", "check_failed",
  "offer_shown", "offer_action",
  "download_started", "download_finished", "download_cancelled", "download_failed",
  "install_requested", "install_result", "assets_applied",
];
// 第 7 拍的维词表，与 core UpdateTelemetry.validate 同口径：
// - kind 三值与 core 的 DataArtifactKind 枚举名逐字对齐（apk/assets/download 那些别的词表进不来）；
// - outcome 只收两档：应用跑在冷启解包线程上，没有可取消的形态，「版本不需要」根本不应用；
// - failure 只在 failed 档必带，且是短标签（字母数字加 `._-`，上限 40 与 core 的
//   MAX_DIM_CHARS 一致）—— problem 原话可能带路径与文件名，灌进来整条拒。
const FUNNEL_ASSETS_KINDS = new Set(["block_delta", "file_delta", "full"]);
const FUNNEL_APPLY_OUTCOMES = new Set(["success", "failed"]);
const FUNNEL_FAILURE_TAG_RE = /^[A-Za-z0-9._-]{1,40}$/;

// assets_applied 的一条是否收得下（镜像 core 对这一事件的裁定，第二道闸）：
// 维缺省按 undefined 处理，null 与 undefined 同当「没带」。
function funnelAssetsAppliedOk(ev) {
  const has = (v) => v !== undefined && v !== null;
  if (typeof ev.outcome !== "string" || !FUNNEL_APPLY_OUTCOMES.has(ev.outcome)) return false;
  if (has(ev.kind) && (typeof ev.kind !== "string" || !FUNNEL_ASSETS_KINDS.has(ev.kind))) return false;
  if (ev.outcome === "success") {
    // 成功档必带合法档位（NONE 档没有应用拍）；且不许蹭 failure 维。
    if (!has(ev.kind)) return false;
    return !has(ev.failure);
  }
  // 失败档：failure 必带且是短标签；kind 仍可带（词表内）。
  return typeof ev.failure === "string" && FUNNEL_FAILURE_TAG_RE.test(ev.failure);
}
// 匿名玩家数（2026-11-08 波）：POST /report 里带这个 kind 的 JSON 是一次登记/心跳。
// body 只认 {"kind":"player-presence","id":"<64 位小写十六进制>"}：id 是 App 首次启动
// 用 SecureRandom 生成的 32 字节随机串，与设备号 / Android ID / 账号 / 存档一概无关。
// 服务端只拿它当去重键，不存 IP、UA、客户端时间、版本号。
const PRESENCE_KIND = "player-presence";
// 统计内部前缀：与更新产物（manifest.txt / apk / zip）同桶但命名空间隔开，
// handleUpdates 明确拒绝把这两个前缀当产物下载。
const PLAYER_PREFIX = "player/";
const ACTIVE_PREFIX = "active/";
const PLAYER_ID_RE = /^[0-9a-f]{64}$/;
// R2 没有 KV 那种 expirationTtl：活跃键按 UTC 日前缀隔离，徽章只列今天那一格，
// 旧键不参与计数、也不影响别的日子的前缀列举。10 GB 免费存储足够放很久；想收尸可在
// Cloudflare 面板给 active/ 前缀配一条两天的生命周期规则，不另起清理任务。
// R2 免费档 Class A 写 100 万次/月（约 3.3 万次/天），每个玩家每天一次心跳毫无压力。
const PLAYER_R2_PAGE_SIZE = 1000;
// R2 Class B 读 1000 万次/月。一次徽章请求只列一个前缀，300 秒缓存下理论上限
// 288 次/天/徽章；两个徽章并列 = 576，仍离额度很远。KV 的 1000 list/天 一点不碰。
const PLAYER_LIST_MAX_PAGES = 20;
const PLAYER_BADGE_CACHE_SECONDS = 300;
// R2 list 失败（绑定缺失 / 暂时不可读）时的降级徽章：短缓存，恢复后 1 分钟内刷回真实读数。
const PLAYER_BADGE_UNAVAILABLE_CACHE_SECONDS = 60;

const RAW_PREFIX = "eratw/";
const INDEX_PREFIX = "idx/";
const OCC_PREFIX = "occ/";
const LABEL_PREFIX = "lbl/";
// 计数真相只需要「这条上报存在」，值留 1 字节：聚合维度全在键名里，/stats 不读任何 occ 值。
const OCC_VALUE = "1";
// occ 翻页上限：20 页 × 每页 1000 键 = 2 万条上报以内不漏；超出如实标 listing_truncated。
const MAX_LIST_PAGES = 20;
/**
 * /stats 里「批量取不到就逐键补」的额度上限。
 * 算术：list 最多 20 发 + 批量 1 发 + 补取 12 发 = 33，留在免费档每请求 50 个子请求之内
 * （`LABEL_SINGLE_GET_BUDGET` 再大就可能把整发请求顶到子请求上限，判读面直接报错）。
 * 补取按份数从多到少走，所以这 12 发覆盖的一定是最该看的那几簇。
 */
const LABEL_SINGLE_GET_BUDGET = 12;
// /stats 最多列多少个签名簇（全量 617 份时实测 31 簇，200 有余量），超出如实标截断。
const STATS_SIGNATURE_TOP = 200;
// 机型码 / 签名码位数：够对账、不淹没键名（KV 单键上限 512 字节）。
const CODE_CHARS = 8;
// 版本位在键名里的最长字符数；净化后不含 `-`，所以按位切不会串位。
const VERSION_SLUG_MAX = 24;
// 回填一发请求处理的正文键上限（KV list 单页最大 1000）。
const REINDEX_MAX_BATCH = 1000;

const TIME_LINE_PREFIX = "报告时间：";
const MESSAGE_LINE_PREFIX = "信息：";
const SCRIPT_LINE_PREFIX = "脚本位置：";
const DEGRADED_MARK = "最小退化报告";
// 无签名的占位码必须不含 `-`（occ 尾段用它做分隔符），也不能是 8 位十六进制（会撞真签名码）。
const NO_SIGNATURE_CODE = "nosig";
const NO_VERSION_SLUG = "q";
const NO_DEVICE_CODE = "00000000";
const TRAIL_LINE_RE = /^t=\d+ms/;

function json(obj, status) {
  return new Response(JSON.stringify(obj, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function readToken(request, url) {
  return request.headers.get("X-Eratw-Token") || url.searchParams.get("token") || "";
}

// 事件自带的 at（epoch 毫秒）换算 UTC 日 YYYY-MM-DD；非法或离谱时间戳返回 null（整条拒）。
function utcDay(at) {
  if (typeof at !== "number" || !Number.isFinite(at) || at <= 0) return null;
  const d = new Date(at);
  if (Number.isNaN(d.getTime())) return null;
  return d.toISOString().slice(0, 10);
}

// 只有带 update-funnel kind 且 events 是数组的 JSON 才是漏斗批；其余（含纯文本错误
// 报告）返回 null，交回既有上报路径。首字符短路，免得对每份报告都跑一次 JSON.parse。
function parseFunnel(body) {
  if (!body.trimStart().startsWith("{")) return null;
  try {
    const parsed = JSON.parse(body);
    if (parsed && parsed.kind === FUNNEL_KIND && Array.isArray(parsed.events)) {
      return parsed.events;
    }
  } catch (_) {
    return null;
  }
  return null;
}

// 按「事件名 + UTC 日」把本批聚成 KV 键 -> 本批增量，再逐键读-改-写累加。
// assets_applied（第 7 拍）维校验不过整条拒；成功档按 kind 分桶成
// `funnel/<日>/assets_applied/<kind>`，失败档缺 kind 落 `funnel/<日>/assets_applied`
// （/funnel-stats 的 name 取首个斜杠后的整段，三格键原样透出，读面无需特判）。
// KV 没有原子增减：并发两批撞同一日同一事件可能少算一次，漏斗只看趋势，best-effort 够用；
// 重发（core 发送失败后整批补发）会多算，同样只影响绝对数、不影响趋势。
async function aggregateFunnel(kv, events) {
  const buckets = new Map();
  for (const ev of events) {
    if (!ev || typeof ev !== "object") continue;
    if (!FUNNEL_EVENT_NAMES.includes(ev.event)) continue;
    const day = utcDay(ev.at);
    if (!day) continue;
    let name = ev.event;
    if (ev.event === "assets_applied") {
      if (!funnelAssetsAppliedOk(ev)) continue;
      if (typeof ev.kind === "string" && FUNNEL_ASSETS_KINDS.has(ev.kind)) {
        name = `assets_applied/${ev.kind}`;
      }
    }
    const key = `${FUNNEL_PREFIX}${day}/${name}`;
    buckets.set(key, (buckets.get(key) || 0) + 1);
  }
  for (const [key, add] of buckets) {
    const prev = await kv.get(key);
    const base = Number.parseInt(prev || "0", 10) || 0;
    await kv.put(key, String(base + add));
  }
  let counted = 0;
  for (const add of buckets.values()) counted += add;
  return counted;
}

// 只带 player-presence kind 的 JSON 才是匿名登记；其余（错误报告、漏斗批、坏 JSON）
// 交回既有上报路径。返回 null = 不是本通道；{id:null} = 是本通道但 id 不合法。
// id 格式卡死 64 位小写十六进制：别让自由文本顺着键名灌进 R2。
function parsePresence(body) {
  if (!body.trimStart().startsWith("{")) return null;
  try {
    const parsed = JSON.parse(body);
    if (!parsed || parsed.kind !== PRESENCE_KIND) return null;
    return {
      id: typeof parsed.id === "string" && PLAYER_ID_RE.test(parsed.id) ? parsed.id : null,
    };
  } catch (_) {
    return null;
  }
}

/**
 * 登记一次匿名心跳，存储面是 **R2**（env.UPDATES），不是 KV。
 *
 * 累计去重不用计数器：一个 id 一个不可变对象，重发/并发都只写同一个键，R2 里该前缀下
 * 的对象条数就是玩家数 —— 读改写计数器的并发丢增量在这个形状里根本不存在。R2 是强一致的，
 * 写完 `head` 立刻可见，比 KV 的最终一致更适合这条口径。
 * 当日活跃另写 `active/<UTC 日>/<id>`；R2 没有 TTL，靠按天前缀隔离，旧键不进今天的计数。
 *
 * 全程零 KV 调用：判新用 `head`（Class B）、写入用 `put`（Class A），这两个额度都在
 * R2 免费档的 1000 万/100 万每月里，与 KV 的 1000 写/天 完全无关。
 */
async function registerPresence(bucket, id) {
  const day = new Date().toISOString().slice(0, 10);
  const playerKey = `${PLAYER_PREFIX}${id}`;
  const isNew = (await bucket.head(playerKey)) === null;
  if (isNew) await bucket.put(playerKey, day);
  await bucket.put(`${ACTIVE_PREFIX}${day}/${id}`, "1");
  return { day, isNew };
}

/** 数一个 R2 前缀下有多少对象；R2 list 回 objects/truncated/cursor，与 KV 的 keys/list_complete 不同。 */
async function countKeys(bucket, prefix) {
  let count = 0;
  let cursor;
  for (let page = 0; page < PLAYER_LIST_MAX_PAGES; page++) {
    const list = await bucket.list({ prefix, limit: PLAYER_R2_PAGE_SIZE, cursor });
    count += list.objects.length;
    if (!list.truncated) return { count, truncated: false };
    cursor = list.cursor;
  }
  return { count, truncated: true };
}

/** 千分位纯 JS 实现，不吃 Intl（徽章里 12,345 比 12345 好读，且不引入区域差异）。 */
function withThousands(n) {
  return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/**
 * 公开只读的玩家数出口。默认给 shields.io 的 endpoint 徽章用，只出**一个总数**；
 * /players/raw 或 ?format=raw 给部署者自己核对，多带一个今日活跃与生成时刻。
 * 免令牌是有意的：徽章要能被 shields.io 与 GitHub 的图片代理匿名拉取，而回包里
 * 没有任何单个 id、没有报告正文、没有设备信息，等于把「一个数」公开而已。
 */
async function handlePlayers(request, env, url) {
  const corsHeaders = {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
    "Access-Control-Max-Age": "86400",
  };
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders });
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    return json({ error: "只支持 GET" }, 405);
  }
  if (!env.UPDATES) {
    return json({ error: "UPDATES R2 未绑定：检查 wrangler.toml [[r2_buckets]]" }, 500);
  }
  const day = new Date().toISOString().slice(0, 10);
  const wantsRaw = url.pathname === "/players/raw" || url.searchParams.get("format") === "raw";
  const metric = url.searchParams.get("metric") === "active" ? "active" : "players";
  // 一次读只列一个前缀（R2 Class B）；raw 是给 GitHub Actions / 部署者核对的低频面才两边都数。
  // 注意这一格读的是 R2：KV 的 1000 list/天 一点不占，KV 整个挂掉也不影响它。
  let players = null;
  let active = null;
  try {
    if (wantsRaw || metric === "players") players = await countKeys(env.UPDATES, PLAYER_PREFIX);
    if (wantsRaw || metric === "active") active = await countKeys(env.UPDATES, `${ACTIVE_PREFIX}${day}/`);
  } catch (err) {
    // R2 暂时不可读时不许把 5xx 交给徽章：shields.io 会把错误页渲染成坏图，
    // GitHub 的图片代理还会把它缓存住。降级成短缓存的 unknown，读面始终干净。
    // 上游是 GitHub Actions 定时取的静态 JSON 时，它也会跳过这一轮、保留上一次的读数。
    if (wantsRaw) {
      return json({ error: "R2 列举暂时不可用，玩家数未取到" }, 503);
    }
    const unavailable = {
      schemaVersion: 1,
      label: metric === "active" ? "active today" : "players",
      message: "unknown",
      color: "lightgrey",
      cacheSeconds: PLAYER_BADGE_UNAVAILABLE_CACHE_SECONDS,
    };
    return new Response(request.method === "HEAD" ? null : JSON.stringify(unavailable, null, 2), {
      status: 200,
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": `public, max-age=${PLAYER_BADGE_UNAVAILABLE_CACHE_SECONDS}`,
        ...corsHeaders,
      },
    });
  }
  let body;
  if (wantsRaw) {
    body = {
      players: players.count,
      active_today: active.count,
      day,
      truncated: players.truncated || active.truncated,
      generated_at: new Date().toISOString(),
    };
  } else if (metric === "active") {
    body = {
      schemaVersion: 1,
      label: "active today",
      message: withThousands(active.count),
      color: "blue",
      cacheSeconds: PLAYER_BADGE_CACHE_SECONDS,
    };
  } else {
    body = {
      schemaVersion: 1,
      label: "players",
      message: withThousands(players.count),
      color: "brightgreen",
      cacheSeconds: PLAYER_BADGE_CACHE_SECONDS,
    };
  }
  return new Response(request.method === "HEAD" ? null : JSON.stringify(body, null, 2), {
    status: 200,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": `public, max-age=${PLAYER_BADGE_CACHE_SECONDS}`,
      ...corsHeaders,
    },
  });
}

/**
 * 更新面：GET /updates/<path>，从 R2 取对象返回。
 * 支持 Range 断点续传，CORS 全开，不要求令牌。
 */
async function handleUpdates(request, env, url) {
  // CORS 预检
  if (request.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "Access-Control-Allow-Origin": "*",
        "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
        "Access-Control-Allow-Headers": "Range",
        "Access-Control-Max-Age": "86400",
      },
    });
  }
  if (request.method !== "GET" && request.method !== "HEAD") {
    return json({ error: "只支持 GET" }, 405);
  }
  if (!env.UPDATES) {
    return json({ error: "UPDATES R2 未绑定：检查 wrangler.toml [[r2_buckets]]" }, 500);
  }

  const prefix = "/updates/";
  const key = url.pathname.slice(prefix.length);
  if (!key || key.includes("..")) {
    return json({ error: "路径不合法" }, 400);
  }
  // 统计内部对象与更新产物共用同一个 R2 桶，但不作为下载产物暴露：值只是日期或 1 字节，
  // 没有隐私面，可也不能让玩家拿 /updates/player/<id> 把内部命名空间当文件列表翻。
  if (key.startsWith(PLAYER_PREFIX) || key.startsWith(ACTIVE_PREFIX)) {
    return json({ error: "统计内部对象不可下载" }, 404);
  }

  // 解析 Range 头：bytes=<start>-[<end>]
  const rangeHeader = request.headers.get("Range");
  let range = undefined;
  if (rangeHeader) {
    const m = rangeHeader.match(/^bytes=(\d+)-(\d*)$/);
    if (m) {
      const start = parseInt(m[1], 10);
      const end = m[2] ? parseInt(m[2], 10) : undefined;
      range = { offset: start };
      if (end !== undefined) {
        range.length = end - start + 1;
      }
    }
    // 无法解析的 Range 忽略（当无 Range 处理，返回全量）
  }

  let obj = null;
  let rangeUnsatisfiable = false;
  try {
    obj = await env.UPDATES.get(key, range ? { range } : undefined);
  } catch (err) {
    // 起点越界（`bytes=N-` 且 N ≥ 对象长度）时 R2 **抛异常**而不是回 null。
    // 这在 HTTP 语义里是 416，不是 500：客户端把「500」当服务端坏了会继续重试同一地址，
    // 而「416」的正确处置是**丢掉 .part 整段重来**。实测（2026-11-01）：越界请求回 500
    // 就是这个口子没堵。非 Range 请求的异常照旧往上抛，别把真故障咽成 404/416。
    if (!range) throw err;
    rangeUnsatisfiable = true;
  }
  if (rangeUnsatisfiable) {
    return new Response(null, {
      status: 416,
      headers: { "Accept-Ranges": "bytes", "Access-Control-Allow-Origin": "*" },
    });
  }
  if (!obj) {
    return json({ error: "对象不存在" }, 404);
  }

  const headers = {
    "Access-Control-Allow-Origin": "*",
    "Accept-Ranges": "bytes",
    "Content-Type": obj.httpMetadata?.contentType || "application/octet-stream",
  };

  // 全量请求的 Content-Length 来自对象的 body size；Range 请求时 R2 返回的是切片
  if (range) {
    // R2 对象在有 range 时：obj.range 给出 offset/length/size（原始大小）
    const totalSize = obj.range?.size ?? obj.size;
    const from = range.offset;
    const to = range.length !== undefined
      ? Math.min(from + range.length - 1, totalSize - 1)
      : totalSize - 1;
    headers["Content-Length"] = String(to - from + 1);
    headers["Content-Range"] = `bytes ${from}-${to}/${totalSize}`;
    return new Response(request.method === "HEAD" ? null : obj.body, {
      status: 206,
      headers,
    });
  }

  headers["Content-Length"] = String(obj.size);
  return new Response(request.method === "HEAD" ? null : obj.body, {
    status: 200,
    headers,
  });
}

// ----------------------------------------------------------------------
// 指纹与字段读取
//
// 这套口径与 devtools/triage_reports.py 必须逐字一致，两侧用同一份夹具对拍：
// tools/report-endpoint/fingerprint-fixtures.json（node 跑 worker.test.mjs，
// python 跑 triage_reports.py --selftest，两边都比 sha256 全串）。
// ----------------------------------------------------------------------

function toHex(buffer) {
  return [...new Uint8Array(buffer)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function sha256Hex(text) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return toHex(digest);
}

/** 行切分：CRLF 与裸 CR 一律折成 LF，行尾空白去掉；别的都不动，两侧可复现。 */
function splitLines(text) {
  return text.replace(/\r\n?/g, "\n").split("\n").map((line) => line.replace(/[ \t]+$/, ""));
}

function fieldOf(lines, label) {
  const line = lines.find((l) => l.startsWith(label));
  return line === undefined ? "" : line.slice(label.length).trim();
}

/**
 * `[错误]` 小节正文：节名行到下一条分隔线为止（同 devtools/triage_reports.py 的 _error_section）。
 * 只在这一节里取 信息 / 脚本位置 —— 补充现场与因果链里同名的行不算，否则签名会被现场行带跑。
 */
function errorSectionLines(lines) {
  const start = lines.findIndex((l) => l.trim() === "[错误]");
  if (start < 0) return [];
  const section = [];
  for (let i = start + 1; i < lines.length; i++) {
    if (/^-{10,}$/.test(lines[i])) break;
    section.push(lines[i]);
  }
  return section;
}

/** 消息里的数字抹成 N（口径同 app 侧 ReportSignature.normalize：数据不是身份；行号留在位置里）。 */
function normalizeMessage(message) {
  return message.replace(/\d+/g, "N").trim();
}

/**
 * 「有身份」= 正文里已经有能区分两次事故的东西：异常信息、脚本位置，或至少一条运行轨迹行。
 * 没身份的报告，两次不同时刻的上传必须各留一条，所以它的指纹把 `报告时间：` 原值加回哈希。
 */
function hasIdentity(lines) {
  if (lines.some((l) => TRAIL_LINE_RE.test(l))) return true;
  const error = errorSectionLines(lines);
  return error.some(
    (l) => (l.startsWith(MESSAGE_LINE_PREFIX) && l.length > MESSAGE_LINE_PREFIX.length)
      || (l.startsWith(SCRIPT_LINE_PREFIX) && l.length > SCRIPT_LINE_PREFIX.length)
  );
}

/**
 * 内容指纹。哈希输入 = 归一后的正文行（去掉 `报告时间：` 行与**末尾空行**）；
 * 只有「没身份」时把 `报告时间：` 那一行当盐加回。
 * identity 位随 idx 落库，判读侧据此分清「宽松合并」和「逐字节相同」。
 */
async function fingerprintOf(lines) {
  const kept = lines.filter((l) => !l.startsWith(TIME_LINE_PREFIX));
  // 末尾空行不进哈希：正文是否带一个尾换行由上传路径决定，不是事故的一部分。
  while (kept.length > 0 && kept[kept.length - 1] === "") kept.pop();
  const timeLine = lines.find((l) => l.startsWith(TIME_LINE_PREFIX)) || "";
  const identity = hasIdentity(lines);
  const hashInput = identity ? kept.join("\n") : `${kept.join("\n")}\n${timeLine}`;
  return {
    fp: await sha256Hex(hashInput),
    identity,
    time: timeLine.slice(TIME_LINE_PREFIX.length).trim(),
  };
}

/**
 * 报告类型 → 单字符码（键名要短；o = 其余，逐字原文在正文里，要看就去 /reports 拉）。
 * 2026-11-17 覆盖补齐波给三类新报告独立码：u/w/d —— 老报告类型的码保持不变，历史 occ 键
 * 的 tail[1] 不受影响；新增类型无历史包袱。顺序：先认精确的新类型，再走旧的关键字匹配
 * （「启动流程异常」含「启动」，不先认就会被旧规则吞进 s）。
 */
function kindCodeOf(kind) {
  if (!kind) return "n";
  if (kind.includes("UI线程异常")) return "u";
  if (kind.includes("装载警告")) return "w";
  if (kind.includes("诊断汇总")) return "d";
  if (kind.includes("启动流程异常")) return "t";
  if (kind.includes("崩溃")) return "c";
  if (kind.includes("启动")) return "s";
  if (kind.includes("脚本")) return "r";
  if (kind.includes("异常退出")) return "x";
  return "o";
}

/** 类型码 → 判读标签（/stats 的 kinds 段用）。 */
const KIND_LABELS = {
  c: "应用崩溃",
  s: "启动失败",
  t: "启动流程异常（壳层）",
  r: "脚本运行报错",
  x: "上次异常退出",
  u: "UI线程异常",
  w: "装载警告",
  d: "诊断汇总",
  o: "其余",
  n: "无类型",
};

/** `app 版本：0.2.39 (versionCode 15)` → `0.2.39`；没有这行（最小退化报告）→ 空串。 */
function versionOf(lines) {
  const raw = fieldOf(lines, "app 版本：");
  const m = raw.match(/^(\S+)\s*\(versionCode\s*(\d+)\)/);
  return m ? m[1] : raw;
}

/** 版本位进键名：只留字母数字点，其余换下划线并截断；空/取不到一律 q —— 判读侧的 ver=? 桶。 */
function versionSlugOf(version) {
  if (!version) return NO_VERSION_SLUG;
  const slug = version.replace(/[^0-9A-Za-z.]/g, "_").slice(0, VERSION_SLUG_MAX);
  return slug || NO_VERSION_SLUG;
}

/**
 * 一次上传的全部聚合读数（指纹、签名码、机型码、退化位）。
 *
 * 机型码取的是「设备」行（厂商 + 型号，见 app/src/main/kotlin/com/eratw/port/android/ErrorReportCollector.kt:701）
 * ——它是**机型**不是安装实例：同型号两台在这里算一台。要按安装实例分，得让产生侧随报告带一个
 * 每安装随机号（已写进交付报告的「需组长接线」，端点不猜）。
 */
async function readUploadReport(body) {
  const lines = splitLines(body);
  const error = errorSectionLines(lines);
  const message = fieldOf(error, MESSAGE_LINE_PREFIX);
  const script = fieldOf(error, SCRIPT_LINE_PREFIX).trim();
  const device = fieldOf(lines, "设备：");
  const version = versionOf(lines);
  const fingerprint = await fingerprintOf(lines);
  const sigCode = message || script
    ? (await sha256Hex(`${normalizeMessage(message)}\u001f${script}`)).slice(0, CODE_CHARS)
    : NO_SIGNATURE_CODE;
  return {
    fp: fingerprint.fp,
    identity: fingerprint.identity,
    kind: fieldOf(lines, "报告类型："),
    version,
    versionSlug: versionSlugOf(version),
    device,
    deviceCode: device ? (await sha256Hex(device)).slice(0, CODE_CHARS) : NO_DEVICE_CODE,
    stamp: fieldOf(lines, "构建标识："),
    message,
    script,
    sigCode,
    degraded: lines.some((l) => l.includes(DEGRADED_MARK)),
    trailLines: lines.filter((l) => TRAIL_LINE_RE.test(l)).length,
  };
}

/**
 * occ 键名：上报毫秒 + 全部聚合维度。
 * 历史回填时毫秒取自已有正文键名，所以重跑幂等：同一份正文两次回填得到同一条键，不会双计。
 */
function occurrenceKeyOf(read, uploadMs) {
  const tail = [
    uploadMs,
    kindCodeOf(read.kind),
    read.degraded ? "1" : "0",
    read.versionSlug,
    read.deviceCode,
    read.sigCode,
  ].join("-");
  return `${OCC_PREFIX}${read.fp}/${tail}`;
}

/** 从 occ 键名把聚合维度读回来（/stats 只认键名，一个正文都不碰）。 */
function parseOccurrenceKey(key) {
  if (!key.startsWith(OCC_PREFIX)) return null;
  const parts = key.slice(OCC_PREFIX.length).split("/");
  if (parts.length !== 2) return null;
  const tail = parts[1].split("-");
  if (tail.length < 6) return null;
  const uploadMs = Number(tail[0]);
  if (!Number.isFinite(uploadMs)) return null;
  return {
    fp: parts[0],
    uploadMs,
    kindCode: tail[1],
    degraded: tail[2] === "1",
    versionSlug: tail[3],
    deviceCode: tail[4],
    // 版本位已净化成不含 `-` 的串，所以末段恒为签名码（8 位，或无签名时的单字符占位）。
    sigCode: tail[tail.length - 1],
  };
}

function newBucket() {
  return {
    occurrences: 0,
    fingerprints: new Set(),
    devices: new Set(),
    signatures: new Set(),
    degradedOccurrences: 0,
    firstUploadMs: Number.POSITIVE_INFINITY,
    lastUploadMs: 0,
  };
}

function bump(bucket, occ) {
  bucket.occurrences += 1;
  bucket.fingerprints.add(occ.fp);
  bucket.devices.add(occ.deviceCode);
  if (occ.sigCode !== NO_SIGNATURE_CODE) bucket.signatures.add(occ.sigCode);
  if (occ.degraded) bucket.degradedOccurrences += 1;
  if (occ.uploadMs < bucket.firstUploadMs) bucket.firstUploadMs = occ.uploadMs;
  if (occ.uploadMs > bucket.lastUploadMs) bucket.lastUploadMs = occ.uploadMs;
}

function freezeBucket(bucket) {
  const empty = bucket.fingerprints.size === 0;
  return {
    occurrences: bucket.occurrences,
    incidents: bucket.fingerprints.size,
    devices: empty ? 0 : bucket.devices.size,
    signatures: bucket.signatures.size,
    degraded_occurrences: bucket.degradedOccurrences,
    first_upload_ms: empty ? null : bucket.firstUploadMs,
    last_upload_ms: empty ? null : bucket.lastUploadMs,
  };
}

function bucketOf(map, key) {
  let bucket = map.get(key);
  if (bucket === undefined) {
    bucket = newBucket();
    map.set(key, bucket);
  }
  return bucket;
}

/** 某一版本位下有多少份「无签名」上报（静默退出与退化都要它，否则版本表会把它们算成缺陷）。 */
function countNoSignature(occurrences, slug) {
  let n = 0;
  for (const occ of occurrences) {
    if (occ.versionSlug === slug && occ.sigCode === NO_SIGNATURE_CODE) n += 1;
  }
  return n;
}

/**
 * 判读面：GET /stats —— 一次调用给出「签名簇 × 版本 × 机型数」，并把无签名（退化/最小 + 纯静默退出）
 * 单独一桶。只做 occ 前缀的 list（键名自带维度）+ 每簇一行的标签 get，正文一份都不拉。
 */
/**
 * 标签批量取：KV 的 get 接受键名数组（一批最多 1000），几十簇就是一发请求。
 *
 * 2026-11-05 线上实测：回填 676 份后 `GET /stats` 出 51 簇、**51 簇全带 label:null**，
 * 而 `wrangler kv key list --prefix lbl/` 数出 45 把键在。判读面拿着没有文案的签名码等于
 * 没有这张表（要文案就得逐簇 `/sample/<指纹>` 拉正文，正是去重要省掉的活）。
 * 所以这里不再赌单一形状：批量返回值先过 [labelRawOf]（字符串与 {value} 包装都认），
 * 认不出来的按份数从多到少**逐键补取**一小把（额度见 [LABEL_SINGLE_GET_BUDGET]）。
 */
async function readLabels(env, codes, sigBuckets) {
  const out = new Map();
  if (codes.length === 0) return out;
  let bulk = null;
  try {
    bulk = await env.REPORTS.get(codes.map((code) => LABEL_PREFIX + code));
  } catch (err) {
    bulk = null; // 批量形态整个失败也不许把 /stats 打成 500：退到逐键那一把
  }
  const bulkMap = bulkLabelMap(bulk);
  for (const code of codes) {
    const raw = labelRawOf(bulkMap.get(LABEL_PREFIX + code));
    if (raw !== null) out.set(code, raw);
  }
  if (out.size === codes.length) return out;
  const missing = codes
    .filter((code) => !out.has(code))
    .sort((a, b) => bucketOccurrences(sigBuckets, b) - bucketOccurrences(sigBuckets, a) || a.localeCompare(b))
    .slice(0, LABEL_SINGLE_GET_BUDGET);
  for (const code of missing) {
    let raw = null;
    try {
      raw = labelRawOf(await env.REPORTS.get(LABEL_PREFIX + code));
    } catch (err) {
      break; // 子请求额度见底：宁可少几簇标签，也不让判读面整个报错
    }
    if (raw !== null) out.set(code, raw);
  }
  return out;
}

/**
 * 批量 get 的结果归一成 `Map`。
 *
 * 2026-11-05 线上探针实测（`label_probe`，取证后已删）：Cloudflare workerd 的
 * `env.REPORTS.get(键名数组)` 回的是 **`Map`**（`bulk_is_map: true`、`size: 51`、
 * `Object.keys()` 为空），而不是文档里常见的 `{键: 值}`。所以下面三种给法都摊平：
 * `Map` / `{键: 值}` / 条目数组（`{key, value}` 与 `[key, value]`）。
 * 只按下标取键的那一版在线上等于整批发空：51 簇只有逐键补的 12 簇读得出标签。
 */
function bulkLabelMap(bulk) {
  const map = new Map();
  if (!bulk || typeof bulk === "string") return map;
  if (bulk instanceof Map) {
    for (const [key, value] of bulk.entries()) if (typeof key === "string") map.set(key, value);
    return map;
  }
  if (Array.isArray(bulk)) {
    for (const item of bulk) {
      if (Array.isArray(item) && typeof item[0] === "string") map.set(item[0], item[1]);
      else if (item && typeof item.key === "string") map.set(item.key, item.value);
    }
    return map;
  }
  if (typeof bulk === "object") {
    for (const key of Object.keys(bulk)) map.set(key, bulk[key]);
  }
  return map;
}

/** 某一签名簇的上报份数（只用于给逐键补取排优先级）。 */
function bucketOccurrences(sigBuckets, code) {
  const bucket = sigBuckets.get(code);
  return bucket && typeof bucket.occurrences === "number" ? bucket.occurrences : 0;
}

/**
 * KV 的值两种形状都认：字符串（单键 get 的缺省）与 `{value, metadata}` 包装。
 * 其它（undefined / null / 非文本）一律回 null 当作「这簇没有标签」。
 */
function labelRawOf(raw) {
  if (typeof raw === "string") return raw;
  if (raw && typeof raw === "object" && typeof raw.value === "string") return raw.value;
  return null;
}

/** 窗口参数合法域（天）：判读常用的周/月视角；上限防「全量再乘一遍」的误用——全期看主表就行。 */
const WINDOW_DAYS_MIN = 1;
const WINDOW_DAYS_MAX = 90;

/**
 * `?windowDays=<n>` 解析：缺省 null = 不出窗口段（响应完全不变，向后兼容）；
 * 给了但不是 1..90 的整数 → 400（宁可报错不给错表——半开区间的口径错一半就废了）。
 */
function parseWindowDays(url) {
  const raw = url.searchParams.get("windowDays");
  if (raw === null || raw === "") return null;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < WINDOW_DAYS_MIN || n > WINDOW_DAYS_MAX) {
    return { error: `windowDays 必须是 ${WINDOW_DAYS_MIN}..${WINDOW_DAYS_MAX} 的整数（收到 ${raw}）` };
  }
  return { days: n };
}

async function handleStats(env, url) {
  const window = parseWindowDays(url);
  if (window && window.error) return json({ error: window.error }, 400);
  const windowCutoffMs = window && window.days !== undefined
    ? Date.now() - window.days * 24 * 60 * 60 * 1000
    : null;

  const occurrences = [];
  let cursor;
  let pages = 0;
  let listingTruncated = false;
  for (; pages < MAX_LIST_PAGES; pages++) {
    const list = await env.REPORTS.list({ prefix: OCC_PREFIX, cursor });
    for (const k of list.keys) {
      const occ = parseOccurrenceKey(k.name);
      if (occ) occurrences.push(occ);
    }
    if (list.list_complete) break;
    cursor = list.cursor;
  }
  if (pages === MAX_LIST_PAGES && cursor) listingTruncated = true;

  const sigBuckets = new Map();
  const versionBuckets = new Map();
  const deviceBuckets = new Map();
  const crossBuckets = new Map();
  const kindBuckets = new Map();
  const allFingerprints = new Set();
  const noSignature = newBucket();
  let noSignatureOfUnknownVersion = 0;
  // 时间窗聚合（DBG-TOOLING 波）：occ 键名自带上报毫秒 ⇒ 同一次 list 就能切窗，零额外 KV 读。
  const windowBucket = newBucket();
  const windowKindBuckets = new Map();
  const windowSigBuckets = new Map();
  for (const occ of occurrences) {
    allFingerprints.add(occ.fp);
    if (windowCutoffMs !== null && occ.uploadMs >= windowCutoffMs) {
      bump(windowBucket, occ);
      bump(bucketOf(windowKindBuckets, occ.kindCode), occ);
      if (occ.sigCode !== NO_SIGNATURE_CODE) bump(bucketOf(windowSigBuckets, occ.sigCode), occ);
    }
    bump(bucketOf(versionBuckets, occ.versionSlug), occ);
    bump(bucketOf(deviceBuckets, occ.deviceCode), occ);
    bump(bucketOf(kindBuckets, occ.kindCode), occ);
    if (occ.sigCode === NO_SIGNATURE_CODE) {
      bump(noSignature, occ);
      if (occ.versionSlug === NO_VERSION_SLUG) noSignatureOfUnknownVersion += 1;
      continue;
    }
    bump(bucketOf(sigBuckets, occ.sigCode), occ);
    // 签名 × 版本这一交叉维度：出表时每簇带自己的版本行，判读侧不必再拉一次正文。
    bump(bucketOf(crossBuckets, `${occ.sigCode}\u001f${occ.versionSlug}`), occ);
  }

  const sigCodes = [...sigBuckets.keys()].sort();
  const labels = await readLabels(env, sigCodes, sigBuckets);
  const signatureList = sigCodes.map((code) => {
    const raw = labels.get(code);
    let label = null;
    if (typeof raw === "string") {
      try {
        label = JSON.parse(raw);
      } catch (err) {
        label = { message: "", script: "", label_parse_error: true };
      }
    }
    const versions = [];
    for (const [crossKey, bucket] of crossBuckets) {
      const [owner, slug] = crossKey.split("\u001f");
      if (owner === code) {
        versions.push({ version: slug === NO_VERSION_SLUG ? "?" : slug, ...freezeBucket(bucket) });
      }
    }
    versions.sort((a, b) => b.occurrences - a.occurrences || a.version.localeCompare(b.version));
    return { sig: code, ...freezeBucket(sigBuckets.get(code)), versions, label };
  });
  signatureList.sort((a, b) => b.occurrences - a.occurrences
    || b.devices - a.devices
    || a.sig.localeCompare(b.sig));

  const versionList = [...versionBuckets.entries()].map(([slug, bucket]) => ({
    version: slug === NO_VERSION_SLUG ? "?" : slug,
    ...freezeBucket(bucket),
    // 这一版有几份读不出签名：signatures=0 而 occurrences>0 的版本，整版都定不了缺陷形状。
    no_signature_occurrences: slug === NO_VERSION_SLUG
      ? noSignatureOfUnknownVersion
      : countNoSignature(occurrences, slug),
  })).sort((a, b) => b.occurrences - a.occurrences || a.version.localeCompare(b.version));

  return json({
    generated_at: new Date().toISOString(),
    // occurrences = 上报条数（含重复上传）；incidents = 去重后的内容条数；差值就是白拉的份数。
    occurrences: occurrences.length,
    incidents: allFingerprints.size,
    duplicate_uploads: occurrences.length - allFingerprints.size,
    signatures_total: signatureList.length,
    signatures_returned: Math.min(signatureList.length, STATS_SIGNATURE_TOP),
    signatures_truncated: signatureList.length > STATS_SIGNATURE_TOP,
    listing_truncated: listingTruncated,
    // 这张表只统计「带去重版 worker 之后」的上传（含 /reindex 回填的历史正文）。
    backfill_hint: "历史正文不在 occ 索引里：跑 POST /reindex 回填，或退回 GET /reports 逐份拉原文",
    signatures: signatureList.slice(0, STATS_SIGNATURE_TOP),
    no_signature: {
      ...freezeBucket(noSignature),
      hint: "无信息也无脚本位置：静默退出（带轨迹）与最小退化报告都在这一桶，退化份数看 degraded_occurrences",
    },
    versions: versionList,
    // kinds = 按报告类型的聚合维度（2026-11-17 上报覆盖补齐波）：判读侧第一眼就能看到
    // 崩溃/启动/脚本/异常退出/其余 各多少份、多少去重 incidents、多少机型，不必自行解析 occ 键。
    kinds: [...kindBuckets.entries()].map(([code, bucket]) => ({
      kind_code: code,
      label: KIND_LABELS[code] || "未知类型",
      ...freezeBucket(bucket),
    })).sort((a, b) => b.occurrences - a.occurrences),
    devices: [...deviceBuckets.entries()].map(([code, bucket]) => ({
      device_code: code,
      ...freezeBucket(bucket),
    })).sort((a, b) => b.occurrences - a.occurrences).slice(0, 40),
    // window = 时间窗视角（DBG-TOOLING 波，?windowDays=N 才出现）：「最近一周/一月涨了什么」
    // 不必再拿 first/last_upload_ms 手推。signatures 只给签名码+计数（贴标签看主 signatures
    // 表，不在窗口段再花 KV 读），kinds 同主表口径但只在窗口内计。
    ...(window && window.days !== undefined ? {
      window: {
        days: window.days,
        since_ms: windowCutoffMs,
        ...freezeBucket(windowBucket),
        kinds: [...windowKindBuckets.entries()].map(([code, bucket]) => ({
          kind_code: code,
          label: KIND_LABELS[code] || "未知类型",
          ...freezeBucket(bucket),
        })).sort((a, b) => b.occurrences - a.occurrences),
        signatures: [...windowSigBuckets.entries()].map(([code, bucket]) => ({
          sig: code,
          ...freezeBucket(bucket),
        })).sort((a, b) => b.occurrences - a.occurrences || a.sig.localeCompare(b.sig)).slice(0, 20),
      },
    } : {}),
    limit_note: `list 最多翻 ${MAX_LIST_PAGES} 页（每页 1000 键），签名簇最多列 ${STATS_SIGNATURE_TOP} 个`,
  });
}

/** 按指纹取首份正文：判读侧每簇只拉一份样张，不必把 617 份全拉下来。 */
async function handleSample(env, url) {
  const fp = decodeURIComponent(url.pathname.slice("/sample/".length));
  if (!/^[0-9a-f]{64}$/.test(fp)) return json({ error: "指纹不合法（要 sha256 十六进制 64 位）" }, 400);
  const raw = await env.REPORTS.get(INDEX_PREFIX + fp);
  if (raw === null) return json({ error: "这个指纹没有索引（历史报告要先 POST /reindex 回填）" }, 404);
  let index;
  try {
    index = JSON.parse(raw);
  } catch (err) {
    return json({ error: "索引损坏，改用 /report/<键> 取正文" }, 500);
  }
  if (!index || !index.key) return json({ error: "索引里没有正文键" }, 404);
  const text = await env.REPORTS.get(index.key);
  if (text === null) return json({ error: "正文键已不在", key: index.key }, 404);
  return new Response(text, {
    status: 200,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "content-disposition": `attachment; filename="${index.key.split("/").pop()}"`,
      "X-Eratw-Fingerprint": fp.slice(0, CODE_CHARS),
      "X-Eratw-Occurrences": String(index.occurrences || 1),
    },
  });
}

/**
 * 历史回填：POST /reindex?limit=<n>[&cursor=<上次 next_cursor>]
 *
 * 把去重版上线前就躺在 KV 里的正文补进 occ/idx/lbl。上报毫秒取自**已有正文键名**
 * （`eratw/<日期>/<毫秒>-<名>`），所以幂等：重跑同一份正文得到同一条 occ 键，不会双计；
 * idx 与 lbl 都是「没有才写」，不会把线上刚写的样张指针挪走。
 * limit 缺省 200 —— 别在一发请求里跑完整库（Worker 有 CPU 与子请求上限）。
 */
async function handleReindex(env, url) {
  const requested = parseInt(url.searchParams.get("limit") || "200", 10);
  const limit = Math.min(Math.max(Number.isFinite(requested) ? requested : 200, 1), REINDEX_MAX_BATCH);
  const cursor = url.searchParams.get("cursor") || undefined;
  const list = await env.REPORTS.list({ prefix: RAW_PREFIX, limit, cursor });
  let indexed = 0;
  let skipped = 0;
  for (const entry of list.keys) {
    const body = await env.REPORTS.get(entry.name);
    if (body === null || !body.includes(REPORT_MARKER)) {
      skipped += 1;
      continue;
    }
    const uploadMs = Number(String(entry.name).split("/").pop().split("-")[0]);
    const read = await readUploadReport(body);
    await env.REPORTS.put(
      occurrenceKeyOf(read, Number.isFinite(uploadMs) ? uploadMs : Date.now()),
      OCC_VALUE
    );
    await writeIndex(env, read, entry.name);
    await writeLabel(env, read);
    indexed += 1;
  }
  const done = list.list_complete === true;
  return json({
    scanned: list.keys.length,
    indexed,
    skipped,
    done,
    next_cursor: done ? null : list.cursor,
    note: "done=false 就带 next_cursor 再发一次，直到翻完整个正文前缀",
  });
}

/**
 * idx：「没有才写」，返回是否写了。写失败不追 —— 正文已落盘，报告不会丢，
 * 最坏是下次同内容再存一份正文（宁可留双份）。
 */
async function writeIndex(env, read, sampleKey) {
  const indexKey = INDEX_PREFIX + read.fp;
  if ((await env.REPORTS.get(indexKey)) !== null) return false;
  await env.REPORTS.put(indexKey, JSON.stringify({
    key: sampleKey,
    occurrences: 1,
    sig: read.sigCode,
    kind: read.kind,
    version: read.version,
    device: read.device,
    degraded: read.degraded,
    identity: read.identity,
    trail_lines: read.trailLines,
  }));
  return true;
}

async function writeLabel(env, read) {
  if (read.sigCode === NO_SIGNATURE_CODE) return false;
  const labelKey = LABEL_PREFIX + read.sigCode;
  if ((await env.REPORTS.get(labelKey)) !== null) return false;
  await env.REPORTS.put(labelKey, JSON.stringify({
    message: normalizeMessage(read.message),
    message_raw: read.message,
    script: read.script,
  }));
  return true;
}

/**
 * 重复上传：把 idx 的条数 +1（尽力而为；读改写并发时最坏丢一次增量，真实计数看 occ）。
 * 索引读不到或是损坏 JSON 就返回 false —— 上层接着走「新内容」分支，宁可留双份。
 */
async function countDuplicate(env, read) {
  const indexKey = INDEX_PREFIX + read.fp;
  const raw = await env.REPORTS.get(indexKey);
  if (raw === null) return false;
  let index;
  try {
    index = JSON.parse(raw);
  } catch (err) {
    return false;
  }
  if (!index || !index.key) return false;
  index.occurrences = (index.occurrences || 1) + 1;
  await env.REPORTS.put(indexKey, JSON.stringify(index));
  return true;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    // ---- 更新面（公开只读，从 R2 取产物，不检查令牌）-----------------------
    // 铁律：这一支绝不能碰 env.REPORTS。统计面现在也存 R2，但用 player/ 与 active/
    // 两个独立前缀，更新面明确拒收（见 handleUpdates）。整个分支只读 R2，且排在
    // REPORT_TOKEN 与 REPORTS 绑定检查之前 —— KV 整个挂掉也拦不住玩家下 APK。
    if (url.pathname.startsWith("/updates/")) {
      return handleUpdates(request, env, url);
    }

    // ---- 统计面（公开只读，只回总数，不检查令牌）----------------------------
    // 排在令牌检查之前：徽章要能被 shields.io / GitHub camo 匿名拉取。
    if (url.pathname === "/players" || url.pathname === "/players/raw") {
      return handlePlayers(request, env, url);
    }

    // ---- 上报面 + 判读面（都要令牌）----------------------------------------
    if (!env.REPORT_TOKEN) {
      return json({ error: "REPORT_TOKEN 未配置：wrangler secret put REPORT_TOKEN" }, 500);
    }
    // REPORTS 绑定检查**故意不在这里**：player-presence 登记只写 R2，不该被 KV 绑定状态
    // 或 KV 当日额度卡住。检查延后到真正需要 KV 的分支（presence 之后的漏斗/报告、
    // 查看面、reindex），统计面因此与 KV 完全解耦。

    // ---- 写入面 ----------------------------------------------------------
    if (request.method === "POST" && url.pathname === "/report") {
      if (readToken(request, url) !== env.REPORT_TOKEN) return json({ error: "token 不符" }, 403);
      const body = await request.text();
      if (!body) return json({ error: "空 body" }, 400);
      if (body.length > MAX_BODY_BYTES) return json({ error: "body 超过 512KB" }, 413);
      // 匿名玩家登记与错误报告共用本端点：同样排在 MARKER 检查之前。
      // 与漏斗分开判，是因为登记只带一个 id，没有 events 数组，也不该被聚成事件计数。
      const presence = parsePresence(body);
      if (presence) {
        if (presence.id === null) {
          return json({ error: "player-presence 的 id 必须是 64 位小写十六进制" }, 400);
        }
        if (!env.UPDATES) {
          return json({ error: "UPDATES R2 未绑定：检查 wrangler.toml [[r2_buckets]]" }, 503);
        }
        try {
          // 只写 R2：head/put 都是 R2 额度，KV 一次都不碰，也不受 KV 当日写额度影响。
          const registered = await registerPresence(env.UPDATES, presence.id);
          return json({ ok: true, kind: PRESENCE_KIND, new: registered.isNew, day: registered.day }, 200);
        } catch (err) {
          // R2 写入失败回 503：客户端据此保留本地心跳、下次冷启再试，不做即时重试风暴。
          return json({ error: "R2 写入暂时不可用，登记未入库，下次冷启再试" }, 503);
        }
      }
      // 从这里往下都要 KV：漏斗聚合、错误报告正文与索引。
      if (!env.REPORTS) {
        return json({ error: "REPORTS KV 未绑定：见 wrangler.toml" }, 500);
      }
      // 更新漏斗与错误报告共用本端点：带 kind 标签的 JSON 批在这里分流聚合，
      // 必须排在 MARKER 检查之前（漏斗 body 是 JSON，不含报告标记）。
      const funnel = parseFunnel(body);
      if (funnel) {
        const counted = await aggregateFunnel(env.REPORTS, funnel);
        return json({ ok: true, counted }, 200);
      }
      if (!body.includes(REPORT_MARKER)) return json({ error: "不是 eratw 错误报告" }, 400);
      // 正文键 = eratw/<ISO日期>/<毫秒>-<净化后的报告名>：字典序即时间序，list 直接翻最新。
      // 报告名只进键名、不进指纹：同一份文件重发时它是秒级避让过的，每次都不同。
      const rawName = request.headers.get("X-Eratw-Report-Name") || "";
      const safeName = rawName.replace(/[^\w.-]/g, "").slice(0, 80) || `report-${Date.now()}.txt`;
      const uploadMs = Date.now();
      const key = `${RAW_PREFIX}${new Date(uploadMs).toISOString().slice(0, 10)}/${uploadMs}-${safeName}`;

      const read = await readUploadReport(body);
      // 计数真相先落：不可变键，读改写与并发都动不到它。occ 写失败也要把正文存下去（不丢报告）。
      let existingIndex = null;
      try {
        await env.REPORTS.put(occurrenceKeyOf(read, uploadMs), OCC_VALUE);
        existingIndex = await env.REPORTS.get(INDEX_PREFIX + read.fp);
      } catch (err) {
        existingIndex = null;
      }
      if (existingIndex !== null) {
        // 重复上传：正文不再落第二份，只补标签 + 把 idx 计数 +1，然后照样回 204。
        // 索引读不出正文键（损坏）就退回新内容分支：宁可双份，不许丢。
        const counted = await countDuplicate(env, read).catch(() => false);
        if (counted) {
          await writeLabel(env, read).catch(() => {});
          return new Response(null, {
            status: 204,
            headers: {
              "X-Eratw-Fingerprint": read.fp.slice(0, CODE_CHARS),
              "X-Eratw-Duplicate": "1",
            },
          });
        }
      }
      try {
        await env.REPORTS.put(key, body);
      } catch (err) {
        // 正文没存下去就是**丢了这份报告**：回 5xx 让 App 侧留着文件下次补发，不许咽成 2xx。
        return json({ error: "KV 写入失败，报告未入库（客户端请重试）" }, 500);
      }
      await writeIndex(env, read, key).catch(() => {});
      await writeLabel(env, read).catch(() => {});
      return new Response(null, {
        status: 204,
        headers: {
          "X-Eratw-Fingerprint": read.fp.slice(0, CODE_CHARS),
          "X-Eratw-Duplicate": "0",
        },
      });
    }

    // ---- 查看面（GET，都要令牌） ------------------------------------------
    if (request.method === "GET") {
      if (readToken(request, url) !== env.REPORT_TOKEN) return json({ error: "token 不符" }, 403);
      if (!env.REPORTS) {
        return json({ error: "REPORTS KV 未绑定：见 wrangler.toml" }, 500);
      }

      if (url.pathname === "/reports") {
        // KV list 按字典序升序；为取「最新 LIST_LIMIT」，有游标就继续翻（上限 10 页，
        // 每页 1000 键 ⇒ 1 万条以内都能取到；超出时仍按字典序保留最后 1000 条）。
        // 去重版上线后这里只出**去重后的正文键**，条数不再等于上报数（上报数看 /stats）。
        const names = [];
        let cursor;
        for (let page = 0; page < 10; page++) {
          const list = await env.REPORTS.list({ prefix: RAW_PREFIX, cursor });
          for (const k of list.keys) names.push(k.name);
          if (list.list_complete) break;
          cursor = list.cursor;
        }
        const latest = names.slice(-LIST_LIMIT).reverse();
        return json({ count: latest.length, reports: latest });
      }

      // 只读聚合出口：列出 funnel/ 下所有「日/事件」键，按日聚成 { 日: { 事件: 计数 } }。
      // 与 /reports 同一套翻页口径（每页 1000、最多 10 页），令牌在 GET 分支入口已验。
      if (url.pathname === "/funnel-stats") {
        const keys = [];
        let cursor;
        for (let page = 0; page < 10; page++) {
          const list = await env.REPORTS.list({ prefix: FUNNEL_PREFIX, cursor });
          for (const k of list.keys) keys.push(k.name);
          if (list.list_complete) break;
          cursor = list.cursor;
        }
        const days = {};
        for (const key of keys) {
          const rest = key.slice(FUNNEL_PREFIX.length);
          const slash = rest.indexOf("/");
          if (slash < 0) continue;
          const day = rest.slice(0, slash);
          const name = rest.slice(slash + 1);
          const raw = await env.REPORTS.get(key);
          const n = Number.parseInt(raw || "0", 10) || 0;
          if (!days[day]) days[day] = {};
          days[day][name] = (days[day][name] || 0) + n;
        }
        return json({ kind: FUNNEL_KIND, days }, 200);
      }
      if (url.pathname === "/stats") return handleStats(env, url);
      if (url.pathname.startsWith("/sample/")) return handleSample(env, url);

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
          清单: "GET /reports?token=<值>（去重后的正文键；条数不等于上报数）",
          聚合: "GET /stats?token=<值>（签名簇 × 版本 × 机型数 + 无签名/退化桶，不拉正文）",
          样张: "GET /sample/<指纹 64 位>?token=<值>（按指纹取该内容的首份正文）",
          原文: "GET /report/<键名>?token=<值>",
          漏斗统计: "GET /funnel-stats?token=<值>",
          回填: "POST /reindex?token=<值>&limit=200[&cursor=<next_cursor>]（历史正文补进聚合索引，幂等）",
          更新: "GET /updates/<file>（公开只读，支持 Range）",
          玩家数: "GET /players（公开，shields.io endpoint 徽章；?metric=active 是今日活跃；存 R2，不占 KV 额度）",
          玩家数原始读数: "GET /players/raw（公开，累计 + 今日活跃 + 生成时刻；GitHub Actions 定时取数走这一条）",
        },
      });
    }

    if (request.method === "POST" && url.pathname === "/reindex") {
      if (readToken(request, url) !== env.REPORT_TOKEN) return json({ error: "token 不符" }, 403);
      if (!env.REPORTS) {
        return json({ error: "REPORTS KV 未绑定：见 wrangler.toml" }, 500);
      }
      return handleReindex(env, url);
    }

    return json({ error: "not found" }, 404);
  },
};
