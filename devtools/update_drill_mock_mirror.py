#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""更新通道演练的**本地 mock 镜像**（CONTRACT-UPD2-3LANE §2 U9 的「镜像形状」那一半）。

`update_drill.py` 把发布目录摆成镜像能服务的形状，然后按「逻辑镜像地址 → 本地回环端口」
的映射取字节。三件事要说清：

1. **不给生产开任何明文口子**。设备侧的 https-only 判据（`UpdatePolicy.normalizeMirror`
   与 `ErrorReportUpload.isHttpsEndpoint`）一个字都不动：候选序、清单里的 `mirror` 行、
   下载地址全部仍是 `https://…`，只有真正落地的 socket 绑在 `127.0.0.1` 的一次性端口上。
   绑定的那一步发生在演练自己的传输函数里，等于「把 DNS 指到本机」，不改判据。
2. **每条镜像一个独立 origin**（各自一个端口），于是「A 源失联、B 源活着」这类场景能在
   字节层面造出来，而不是在判定层面假装。
3. **故障注入是服务器侧的行为**，不是客户端的分支：`dead` / `cut:<字节>` / `no_range` /
   `missing:<文件>` 让服务端真的回 404、真的半途掐线、真的对 Range 请求回 200 全量 ——
   客户端必须自己判 `mustRewriteFromScratch`、自己认领 `.part`、自己按健康度换源。

传输口有两档：`HttpTransport`（真 socket，默认）与 `InProcTransport`（同一份
`MockMirror.handle` 直调，无 socket）。后一档只在环境连回环端口都绑不上时兜底
（沙箱/CI），两条档跑的是同一套判据，只有 socket 那一层不同 —— 降级时演练会大声说明。

本模块不含任何判定语义（判定全在 `update_drill.py` 里按 Kotlin 直译），也不碰生产代码。
"""

from __future__ import annotations

import os
import posixpath
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class TransportError(Exception):
    """连接层失败（对端不存在/被拒/线被掐）—— 设备侧对应 `FailureKind.Network` 那一族。"""


#: 故障档位（键是逻辑镜像基址，值决定这条源怎么对待请求）。
FAULT_OK = "ok"
FAULT_DEAD = "dead"                  # 连接都建不起来：整源失联
FAULT_MISSING = "missing"        # 目录在、点名的那个对象不在：404
FAULT_NO_RANGE = "no_range"          # 对 Range 请求回 200 全量（野鸡代理/静态目录的常见形状）
FAULT_CUT = "cut"                    # 声明全长 CL 后截断写 body（客户端读到的字节少于 CL，随即 EOF）
FAULT_ERROR = "error"                # 回一个 5xx（源活着但此刻不行）—— 设备侧该在同一条源退避重试



class MockMirror:
    """一个静态目录 origin：按 R2/对象存储的口径服务 GET + Range。"""

    def __init__(self, root: str, base_url: str, name: str = "") -> None:
        self.root = os.path.abspath(root)
        self.base_url = base_url
        self.name = name or base_url
        self.fault = FAULT_OK
        self.fault_arg = 0
        self.fault_times = 0  # None = 永久生效；正整数 = 还能生效几次
        self.requests = []  # [(path, range_start_or_None)] —— 断言「某条源被打了几次」靠它

    def set_fault(self, fault: str, arg=0, times=1) -> None:
        """装一个故障；`times` = 连续生效几次后自动恢复正常（None = 永久）。"""
        self.fault = fault
        self.fault_arg = arg
        self.fault_times = times if fault != FAULT_OK else 0

    def _fault_fires(self) -> bool:
        if self.fault == FAULT_OK:
            return False
        if self.fault_times is None:
            return True
        if self.fault_times > 0:
            self.fault_times -= 1
            return True
        return False

    # -- 字节层：与真静态目录同形 -------------------------------------------------

    def _resolve(self, path: str):
        """把 URL 路径映射到目录里的文件；越界（`..`）一律 404，不抛。"""
        clean = posixpath.normpath(path.lstrip("/")).replace("\\", "/")
        if clean.startswith("..") or clean == "..":
            return None
        candidate = os.path.join(self.root, *clean.split("/")) if clean else self.root
        if not os.path.isfile(candidate):
            return None
        if os.path.commonpath([os.path.abspath(candidate), self.root]) != self.root:
            return None
        return candidate

    def handle(self, path: str, range_start=None):
        """(status, headers, body_bytes)。headers 是 dict（键按 HTTP 惯用写法）。"""
        self.requests.append((path, range_start))
        base = os.path.basename(path)
        if self.fault == FAULT_DEAD and self._fault_fires():
            raise TransportError(f"{self.name}：整机失联")
        if self.fault == FAULT_ERROR and self._fault_fires():
            return int(self.fault_arg or 503), {"Content-Length": "0"}, b""
        file_path = None if (self.fault == FAULT_MISSING and self._fault_fires()
                             and (self.fault_arg in (0, None, "")
                                  or base == str(self.fault_arg))) \
            else self._resolve(path)
        if file_path is None:
            return 404, {"Content-Type": "application/octet-stream"}, b""
        with open(file_path, "rb") as fh:
            body = fh.read()
        total = len(body)
        cut = self.fault == FAULT_CUT and self._fault_fires()
        if range_start is None or range_start <= 0:
            if cut:
                # 「CL 声明全长、读到一半 EOF」（L7）：真静态目录被反向代理/断网截断时就是这个
                # 形状 —— 响应头是**完整**的 Content-Length，body 只写到第 fault_arg 字节就收口。
                # 旧写法返回空 headers 让发送层按实收字节补 CL，客户端看到的是自洽短响应
                # （除了清单声明的字节数，没人知道少了东西），与真实断流不同形。
                return 200, {"Content-Length": str(total)}, body[:self.fault_arg]
            return 200, {"Content-Length": str(total)}, body
        if self.fault == FAULT_NO_RANGE and self._fault_fires():
            # 不支持区间：回 200 + 整份。设备侧必须**截断从头重写**，不许把整份追加到旧 .part 后。
            return 200, {"Content-Length": str(total)}, body
        if range_start >= total:
            return 416, {"Content-Range": f"bytes */{total}"}, b""
        chunk = body[range_start:]
        if cut:
            # 续传口同样声明「剩余全长」（Content-Range 的 total 与 Content-Length 都按完整
            # 响应写），再只写 fault_arg 字节 —— 与「上一条连接传到一半掉线」同形。
            return 206, {
                "Content-Range": f"bytes {range_start}-{total - 1}/{total}",
                "Content-Length": str(len(chunk)),
            }, chunk[:self.fault_arg]
        return 206, {
            "Content-Range": f"bytes {range_start}-{total - 1}/{total}",
            "Content-Length": str(len(chunk)),
        }, chunk


class _Handler(BaseHTTPRequestHandler):
    """把一次 HTTP 请求翻成 `MockMirror.handle`；每个 origin 一个 server 实例。"""

    server_version = "EratwDrillMirror/1"

    def log_message(self, fmt, *args):  # 静音：演练自己打印逐拍读数
        pass

    def do_GET(self):  # noqa: N802 —— BaseHTTPRequestHandler 的命名约定
        mirror = self.server.mirror
        range_start = _parse_range(self.headers.get("Range"))
        try:
            status, headers, body = mirror.handle(self.path, range_start)
        except TransportError:
            # 整源失联：连接直接收口，不吐响应 —— 客户端拿到的是连接层异常，
            # 与真机对着一个挂了的镜像是同一个形状。
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                self.connection.close()
            except OSError:
                pass
            return
        self.send_response(status)
        for key, value in headers.items():
            self.send_header(key, value)
        if "Content-Length" not in headers:
            self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass


def _parse_range(header):
    """`Range: bytes=1000-` → 1000；别的形状一律 None（按整份服务）。"""
    if not header or not header.strip().lower().startswith("bytes="):
        return None
    spec = header.strip()[len("bytes="):]
    start = spec.split("-")[0]
    try:
        value = int(start)
    except ValueError:
        return None
    return value if value >= 0 else None


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, mirror: MockMirror) -> None:
        super().__init__(("127.0.0.1", 0), _Handler)
        self.mirror = mirror


def serve(mirrors):
    """起一批回环静态服务，返回 {逻辑基址: ("127.0.0.1", port)} 映射。"""
    servers = []
    mapping = {}
    try:
        for mirror in mirrors:
            server = _Server(mirror)
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            servers.append(server)
            mapping[mirror.base_url] = ("127.0.0.1", server.server_address[1])
        return mapping, servers
    except OSError:
        for server in servers:
            server.shutdown()
            server.server_close()
        raise


def stop(servers) -> None:
    for server in servers:
        try:
            server.shutdown()
            server.server_close()
        except OSError:
            pass


class HttpTransport:
    """真 socket 档：逻辑 https 地址 → 本机端口（策略层看不见这层映射）。"""

    kind = "http"

    def __init__(self, mapping, mirrors_by_base) -> None:
        self.mapping = mapping
        self.mirrors_by_base = mirrors_by_base

    def get(self, url, range_start=None):
        import http.client
        for base, (host, port) in self.mapping.items():
            if url.startswith(base):
                path = url[len(base):]
                conn = http.client.HTTPConnection(host, port, timeout=10)
                try:
                    headers = {"Range": f"bytes={range_start}-"} if range_start else {}
                    conn.request("GET", "/" + path.lstrip("/"), headers=headers)
                    resp = conn.getresponse()
                    # 逐块读到 EOF：断流时拿到的是「已经收到的那部分」，与设备侧
                    # `input.read(buffer)` 读到 -1 同口径（不靠 Content-Length 提前报错）。
                    chunks = []
                    while True:
                        piece = resp.read(1 << 16)
                        if not piece:
                            break
                        chunks.append(piece)
                    return resp.status, dict(resp.getheaders()), b"".join(chunks)
                except (http.client.HTTPException, socket.timeout, ConnectionError) as exc:
                    raise TransportError(f"{url}：{exc}") from exc
                finally:
                    conn.close()
                break
        raise TransportError(f"演练的 mock 镜像表里没有这条地址：{url}")

    def close(self) -> None:
        pass


class InProcTransport:
    """无 socket 档：同一份 `MockMirror.handle` 直调（只在绑不上端口时兜底）。"""

    kind = "inproc"

    def __init__(self, mirrors_by_base) -> None:
        self.mirrors_by_base = mirrors_by_base

    def get(self, url, range_start=None):
        for base, mirror in self.mirrors_by_base.items():
            if url.startswith(base):
                return mirror.handle("/" + url[len(base):].lstrip("/"), range_start)
        raise TransportError(f"演练的 mock 镜像表里没有这条地址：{url}")

    def close(self) -> None:
        pass
