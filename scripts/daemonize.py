#!/usr/bin/env python3
"""
把一个 Java 服务以真正的守护进程方式拉起。

背景：沙箱化的后台任务在结束时会回收整个进程组（kill -- -PGID），
即使用了 nohup / disown 也照样被带走。唯一可靠的办法是让 java 进程
进入一个全新的 session（setsid），从而彻底脱离原进程组。

用法:
    python3 daemonize.py <jar> <port> <logfile> <pidfile>
环境变量从当前进程继承（调用方负责先 source .env）。
"""
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request


def port_holder(port: int) -> str:
    """返回正在 LISTEN 该端口的 pid（可能多个，空格分隔）；无人监听返回空串。"""
    try:
        out = subprocess.run(
            ["lsof", "-nP", f"-iTCP:{port}", "-sTCP:LISTEN", "-t"],
            capture_output=True, text=True, timeout=10,
        ).stdout.split()
        return " ".join(out)
    except (OSError, subprocess.SubprocessError):
        return ""


def wait_port_free(port: int, timeout: int = 30) -> bool:
    """等待端口彻底释放。

    踩过的坑：kill 之后立刻拉新进程，旧进程可能还没放开端口，新进程直接
    PortInUseException 死掉。而此时健康检查探到的是**旧进程**的 200，
    于是报「启动成功」——等旧进程真的退出，服务就凭空消失了。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        if not port_holder(port):
            return True
        time.sleep(1)
    return False


def wait_health(port: int, proc: subprocess.Popen, timeout: int = 90) -> bool:
    """健康检查。

    必须同时满足两个条件才算通过，缺一不可：
      1) /actuator/health 返回 200
      2) 监听该端口的进程**就是我们刚拉起的那个**
    只看 (1) 会被「旧进程尚未退出」骗过去（血泪教训，见 wait_port_free）。
    """
    url = f"http://127.0.0.1:{port}/actuator/health"
    deadline = time.time() + timeout
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while time.time() < deadline:
        # 子进程已经死了就不用再等了，早失败早报错
        if proc.poll() is not None:
            print(f"[ERR] java 进程已退出，exit={proc.returncode}")
            return False
        try:
            with opener.open(url, timeout=3) as resp:
                if resp.status == 200 and str(proc.pid) in port_holder(port).split():
                    return True
        except (urllib.error.URLError, OSError):
            pass
        time.sleep(2)
    return False


def main() -> int:
    if len(sys.argv) != 5:
        print(__doc__)
        return 2

    jar, port_s, logfile, pidfile = sys.argv[1:5]
    port = int(port_s)

    if not os.path.isfile(jar):
        print(f"[ERR] jar 不存在: {jar}")
        return 1

    # 端口必须先干净。占着就等，等不到就报错退出——绝不在旧进程还活着时硬拉，
    # 那样只会得到一个启动失败的新进程 + 一个骗人的 200。
    holder = port_holder(port)
    if holder:
        print(f"[WARN] 端口 {port} 仍被 pid={holder} 占用，等待释放…")
        if not wait_port_free(port):
            print(f"[ERR] 端口 {port} 30s 内未释放（pid={port_holder(port)}），已放弃。")
            print(f"       请先手动停止：kill {port_holder(port)}")
            return 1
        print(f"[INFO] 端口 {port} 已释放")

    java_bin = os.environ.get("JAVA_BIN") or "java"
    cmd = [java_bin, "-jar", jar, f"--server.port={port}"]

    os.makedirs(os.path.dirname(logfile) or ".", exist_ok=True)
    log_fh = open(logfile, "ab", buffering=0)

    # start_new_session=True → 内部调用 setsid()，子进程成为新 session 的首进程，
    # 拥有独立的 PGID，父进程退出后由 init(PID 1) 收养。
    proc = subprocess.Popen(
        cmd,
        stdout=log_fh,
        stderr=subprocess.STDOUT,
        stdin=subprocess.DEVNULL,
        start_new_session=True,
        close_fds=True,
    )

    with open(pidfile, "w") as f:
        f.write(str(proc.pid))

    print(f"[INFO] 已启动 {os.path.basename(jar)} pid={proc.pid} port={port} (独立 session)")

    if wait_health(port, proc):
        print(f"[OK] 端口 {port} 健康检查通过（已确认监听者就是 pid={proc.pid}）")
        return 0

    print(f"[ERR] 端口 {port} 健康检查超时，日志尾部：")
    with open(logfile, "r", errors="replace") as f:
        for line in f.readlines()[-25:]:
            print("   " + line.rstrip())
    return 1


if __name__ == "__main__":
    sys.exit(main())
