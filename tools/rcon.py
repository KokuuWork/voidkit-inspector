#!/usr/bin/env python3
"""开发用 RCON 客户端 —— 往本地 dev 服务端发命令并读回响应。

用途：自动验证 `/coi` 系命令链路（注册 / 权限 / 参数 / 执行），
比让人手敲键盘再翻日志可靠得多 —— 命令报错会原样回显。

用法：
    python tools/rcon.py "coi status"
    python tools/rcon.py "coi here"

注意：
* 只连 127.0.0.1:25575，密码走环境变量 VOIDKIT_RCON_PASSWORD
  （默认是 build.gradle 里 syncDevServerConfig 写的 dev 值）。
* RCON 端口与密码仅用于本地开发（server.properties 在 build/ 下，不入 git）。
* 控制台（RCON）身份**永远绕过 requires 判定**（VanillaPermissionProvider.admin
  对无玩家实体的命令源直接放行），所以控制台能测到命令是否注册，
  但**测不到玩家权限** —— 玩家侧权限要用真客户端验。
"""
import socket
import struct
import sys
import os

HOST = os.environ.get("VOIDKIT_RCON_HOST", "127.0.0.1")
PORT = int(os.environ.get("VOIDKIT_RCON_PORT", "25575"))
# 密码从环境变量读，不写进源码 —— 与项目「凭据不进聊天/不进版本库」一致。
# 默认值与 build.gradle 的 syncDevServerConfig 一致（仅 dev 用，build/ 不入 git）。
PASSWORD = os.environ.get("VOIDKIT_RCON_PASSWORD", "devtest123")

# RCON 包类型
LOGIN = 3
COMMAND = 2
RESPONSE = 0
AUTH_FAIL = 2


def pack(req_id: int, req_type: int, payload: str) -> bytes:
    data = payload.encode("utf-8") + b"\x00\x00"
    return struct.pack("<iii", req_id, req_type, len(data)) + data


def read_all(sock: socket.socket, size: int) -> bytes:
    buf = b""
    while len(buf) < size:
        chunk = sock.recv(size - len(buf))
        if not chunk:
            raise ConnectionError("连接被服务端关闭")
        buf += chunk
    return buf


def recv_packet(sock: socket.socket):
    length = struct.unpack("<i", read_all(sock, 4))[0]
    body = read_all(sock, length)
    req_id, req_type = struct.unpack("<ii", body[:8])
    # 末尾两字节是 \x00\x00
    return req_id, req_type, body[8:-2].decode("utf-8", errors="replace")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    cmd = sys.argv[1]

    try:
        sock = socket.create_connection((HOST, PORT), timeout=10)
    except OSError as exc:
        print(f"[rcon] 连不上 {HOST}:{PORT} —— {exc}", file=sys.stderr)
        print("      服务端起了吗？RCON 开了吗？（看 server.properties 的 enable-rcon）", file=sys.stderr)
        return 2

    with sock:
        sock.sendall(pack(1, LOGIN, PASSWORD))
        req_id, req_type, _ = recv_packet(sock)
        if req_type == AUTH_FAIL:
            print("[rcon] 认证失败 —— 密码与服务端不一致", file=sys.stderr)
            return 2

        sock.sendall(pack(2, COMMAND, cmd))
        _, req_type, resp = recv_packet(sock)
        # 命令有输出时会收到多个包，最后一个是空包标记结束
        chunks = [resp] if resp else []
        while True:
            try:
                sock.settimeout(1.0)
                _, _, more = recv_packet(sock)
            except (socket.timeout, ConnectionError, OSError):
                break
            if not more:
                break
            chunks.append(more)

        print("\n".join(chunks) if chunks else "(无输出)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
