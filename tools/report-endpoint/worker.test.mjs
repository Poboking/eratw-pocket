/**
 * tools/report-endpoint/worker.js 的本地对拍与行为自证（node，**不部署**）。
 *
 * 跑法（本机 HOMEJIA，仓库根 = eratw-android）：
 *
 *     node tools/report-endpoint/worker.test.mjs
 *
 * 为什么手写桩而不引 miniflare：本仓没有 node_modules，也不给这条 lane 引新依赖；
 * 被测行为只用 KV 的 get/put/list 三个面（含数组批量 get）与 R2 的 get/head/put/list，桩够真。
 * 两个桩的形状差异是本波的重点：KV list 回 keys/list_complete，R2 list 回 objects/truncated。
 *
 * 五件事分开自证：
 * 1. **指纹对拍**：fingerprint-fixtures.json 的 expect.fp 由 devtools/triage_reports.py 算，
 *    这里拿它去 GET /sample/<整串指纹> —— 只有 worker 算出同一个全串才可能命中，两侧口径一致才算绿；
 *    顺带比签名码 / 版本位 / 机型码 / 类型码 / 退化位（从 occ 键名与 /stats 回包里读）。
 * 2. **去重行为**：同一份内容重复 POST 不再各占一条正文、计数要长、无身份的退化报告不许跨时刻并。
 * 3. **聚合读数与不丢报告**：/stats 的账（occurrences = incidents + duplicate_uploads）、
 *    /reindex 幂等、KV 写正文失败时回 5xx（让 App 侧留文件重发）。
 * 4. **匿名玩家数**（第 8 节）：登记按 id 去重进 R2、坏 id 一律 400、徽章免令牌且回包里
 *    一个 id 都没有、一次徽章请求只花一次 R2 list、漏斗批不许被新分流吞掉；
 *    全程 KV 计数器不涨，证明统计面确实零 KV 占用。
 * 5. **额度与故障隔离**（第 9 节）：KV 绑定整个不在、KV 全抛错、R2 写/list 抛错，
 *    逐条证明 /updates 仍照常吐 APK；玩家登记失败回 503 可重试、徽章降级成短缓存 unknown。
 */

import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import worker from "./worker.js";

const HERE = dirname(fileURLToPath(import.meta.url));
const TOKEN = "test-token-never-printed";
const FIXTURES = JSON.parse(readFileSync(join(HERE, "fingerprint-fixtures.json"), "utf-8"));

let checks = 0;
let lastLabel = "(还没跑第一条)";

function expect(condition, label) {
  lastLabel = label;
  if (!condition) throw new Error(`worker.test 失败：${label}`);
  checks += 1;
}

/** 相邻两次上传必须落在不同毫秒上，否则同内容的 occ 键会撞（桩不带真时钟，测试自己隔开）。 */
const tick = () => new Promise((resolve) => setTimeout(resolve, 3));

/** KV 桩：键名排序即字典序；list 支持 prefix/limit/cursor；get 支持一次传数组（批量）。 */
function makeKv() {
  const store = new Map();
  let failBodyPut = false;
  // 把 KV 整个判死（get/put/list 全抛）：模拟免费档额度打满或绑定本身故障。
  // 玩家统计与更新两面都不该被它拖下水，第 9 节逐条钉。
  let failAll = false;
  // 批量 get 的真实形状不止一种（见 worker.js 的 readLabels），五形都能摆：
  // plain = {键: 字符串}（桩原本只有这一形，所以它从没抓到线上那枚 bug）；
  // wrapped = {键: {value, metadata}}；map = 真 workerd 给的那一形；entries = 条目数组；
  // throws = 批量形态整个不支持（这时才该走逐键补）。
  let bulkShape = "plain";
  // put/list 都计数：第 8 节要证「玩家登记与徽章读取一次 KV 都不碰」。
  const counters = { bulk: 0, single: 0, list: 0, put: 0 };
  return {
    store,
    counters,
    setFailBodyPut(flag) {
      failBodyPut = flag;
    },
    setFailAll(flag) {
      failAll = flag;
    },
    setBulkShape(shape) {
      if (!["plain", "wrapped", "throws", "map", "entries"].includes(shape)) {
        throw new Error(`桩：不认识的形式 ${shape}`);
      }
      bulkShape = shape;
    },
    async get(key) {
      if (failAll) throw new Error("桩：KV 整个挂掉或当日额度打满");
      if (Array.isArray(key)) {
        counters.bulk += 1;
        if (bulkShape === "throws") throw new Error("桩：批量 get 不支持");
        const out = {};
        for (const one of key) {
          if (!store.has(one)) continue;
          out[one] = bulkShape === "wrapped" ? { value: store.get(one), metadata: null } : store.get(one);
        }
        if (bulkShape === "map") return new Map(Object.entries(out));
        if (bulkShape === "entries") return Object.entries(out);
        return out;
      }
      if (String(key).startsWith("lbl/")) counters.single += 1;
      return store.has(key) ? store.get(key) : null;
    },
    async put(key, value, options) {
      counters.put += 1;
      if (failAll) throw new Error("桩：KV 整个挂掉或当日写额度打满");
      if (failBodyPut && key.startsWith("eratw/")) throw new Error("桩：KV 写正文失败");
      store.set(key, typeof value === "string" ? value : String(value));
    },
    async list({ prefix, limit, cursor } = {}) {
      counters.list += 1;
      if (failAll) throw new Error("桩：KV 整个挂掉或当日 list 额度打满");
      const names = [...store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
      const from = cursor === undefined ? 0 : Number(cursor);
      const size = limit === undefined ? 1000 : limit;
      const page = names.slice(from, from + size);
      const complete = from + size >= names.length;
      return {
        keys: page.map((name) => ({ name })),
        list_complete: complete,
        cursor: complete ? undefined : String(from + size),
      };
    },
  };
}

/**
 * R2 桩：head/put/get/list，形状照真 workerd 的 R2Bucket —— **list 回 objects/truncated/cursor**，
 * 与 KV 的 keys/list_complete/cursor 不同；这条差异正是本波 KV→R2 迁移要钉住的地方。
 * store 里同时放 demo.apk（更新产物）与 player/ active/（统计内部对象）：同桶、不同前缀。
 */
function makeR2() {
  const store = new Map([["demo.apk", { body: "0123456789", size: 10 }]]);
  const counters = { head: 0, put: 0, list: 0, get: 0 };
  const putCalls = [];
  let failPut = false;
  let failList = false;
  return {
    store,
    counters,
    putCalls,
    setFailPut(flag) { failPut = flag; },
    setFailList(flag) { failList = flag; },
    async head(key) {
      counters.head += 1;
      const obj = store.get(key);
      return obj ? { key, size: obj.size } : null;
    },
    async put(key, value) {
      counters.put += 1;
      putCalls.push(key);
      if (failPut) throw new Error("桩：R2 写入失败");
      const body = typeof value === "string" ? value : String(value);
      store.set(key, { body, size: body.length });
      return { key, size: body.length };
    },
    async get(key, opts) {
      counters.get += 1;
      const obj = store.get(key);
      if (!obj) return null;
      const offset = opts?.range?.offset ?? 0;
      if (offset >= obj.size) throw new Error("桩：Range 起点越界");
      const length = opts?.range?.length ?? obj.size - offset;
      return {
        body: obj.body.slice(offset, offset + length),
        size: obj.size,
        range: { offset, length, size: obj.size },
      };
    },
    async list({ prefix, limit, cursor } = {}) {
      counters.list += 1;
      if (failList) throw new Error("桩：R2 list 失败");
      const names = [...store.keys()].filter((k) => !prefix || k.startsWith(prefix)).sort();
      const from = cursor === undefined ? 0 : Number(cursor);
      const size = limit === undefined ? 1000 : limit;
      const page = names.slice(from, from + size);
      const truncated = from + size < names.length;
      return {
        objects: page.map((name) => ({ key: name, size: store.get(name).size })),
        truncated,
        cursor: truncated ? String(from + size) : undefined,
        delimitedPrefixes: [],
      };
    },
  };
}

function makeEnv(kv, bucket = makeR2()) {
  return {
    REPORT_TOKEN: TOKEN,
    REPORTS: kv,
    UPDATES: bucket,
    __r2: bucket,
  };
}

async function postReport(kv, body, name, bucket) {
  await tick();
  const request = new Request("https://eratw.example/report", {
    method: "POST",
    headers: {
      "X-Eratw-Token": TOKEN,
      "X-Eratw-Report-Name": name || "eratw-report-20261104-000000.txt",
    },
    body,
  });
  return worker.fetch(request, makeEnv(kv, bucket), {});
}

async function getRaw(kv, path, bucket) {
  const response = await worker.fetch(new Request(`https://eratw.example${path}`), makeEnv(kv, bucket), {});
  const text = await response.text();
  return { response, text, json: text.startsWith("{") ? JSON.parse(text) : null };
}

function bodyOf(caseName) {
  const item = FIXTURES.cases.find((c) => c.name === caseName);
  if (!item) throw new Error(`夹具里没有 ${caseName}`);
  return item;
}

const rawKeys = (kv) => [...kv.store.keys()].filter((k) => k.startsWith("eratw/"));
const occKeys = (kv) => [...kv.store.keys()].filter((k) => k.startsWith("occ/"));
const idxKeys = (kv) => [...kv.store.keys()].filter((k) => k.startsWith("idx/"));
const labelKeys = (kv) => [...kv.store.keys()].filter((k) => k.startsWith("lbl/"));

/** occ 键尾段：毫秒-类型码-退化位-版本位-机型码-签名码（键形是 occ/<指纹>/<尾段>）。 */
function occTail(key) {
  const tail = key.split("/")[2].split("-");
  return {
    ms: tail[0],
    kindCode: tail[1],
    degraded: tail[2],
    versionSlug: tail[3],
    deviceCode: tail[4],
    sigCode: tail[5],
  };
}

async function main() {
  // ---- 1. 鉴权与验真：老行为不许变（App 侧只认 2xx，其它码会引发补发风暴）----
  const gate = makeKv();
  const badToken = await worker.fetch(
    new Request("https://eratw.example/report", {
      method: "POST",
      headers: { "X-Eratw-Token": "nope" },
      body: "x",
    }),
    makeEnv(gate),
    {}
  );
  expect(badToken.status === 403, "令牌不符回 403");
  expect(rawKeys(gate).length === 0, "鉴权失败不写入");
  const noMarker = await postReport(gate, "随便一段文本，不是报告", "eratw-report-x.txt");
  expect(noMarker.status === 400 && rawKeys(gate).length === 0, "没有报告标记的一律拒收（验真标记）");
  const emptyBody = await postReport(gate, "", "eratw-report-x.txt");
  expect(emptyBody.status === 400, "空 body 拒收");

  // ---- 2. 指纹对拍 + 首传/重复判定：按夹具顺序全量灌一遍 ----
  const kv = makeKv();
  const seenFp = new Set();
  const primaryBody = new Map();
  for (const item of FIXTURES.cases) {
    const response = await postReport(kv, item.body, `eratw-${item.name}.txt`);
    const firstTime = !seenFp.has(item.expect.fp);
    expect(response.status === 204, `${item.name} 上传回 204`);
    expect(response.headers.get("X-Eratw-Duplicate") === (firstTime ? "0" : "1"),
      `${item.name} 的重复判定与 Python 侧指纹分组一致`);
    expect(response.headers.get("X-Eratw-Fingerprint") === item.expect.fp.slice(0, 8),
      `${item.name} 指纹前 8 位与 Python 侧一致`);
    if (firstTime) {
      seenFp.add(item.expect.fp);
      primaryBody.set(item.expect.fp, item.body);
    }
    const sample = await getRaw(kv, `/sample/${item.expect.fp}?token=${TOKEN}`);
    expect(sample.response.status === 200, `${item.name} 整串 fp 能取回正文（两侧 sha256 同值）`);
    expect(sample.text === primaryBody.get(item.expect.fp),
      `${item.name} 样张 = 该指纹的首份正文（变体不再各存一份）`);
  }
  const totalCases = FIXTURES.cases.length;
  const uniqueFp = seenFp.size;
  expect(occKeys(kv).length === totalCases, "每次上传各留一条 occ 计数");
  expect(rawKeys(kv).length === uniqueFp, `正文只 ${uniqueFp} 份（夹具 ${totalCases} 份，重复的没占新键）`);
  expect(idxKeys(kv).length === uniqueFp, "idx 与正文一一对应");

  // 标签取的形状自证（2026-11-05 线上事故）：回填 676 份后 `GET /stats` 的 51 簇 label 全 null，
  // 而 `wrangler kv key list --prefix lbl/` 数出 45 把键在。上一段 183 项为什么没抓到它：
  // 桩的批量 get 只会回 `{键: 字符串}` 这一形，而真运行时给的形状不止一种。
  // 三形（plain / wrapped / throws）都必须读得出簇标签，读不出＝判读面瞎。
  async function expectLabelShape(mode) {
    const shapedKv = makeKv();
    shapedKv.setBulkShape(mode);
    await postReport(shapedKv, bodyOf("step-limit-legacy").body, `eratw-report-20261104-shape-${mode}.txt`);
    // 只量 /stats 这一发：POST 时 writeLabel 本来就要单键 get 一次「这把标签在不在」。
    shapedKv.counters.single = 0;
    const shapeStats = await getRaw(shapedKv, `/stats?token=${TOKEN}`);
    expect(shapeStats.response.status === 200, `${mode} 形下 /stats 仍要 200`);
    expect(
      !!shapeStats.json.signatures.find((s) => s.label && s.label.script === "HSV_TO_RGB 行166"),
      `批量 get 的 ${mode} 形也要读得出簇标签（读不出＝/stats 等于没有这张表）`,
    );
    expect(shapeStats.json.signatures.length === 1, `${mode} 形下簇数与签名码不许漂`);
    // 认得批量那一形时就不该再逐键补（那是子请求额度里的白烧）；只有一形都不认时才补。
    if (mode === "throws") {
      expect(shapedKv.counters.single >= 1, `${mode} 形下必须退到逐键补，否则整表没有标签`);
    } else {
      expect(shapedKv.counters.single === 0, `${mode} 形下批量那一发就该命中，不许逐键补`);
    }
  }
  await expectLabelShape("plain");
  await expectLabelShape("wrapped");
  await expectLabelShape("map");
  await expectLabelShape("entries");
  await expectLabelShape("throws");

  // 真 workerd 的批量 get 回的是 Map（2026-11-05 线上探针实测：bulk_is_map: true、size: 51、
  // Object.keys() 为空）。这一形单独钉一遍，别被 `{键: 值}` 那一形掩盖过去。
  const mapKv = makeKv();
  mapKv.setBulkShape("map");
  await postReport(mapKv, bodyOf("step-limit-legacy").body, "eratw-report-20261104-workerd-map.txt");
  const mapStats = await getRaw(mapKv, `/stats?token=${TOKEN}`);
  expect(mapStats.json.signatures[0].label.script === "HSV_TO_RGB 行166", "Map 形（线上那一形）标签读得出");
  expect(mapKv.counters.bulk === 1, "Map 形只烧一发批量子请求");

  // 真没有 lbl 键时（旧正文没写标签）：label 如实为 null，不许编造，也不许把 /stats 打成错。
  const noLabelKv = makeKv();
  noLabelKv.setBulkShape("wrapped");
  await postReport(noLabelKv, bodyOf("step-limit-legacy").body, "eratw-report-20261104-nolabel.txt");
  for (const stale of [...noLabelKv.store.keys()]) if (stale.startsWith("lbl/")) noLabelKv.store.delete(stale);
  const noLabelStats = await getRaw(noLabelKv, `/stats?token=${TOKEN}`);
  expect(noLabelStats.response.status === 200, "没有 lbl 键时 /stats 仍要 200");
  expect(
    noLabelStats.json.signatures.every((s) => s.label === null),
    "没有 lbl 键 ⇒ 簇标签如实为 null（逐键补取也不许无中生有）",
  );
  expect(noLabelStats.json.signatures.length === 1, "没有 lbl 键不影响簇数");

  // 夹具声明的每一对（同 fp / 不同 fp）必须与端点算出来的一致
  for (const pair of FIXTURES.pairs) {
    const a = bodyOf(pair.a).expect;
    const b = bodyOf(pair.b).expect;
    expect((a.fp === b.fp) === pair.expect_same_fp, `夹具 pairs：${pair.a} vs ${pair.b} —— ${pair.why}`);
  }

  // occ 键名里的聚合维度必须与 Python 侧同一支（签名码 / 版本位 / 机型码 / 类型码 / 退化位）
  for (const item of FIXTURES.cases) {
    const key = occKeys(kv).find((k) => k.startsWith(`occ/${item.expect.fp}/`));
    expect(!!key, `${item.name} 有 occ 键`);
    const tail = occTail(key);
    expect(tail.sigCode === item.expect.sig_code, `${item.name} 签名码同值`);
    expect(tail.versionSlug === item.expect.version_slug, `${item.name} 版本位同值（取不到=q）`);
    expect(tail.deviceCode === item.expect.device_code, `${item.name} 机型码同值`);
    expect(tail.kindCode === item.expect.kind_code, `${item.name} 类型码同值`);
    expect(tail.degraded === (item.expect.degraded ? "1" : "0"), `${item.name} 退化位同值`);
  }

  // ---- 3. 去重行为：同一份内容重复 POST 不再各占一条 KV ----
  const legacy = bodyOf("step-limit-legacy");
  const dupKv = makeKv();
  const first = await postReport(dupKv, legacy.body, "eratw-report-20261104-092959.txt");
  expect(first.status === 204 && first.headers.get("X-Eratw-Duplicate") === "0", "首传入库");
  expect(rawKeys(dupKv).length === 1 && occKeys(dupKv).length === 1, "首传各占一条正文与计数");
  const retryName = await postReport(dupKv, legacy.body, "eratw-report-20261104-093001.txt");
  expect(retryName.status === 204, "重复上传仍回 204（拒收会让 App 无限补发）");
  expect(retryName.headers.get("X-Eratw-Duplicate") === "1", "重复上传标 X-Eratw-Duplicate=1");
  expect(rawKeys(dupKv).length === 1, "重复上传没再多占一条正文");
  expect(occKeys(dupKv).length === 2, "重复上传多一条 occ 计数");
  const crlfAgain = await postReport(dupKv, bodyOf("step-limit-crlf").body, "eratw-report-20261104-093002.txt");
  expect(crlfAgain.headers.get("X-Eratw-Duplicate") === "1", "只差折行的重发并成同一条内容");
  const shiftedAgain = await postReport(dupKv, bodyOf("step-limit-time-shifted").body, "eratw-report-20261104-093003.txt");
  expect(shiftedAgain.headers.get("X-Eratw-Duplicate") === "1", "有身份 + 只差报告时间 ⇒ 判为重复");
  const dupStats = (await getRaw(dupKv, `/stats?token=${TOKEN}`)).json;
  expect(dupStats.occurrences === 4 && dupStats.incidents === 1 && dupStats.duplicate_uploads === 3,
    "dupKv 的账：4 次上传 = 1 起事故 + 3 次重复");
  expect(JSON.parse(dupKv.store.get(`idx/${legacy.expect.fp}`)).occurrences === 4,
    "idx 的条数跟着上传数走（读改写的尽力计数，真相在 occ）");

  // 无身份的最小退化报告：换个时间必须算两条（宁可留双份）
  const noMsgA = bodyOf("minimal-degraded-no-message");
  const noMsgB = bodyOf("minimal-no-message-time-shifted");
  expect(noMsgA.expect.fp !== noMsgB.expect.fp, "无身份时报告时间是盐 ⇒ 两份不同指纹");
  const degKv = makeKv();
  const degA = await postReport(degKv, noMsgA.body, "eratw-report-20261103-130512.txt");
  const degB = await postReport(degKv, noMsgB.body, "eratw-report-20261103-130513.txt");
  expect(degA.headers.get("X-Eratw-Duplicate") === "0" && degB.headers.get("X-Eratw-Duplicate") === "0",
    "两份不同时刻的无身份退化报告各算一起（不许并）");
  expect(rawKeys(degKv).length === 2, "宁可留双份：退化报告各存一份正文");
  const degSameAgain = await postReport(degKv, noMsgA.body, "eratw-report-20261103-130512-b.txt");
  expect(degSameAgain.headers.get("X-Eratw-Duplicate") === "1",
    "同一份退化报告逐字节重发仍然合并（秒级避让的文件名不进指纹）");

  // ---- 4. /stats 聚合读数：不必把 617 份全拉下来也能出这张表 ----
  const stats = (await getRaw(kv, `/stats?token=${TOKEN}`)).json;
  expect(stats.occurrences === totalCases, "/stats occurrences = occ 键数");
  expect(stats.incidents === uniqueFp && stats.incidents === rawKeys(kv).length, "/stats incidents = 正文条数");
  expect(stats.duplicate_uploads === stats.occurrences - stats.incidents,
    "occurrences = incidents + duplicate_uploads");
  expect(stats.listing_truncated === false, "未触到翻页上限时不许谎报截断");
  expect(stats.signatures_truncated === false, "簇数没超上限时不许谎报截断");

  const codeee = stats.signatures.find((item) => item.sig === legacy.expect.sig_code);
  expect(!!codeee, "签名簇表里有 step-limit 那一簇（签名码与 Python 侧同值）");
  expect(codeee.occurrences === 3, "这一簇 3 次上传（首传 + 只差时间 + 换折行）");
  expect(codeee.incidents === 1, "这一簇去重后 1 起事故");
  expect(codeee.devices === 1, "这一簇 1 台机（夹具里同一台 vivo）");
  expect(codeee.versions.length === 1 && codeee.versions[0].version === "0.2.3"
    && codeee.versions[0].occurrences === 3 && codeee.versions[0].devices === 1,
    "签名簇 × 版本 × 机型数交叉读数");
  expect(codeee.label && codeee.label.message.includes("执行时间超过上限(N")
    && codeee.label.script === "HSV_TO_RGB 行166", "簇标签：数字抹 N、行号保留");
  expect(codeee.label.message_raw.includes("141685ms"), "簇标签另存未归一的原文一枚（判读侧能核对数字）");
  const modernSig = stats.signatures.find((item) => item.sig === bodyOf("native-method-modern").expect.sig_code);
  expect(!!modernSig && modernSig.incidents === 1 && modernSig.devices === 1,
    "0.2.39 那一簇单独一条：1 次上传 / 1 起事故 / 1 台机");
  expect(stats.signatures_total === 2, "夹具里只有两个签名簇，其余全在无签名桶");

  const noSig = stats.no_signature;
  expect(noSig.occurrences === 6 && noSig.incidents === 5,
    "无签名桶：静默退出（2 次上传/1 起）+ 退化 3 + 探针 1，重复的不双计");
  expect(noSig.degraded_occurrences === 3, "无签名桶里如实标出退化份数（本轮真实 3 份的形状）");
  expect(noSig.devices === 2, "无签名桶的机型数：退化与探针没有设备行 ⇒ 只数得出 2 台机");
  const unknownVersion = stats.versions.find((item) => item.version === "?");
  expect(!!unknownVersion && unknownVersion.occurrences === 4 && unknownVersion.incidents === 4,
    "ver=? 桶在版本表里单列（退化 3 份 + 探针 1 份）");
  expect(unknownVersion.signatures === 0, "ver=? 里一个签名簇都不许算");
  expect(unknownVersion.no_signature_occurrences === 4, "ver=? 的份全部计入无签名");
  const modernVersion = stats.versions.find((item) => item.version === "0.2.39");
  expect(modernVersion.occurrences === 3 && modernVersion.signatures === 1
    && modernVersion.no_signature_occurrences === 2,
    "0.2.39 一行：3 次上传 / 1 个签名簇 / 2 次上传读不出签名（静默退出的两次都算条数）");
  expect(stats.signatures.every((item) => item.occurrences >= item.incidents), "每簇的上传数不许小于事故数");

  // ---- 4b. /stats 时间窗（DBG-TOOLING 波）：?windowDays=N 只算窗口内的 occ；缺省响应不变 ----
  const wkv = makeKv();
  await postReport(wkv, bodyOf("step-limit-legacy").body, "eratw-report-20261117-window-a.txt");
  await postReport(wkv, bodyOf("step-limit-legacy").body, "eratw-report-20261117-window-b.txt");
  // 窗外旧键：手写 occ 键（毫秒 = 200 天前；fp 段按解析口径不校验十六进制，用真形指纹）
  const oldMs = Date.now() - 200 * 24 * 60 * 60 * 1000;
  wkv.store.set(`occ/${"f".repeat(64)}/${oldMs}-c-0-v9-dev9-sig1`, "1");
  const noWin = (await getRaw(wkv, `/stats?token=${TOKEN}`)).json;
  expect(noWin.window === undefined, "不带 windowDays 时不出 window 段（响应逐键不变，向后兼容）");
  expect(noWin.occurrences === 3, "主表口径不受窗口参数影响（全量 3 条 occ）");
  const win = (await getRaw(wkv, `/stats?token=${TOKEN}&windowDays=7`)).json;
  expect(!!win.window && win.window.days === 7, "windowDays=7 出 window 段");
  expect(win.window.occurrences === 2 && win.window.incidents === 1,
    "窗口只算窗内 occ：两次 POST（同内容去重 1 起）+ 旧键不进窗");
  expect(win.window.kinds.length === 1 && win.window.kinds[0].kind_code !== "c",
    "窗内 kinds 只有脚本族（崩溃族旧键被切在窗外）");
  expect(win.window.signatures.length === 1 && win.window.signatures[0].occurrences === 2,
    "窗内签名表只含无签名过滤后的窗内簇");
  for (const bad of ["0", "91", "abc", "7.5"]) {
    const badRes = await getRaw(wkv, `/stats?token=${TOKEN}&windowDays=${bad}`);
    expect(badRes.response.status === 400, `windowDays=${bad} 必须 400（不给错表）`);
  }

  // ---- 5. /reports 与 /reindex ----
  const listing = (await getRaw(kv, `/reports?token=${TOKEN}`)).json;
  expect(listing.count === rawKeys(kv).length, "/reports 只出正文键，条数 = 去重后事故数");
  expect(listing.reports.every((k) => k.startsWith("eratw/")), "/reports 不漏 occ/idx/lbl 内部键");
  expect(listing.reports[0] >= listing.reports[listing.reports.length - 1] || listing.reports.length === 1,
    "/reports 仍按字典序倒出（最新在前）");

  // 历史回填：另开一个桩，塞两条「去重版上线前」的键（一条真报告、一条没标记的杂项）
  const legacyKey = "eratw/2026-11-02/1793594714034-eratw-report-20261102-124513.txt";
  const silent = bodyOf("silent-exit-trail");
  const backfillKv = makeKv();
  await backfillKv.put(legacyKey, silent.body);
  await backfillKv.put("eratw/2026-11-02/1793594714999-blob.txt", "这段没有报告标记 ⇒ 回填要如实跳过");
  const reindexResponse = await worker.fetch(
    new Request(`https://eratw.example/reindex?token=${TOKEN}&limit=500`, { method: "POST" }),
    makeEnv(backfillKv),
    {}
  );
  const reindexJson = await reindexResponse.json();
  expect(reindexResponse.status === 200 && reindexJson.scanned === 2, "/reindex 按 limit 扫正文键");
  expect(reindexJson.indexed === 1 && reindexJson.skipped === 1, "回填：有标记的入库、没标记的如实跳过");
  expect(occKeys(backfillKv).length === 1, "回填写出 1 条 occ");
  expect(rawKeys(backfillKv).length === 2, "回填不新增正文（历史那条本来就在，杂项也不算正文）");
  expect(occTail(occKeys(backfillKv)[0]).ms === "1793594714034",
    "回填的毫秒取自已有正文键名（不是 Date.now ⇒ 重跑幂等）");
  await (await worker.fetch(
    new Request(`https://eratw.example/reindex?token=${TOKEN}&limit=500`, { method: "POST" }),
    makeEnv(backfillKv),
    {}
  )).json();
  expect(occKeys(backfillKv).length === 1, "/reindex 幂等：重跑同一份正文不新增 occ");
  const backfillStats = (await getRaw(backfillKv, `/stats?token=${TOKEN}`)).json;
  expect(backfillStats.incidents === 1 && backfillStats.no_signature.incidents === 1,
    "回填的那份静默退出进无签名桶（ver=0.2.39、带轨迹）");
  expect(backfillStats.signatures.length === 0, "静默退出没有签名 ⇒ 签名表如实为空");
  expect(backfillStats.no_signature.degraded_occurrences === 0, "带轨迹的静默退出不算退化报告");
  const backfillListing = (await getRaw(backfillKv, `/reports?token=${TOKEN}`)).json;
  expect(backfillListing.reports.includes(legacyKey), "回填后仍能按原正文键取历史原文");

  const missing = await getRaw(kv, `/sample/${"f".repeat(64)}?token=${TOKEN}`);
  expect(missing.response.status === 404, "指纹没有索引时 /sample 回 404（不许假 200）");
  const badFp = await getRaw(kv, `/sample/not-a-fp?token=${TOKEN}`);
  expect(badFp.response.status === 400, "非法指纹回 400");
  expect(labelKeys(kv).length === 2, "lbl 每个签名簇一条（两簇 ⇒ 两条，不随上传数涨）");

  // ---- 6. 不丢报告：正文写不进去就是 5xx，让 App 留着文件重发 ----
  const failing = makeKv();
  failing.setFailBodyPut(true);
  const lostGuard = await postReport(failing, bodyOf("native-method-modern").body, "eratw-report-20261104-100000.txt");
  expect(lostGuard.status === 500, "KV 写正文失败必须回 5xx（咽成 2xx 就是丢报告）");
  const half = makeKv();
  await postReport(half, bodyOf("native-method-modern").body, "eratw-report-a.txt");
  half.setFailBodyPut(true);
  const probeFail = await postReport(half, bodyOf("probe").body, "eratw-report-b.txt");
  expect(probeFail.status === 500, "新内容的正文写不进也回 5xx（occ 已留痕，重发不双计）");
  expect(occKeys(half).length === 2 && rawKeys(half).length === 1, "失败时 occ 留痕、正文不脏写");

  // ---- 7. 更新面不许被我动到 ----
  const updates = makeEnv(makeKv());
  const full = await worker.fetch(new Request("https://eratw.example/updates/demo.apk"), updates, {});
  expect(full.status === 200 && (await full.text()) === "0123456789", "更新面全量仍 200");
  const ranged = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk", { headers: { Range: "bytes=4-6" } }),
    updates, {}
  );
  expect(ranged.status === 206 && (await ranged.text()) === "456", "更新面 Range 仍 206");
  const beyond = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk", { headers: { Range: "bytes=99-" } }),
    updates, {}
  );
  expect(beyond.status === 416, "更新面越界仍 416");
  // 统计内部对象与 APK 同桶，但不能顺着 /updates/ 被翻出来。
  const internalPlayer = await worker.fetch(
    new Request(`https://eratw.example/updates/player/${"a".repeat(64)}`), makeEnv(makeKv()), {},
  );
  expect(internalPlayer.status === 404, "统计内部 player/ 前缀不许被当更新产物下载");
  const internalActive = await worker.fetch(
    new Request("https://eratw.example/updates/active/2026-11-08/" + "a".repeat(64)), makeEnv(makeKv()), {},
  );
  expect(internalActive.status === 404, "统计内部 active/ 前缀同样拒收");
  const usage = await getRaw(makeKv(), `/?token=${TOKEN}`);
  expect(usage.json.usage["聚合"].includes("/stats"), "首页 usage 把新面写出来（判读侧找得到入口）");
  expect(usage.json.usage["玩家数"].includes("R2"), "首页 usage 写明玩家数存 R2、不占 KV 额度");

  // ---- 8. 匿名玩家数：登记去重 / 徽章公开 / 今日活跃（2026-11-08 波，存储面 = R2）----
  const pkv = makeKv();
  const p2 = makeR2();
  const idA = "a".repeat(64);
  const idB = "b".repeat(64);
  const presenceBody = (id) => JSON.stringify({ kind: "player-presence", id });
  const playerKeys = (bucket) => [...bucket.store.keys()].filter((k) => k.startsWith("player/"));
  const activeKeys = (bucket) => [...bucket.store.keys()].filter((k) => k.startsWith("active/"));
  const today = new Date().toISOString().slice(0, 10);

  const firstPing = await postReport(pkv, presenceBody(idA), "presence-a.txt", p2);
  const firstPingJson = await firstPing.json();
  expect(firstPing.status === 200 && firstPingJson.new === true && firstPingJson.day === today,
    "首次匿名登记回 200、标 new、日界取服务端 UTC");
  expect(playerKeys(p2).length === 1, "首次登记只落一个 player/ 对象");
  expect(p2.store.get(`player/${idA}`).body === today, "累积对象的值只有首次见到的 UTC 日");

  const dupPing = await postReport(pkv, presenceBody(idA), "presence-a-retry.txt", p2);
  const dupJson = await dupPing.json();
  expect(dupPing.status === 200 && dupJson.new === false, "同 id 重发仍回 2xx（App 才不会无限补发）");
  expect(playerKeys(p2).length === 1, "重发不新增玩家（不可变键去重，不是读改写计数器）");
  expect(activeKeys(p2).length === 1, "同一天的活跃心跳也只占一个对象");

  const secondPing = await postReport(pkv, presenceBody(idB), "presence-b.txt", p2);
  expect(secondPing.status === 200 && (await secondPing.json()).new === true, "第二个 id 是另一个玩家");
  expect(playerKeys(p2).length === 2 && activeKeys(p2).length === 2,
    "两个玩家 = 两条累积对象 + 两条今日对象");

  expect(activeKeys(p2).every((k) => k.startsWith(`active/${today}/`)),
    "活跃键按 UTC 日前缀隔离（R2 没有 KV 的 expirationTtl，不靠过期收尸）");
  expect(p2.putCalls.every((k) => k.startsWith("player/") || k.startsWith("active/")),
    "登记只写统计内部两个前缀，不碰 demo.apk 等更新产物");

  // 零 KV 占用：另起一组干净计数器，登记完必须还是全零。
  const zeroKv = makeKv();
  const zeroR2 = makeR2();
  await postReport(zeroKv, presenceBody("d".repeat(64)), "presence-zero-kv.txt", zeroR2);
  expect(zeroKv.counters.put === 0 && zeroKv.counters.list === 0 &&
    zeroKv.counters.bulk === 0 && zeroKv.counters.single === 0,
  "玩家登记一次 KV 都没碰（写/list/批量读/单键读全为零，额度留给报告与漏斗）");
  expect(zeroR2.counters.head === 1 && zeroR2.counters.put === 2,
    "登记走 R2：一次 head 判新 + 两条 put（累积 + 今日）");

  for (const bad of ["A".repeat(64), "a".repeat(63), "../evil", "zz"]) {
    const before = playerKeys(p2).length;
    const rejected = await postReport(pkv, presenceBody(bad), "presence-bad.txt", p2);
    expect(rejected.status === 400 && playerKeys(p2).length === before,
      `坏 id（${bad.slice(0, 8)}…）一律 400 且不入库`);
  }
  const extra = await postReport(
    pkv,
    JSON.stringify({ kind: "player-presence", id: idA, ip: "1.2.3.4", ua: "probe" }),
    "presence-extra.txt",
    p2,
  );
  expect(extra.status === 200 &&
    ![...p2.store.keys()].some((k) => k.includes("1.2.3.4") || k.includes("probe")),
  "多带的字段不进任何对象键（服务端不存 IP / UA）");

  // 新分流不许把漏斗批吞掉，也不许把错误报告当登记。漏斗仍写 KV，与统计面井水不犯河水。
  const funnelPing = await postReport(
    pkv,
    JSON.stringify({ kind: "update-funnel", events: [{ event: "check_started", at: Date.now() }] }),
    "funnel-after-presence.txt",
    p2,
  );
  const funnelJson = await funnelPing.json();
  expect(funnelPing.status === 200 && funnelJson.counted === 1 &&
    [...pkv.store.keys()].some((k) => k.startsWith("funnel/")),
  "加了登记分流后漏斗批仍然照常聚合（KV 那一面没被动过）");

  // 徽章：免令牌、只回总数、一个 id 都不出网；且读的是 R2 不是 KV。
  p2.counters.list = 0;
  pkv.counters.list = 0;
  const badge = await getRaw(pkv, "/players", p2);
  expect(badge.response.status === 200, "GET /players 免令牌（shields.io / GitHub camo 拉得到）");
  expect(badge.json.schemaVersion === 1 && badge.json.label === "players" &&
    badge.json.message === "2", "徽章 JSON 是 shields endpoint 形状，message 是累计玩家数");
  expect(badge.json.color === "brightgreen" && badge.json.cacheSeconds === 300,
    "徽章带颜色与 300 秒缓存");
  expect(!JSON.stringify(badge.json).includes(idA) && !JSON.stringify(badge.json).includes(idB),
    "徽章回包里没有任何单个匿名 id");
  expect(badge.response.headers.get("cache-control").includes("max-age=300") &&
    badge.response.headers.get("access-control-allow-origin") === "*",
  "徽章带 Cache-Control 与 CORS（GitHub 图片代理可缓存）");
  expect(p2.counters.list === 1, "一次徽章请求只列一个 R2 前缀（Class B 一发）");
  expect(pkv.counters.list === 0, "徽章读取零 KV list（免费档 1000 次/天 全留给判读面）");

  const activeBadge = await getRaw(pkv, "/players?metric=active", p2);
  expect(activeBadge.json.label === "active today" && activeBadge.json.message === "2",
    "第二颗徽章出今日活跃");
  const raw = await getRaw(pkv, "/players/raw", p2);
  expect(raw.json.players === 2 && raw.json.active_today === 2 && raw.json.day === today &&
    raw.json.truncated === false, "/players/raw 原始读数齐全且不截断");
  const head = await worker.fetch(
    new Request("https://eratw.example/players", { method: "HEAD" }), makeEnv(pkv, p2), {},
  );
  expect(head.status === 200 && (await head.text()) === "", "HEAD 不带 body 仍 200");
  const noPost = await worker.fetch(
    new Request("https://eratw.example/players", { method: "POST" }), makeEnv(pkv, p2), {},
  );
  expect(noPost.status === 405, "统计面只读，POST 回 405");
  const options = await worker.fetch(
    new Request("https://eratw.example/players", { method: "OPTIONS" }), makeEnv(pkv, p2), {},
  );
  expect(options.status === 204, "统计面 CORS 预检 204");
  const secretlessEnv = makeEnv(pkv, p2);
  delete secretlessEnv.REPORT_TOKEN;
  const secretless = await worker.fetch(
    new Request("https://eratw.example/players"), secretlessEnv, {},
  );
  expect(secretless.status === 200, "统计面不依赖 REPORT_TOKEN（读面与判读面解耦）");

  // ---- 9. 额度与故障隔离：KV 挂掉 / R2 挂掉，都不许拖累玩家更新（用户追问）----
  // 把两个存储面分别往最坏里摆：KV 绑定整个没有、KV 全抛错、R2 写/list 抛错。
  // 结论要逐条钉住：更新面只读 R2 且排在令牌/KV 检查之前；统计面也只读 R2；
  // 只有错误报告与漏斗该受 KV 故障影响，且如实回 5xx 让客户端留文件重发。
  const noKvEnv = makeEnv(makeKv(), makeR2());
  delete noKvEnv.REPORTS;
  const updateWithoutKv = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk"), noKvEnv, {},
  );
  expect(updateWithoutKv.status === 200 && (await updateWithoutKv.text()) === "0123456789",
    "REPORTS 绑定整个不存在时，/updates 仍把 APK 完整吐出来（更新面一行 KV 都不碰）");
  const noTokenNoKv = makeEnv(makeKv(), makeR2());
  delete noTokenNoKv.REPORTS;
  delete noTokenNoKv.REPORT_TOKEN;
  const updateWithoutEither = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk"), noTokenNoKv, {},
  );
  expect(updateWithoutEither.status === 200,
    "KV 与令牌双双缺席时更新面仍 200（它排在两个检查之前就返回了）");

  // 绑定整个缺席时，登记与徽章照常 —— 这是「统计面零 KV 调用」最强的证明。
  const kvlessEnv = makeEnv(makeKv(), makeR2());
  delete kvlessEnv.REPORTS;
  const kvlessPing = await worker.fetch(
    new Request("https://eratw.example/report", {
      method: "POST",
      headers: { "X-Eratw-Token": TOKEN },
      body: presenceBody("e".repeat(64)),
    }),
    kvlessEnv, {},
  );
  expect(kvlessPing.status === 200 && (await kvlessPing.json()).new === true,
    "REPORTS 绑定整个不在时玩家登记仍成功（统计面确实不依赖 KV）");
  const kvlessBadge = await worker.fetch(new Request("https://eratw.example/players"), kvlessEnv, {});
  expect(kvlessBadge.status === 200 && (await kvlessBadge.json()).message === "1",
    "REPORTS 绑定整个不在时徽章仍读得出真实数");

  // KV 全抛错（免费档额度打满 / 绑定故障）：登记、徽章、更新三面都不该被牵连。
  const deadKv = makeKv();
  const liveR2 = makeR2();
  deadKv.setFailAll(true);
  const deadPing = await postReport(deadKv, presenceBody("f".repeat(64)), "presence-dead-kv.txt", liveR2);
  expect(deadPing.status === 200, "KV 全抛错时玩家登记仍 200");
  const deadBadge = await getRaw(deadKv, "/players", liveR2);
  expect(deadBadge.response.status === 200 && deadBadge.json.message === "1",
    "KV 全抛错时徽章仍出真实数");
  const deadUpdate = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk"), makeEnv(deadKv, liveR2), {},
  );
  expect(deadUpdate.status === 200, "KV 全抛错时 /updates 仍 200");
  const deadReport = await postReport(deadKv, bodyOf("probe").body, "report-dead-kv.txt", liveR2);
  expect(deadReport.status === 500, "KV 全抛错时错误报告如实回 5xx（该失败的地方不许咽成 2xx）");

  // R2 写失败：登记回 503 且不留半键，但 KV 那一面（错误报告）与更新读面照常。
  const quotaKv = makeKv();
  const quotaR2 = makeR2();
  const idC = "c".repeat(64);
  quotaR2.setFailPut(true);
  const rejectedPing = await postReport(quotaKv, presenceBody(idC), "presence-quota.txt", quotaR2);
  expect(rejectedPing.status === 503,
    "R2 写失败时登记回 503（客户端留着心跳下次冷启再试，不即时重试风暴）");
  expect(playerKeys(quotaR2).length === 0 && activeKeys(quotaR2).length === 0,
    "R2 写失败不会留下半个玩家对象");
  const afterFailedPing = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk"), makeEnv(quotaKv, quotaR2), {},
  );
  expect(afterFailedPing.status === 200,
    "玩家登记失败后，同一个 Worker 里的 /updates 仍然 200");
  const reportAfterPing = await postReport(quotaKv, bodyOf("probe").body, "report-after-quota.txt", quotaR2);
  expect(reportAfterPing.status === 204,
    "R2 写失败不拖累错误报告：/report 的正文仍照常 204 入库");

  quotaR2.setFailPut(false);
  const retriedPing = await postReport(quotaKv, presenceBody(idC), "presence-retry.txt", quotaR2);
  expect(retriedPing.status === 200 && (await retriedPing.json()).new === true,
    "R2 恢复后重试成功（登记幂等，失败不留脏账）");
  expect(playerKeys(quotaR2).length === 1 && activeKeys(quotaR2).length === 1,
    "重试只落一条累积对象 + 一条今日对象");

  quotaR2.setFailList(true);
  const degradedBadge = await getRaw(quotaKv, "/players", quotaR2);
  expect(degradedBadge.response.status === 200 && degradedBadge.json.message === "unknown" &&
    degradedBadge.json.cacheSeconds === 60,
  "R2 list 失败时徽章降级成短缓存 unknown，而不是 5xx 坏图");
  const degradedRaw = await getRaw(quotaKv, "/players/raw", quotaR2);
  expect(degradedRaw.response.status === 503, "raw 面如实回 503，不拿 0 冒充真实读数");
  const updateDuringListFail = await worker.fetch(
    new Request("https://eratw.example/updates/demo.apk"), makeEnv(quotaKv, quotaR2), {},
  );
  expect(updateDuringListFail.status === 200,
    "徽章列举不出来的时候，玩家更新仍然 200");
  const reportDuringListFail = await postReport(
    quotaKv, bodyOf("probe").body, "report-list-fail.txt", quotaR2,
  );
  expect(reportDuringListFail.status === 204,
    "徽章列举失败不拖累错误报告（KV 与统计读面彻底解耦）");

  console.log(`worker.test ok (${checks} checks)`);
  return 0;
}

main().then(
  (code) => process.exit(code),
  (error) => {
    console.error(`${error.message}`);
    console.error(`已过 ${checks} 项；最后一条：${lastLabel}`);
    process.exit(1);
  }
);
