#!/bin/bash
# =============================================================================
# 电商平台 - 统一构建/运行环境变量入口
#
# 用法（被其他脚本 source，不要直接执行）：
#     source "$(dirname "$0")/scripts/env.sh"
#
# 职责：
#   1. 解析并校验 JDK（必须为主版本 17~21，本项目统一使用 JDK 21）
#   2. 导出 JAVA_HOME / JAVA_BIN / MVN_CMD，供 start.sh、scripts/build-apk.sh 等使用
#   3. 脚本中一律使用 "$JAVA_BIN" / "$MVN_CMD"，禁止裸 java / 裸 mvn
#
# JDK 版本决策（架构组已实测）：
#   - Spring Boot 2.7.18 不支持 JDK 24（本机默认 JDK），编译会失败
#   - 根 pom 配置 <release>17，且源码使用 Set.of/List.of，编译 JDK 必须 >= 17 → JDK 8 出局
#   - Android AGP 8.2.2 + Gradle 8.5 要求 JDK >= 17 且最高支持 21
#   - 本机无 JDK 17 → **JDK 21 是唯一交集**
#
# 覆盖方式：
#     export JAVA_HOME_21=/path/to/your/jdk21
# 未设置时回退到本机默认路径（见下方 DEFAULT_JAVA_HOME_21）。
#
# 兼容性：ASCII 直引号；仅使用 bash 3.2 (macOS 自带) 支持的语法。
# =============================================================================

# ---------------------------------------------------------------------------
# 可覆盖的默认路径
# ---------------------------------------------------------------------------
DEFAULT_JAVA_HOME_21="/Users/finn/Library/Java/JavaVirtualMachines/ms-21.0.9/Contents/Home"
DEFAULT_MVN_BIN="/Users/finn/Maven/apache-maven-3.9.9/bin/mvn"

ENV_SH_MIN_JDK=17
ENV_SH_MAX_JDK=21

# ---------------------------------------------------------------------------
# 输出助手（被 source 时可能已定义同名函数，这里使用独立前缀避免冲突）
# ---------------------------------------------------------------------------
env_sh_err() {
    printf '\033[31m[ENV][ERROR]\033[0m %s\n' "$1" >&2
}

env_sh_warn() {
    printf '\033[33m[ENV][WARN ]\033[0m %s\n' "$1" >&2
}

env_sh_info() {
    printf '\033[36m[ENV]\033[0m %s\n' "$1"
}

# ---------------------------------------------------------------------------
# 解析 java -version 输出中的主版本号
#   输入：JDK 家目录
#   输出：主版本号（8 / 17 / 21 / 24 ...），解析失败输出空串
# 说明：
#   JDK 8  -> java version "1.8.0_472"  -> 取第二段 = 8
#   JDK 9+ -> openjdk version "21.0.9"  -> 取第一段 = 21
# ---------------------------------------------------------------------------
env_sh_detect_java_major() {
    local java_home="$1"
    local raw_line=""
    local version_str=""
    local major=""

    if [ ! -x "${java_home}/bin/java" ]; then
        printf ''
        return 1
    fi

    raw_line=$("${java_home}/bin/java" -version 2>&1 | head -n 1)
    version_str=$(printf '%s' "$raw_line" | sed -n 's/.*version "\([^"]*\)".*/\1/p')

    if [ -z "$version_str" ]; then
        printf ''
        return 1
    fi

    major=$(printf '%s' "$version_str" | cut -d. -f1)
    if [ "$major" = "1" ]; then
        major=$(printf '%s' "$version_str" | cut -d. -f2)
    fi
    # 形如 "21-ea" / "17+35" 的情况，剥离非数字后缀
    major=$(printf '%s' "$major" | sed 's/[^0-9].*$//')

    if [ -z "$major" ]; then
        printf ''
        return 1
    fi

    printf '%s' "$major"
    return 0
}

# ---------------------------------------------------------------------------
# 1. 解析 JAVA_HOME
# ---------------------------------------------------------------------------
if [ -n "$JAVA_HOME_21" ]; then
    ENV_SH_JAVA_HOME="$JAVA_HOME_21"
    ENV_SH_JAVA_SOURCE="环境变量 JAVA_HOME_21"
elif [ -n "$JAVA_HOME" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    # 允许调用方通过 JAVA_HOME 直接指定（例如验收测试故意指向 JDK 24 验证报错）
    ENV_SH_JAVA_HOME="$JAVA_HOME"
    ENV_SH_JAVA_SOURCE="环境变量 JAVA_HOME"
else
    ENV_SH_JAVA_HOME="$DEFAULT_JAVA_HOME_21"
    ENV_SH_JAVA_SOURCE="脚本内置默认路径"
fi

if [ ! -x "${ENV_SH_JAVA_HOME}/bin/java" ]; then
    env_sh_err "找不到可执行的 java：${ENV_SH_JAVA_HOME}/bin/java"
    env_sh_err "来源：${ENV_SH_JAVA_SOURCE}"
    env_sh_err "请安装 JDK 21 后执行： export JAVA_HOME_21=/path/to/jdk21"
    exit 1
fi

# ---------------------------------------------------------------------------
# 2. 硬校验 JDK 主版本
# ---------------------------------------------------------------------------
ENV_SH_JAVA_MAJOR=$(env_sh_detect_java_major "$ENV_SH_JAVA_HOME")

if [ -z "$ENV_SH_JAVA_MAJOR" ]; then
    env_sh_err "无法解析 JDK 版本号：${ENV_SH_JAVA_HOME}"
    env_sh_err "原始输出：$("${ENV_SH_JAVA_HOME}/bin/java" -version 2>&1 | head -n 1)"
    exit 1
fi

if [ "$ENV_SH_JAVA_MAJOR" -lt "$ENV_SH_MIN_JDK" ] || [ "$ENV_SH_JAVA_MAJOR" -gt "$ENV_SH_MAX_JDK" ]; then
    env_sh_err "=============================================================="
    env_sh_err " JDK 版本不符合要求"
    env_sh_err "=============================================================="
    env_sh_err " 当前 JDK 主版本 : ${ENV_SH_JAVA_MAJOR}"
    env_sh_err " 当前 JAVA_HOME  : ${ENV_SH_JAVA_HOME}"
    env_sh_err " 来源            : ${ENV_SH_JAVA_SOURCE}"
    env_sh_err " 要求区间        : ${ENV_SH_MIN_JDK} <= 主版本 <= ${ENV_SH_MAX_JDK}（本项目统一使用 JDK 21）"
    env_sh_err "--------------------------------------------------------------"
    env_sh_err " 原因："
    env_sh_err "   - Spring Boot 2.7.18 不支持 JDK 22+（含 JDK 24），编译/启动会失败"
    env_sh_err "   - 根 pom 配置 <release>17，源码使用 Set.of/List.of，JDK 8 无法编译"
    env_sh_err "   - Android AGP 8.2.2 + Gradle 8.5 最高支持 JDK 21"
    env_sh_err "--------------------------------------------------------------"
    env_sh_err " 修复方式（任选其一）："
    env_sh_err "   1) export JAVA_HOME_21=${DEFAULT_JAVA_HOME_21}"
    env_sh_err "   2) unset JAVA_HOME  # 让脚本回退到内置的 JDK 21 路径"
    env_sh_err "=============================================================="
    exit 1
fi

JAVA_HOME="$ENV_SH_JAVA_HOME"
JAVA_BIN="${JAVA_HOME}/bin/java"
JAVA_MAJOR="$ENV_SH_JAVA_MAJOR"
export JAVA_HOME
export JAVA_BIN
export JAVA_MAJOR

# 让子进程（gradle / maven fork）也能拿到正确的 java
PATH="${JAVA_HOME}/bin:${PATH}"
export PATH

# ---------------------------------------------------------------------------
# 3. 解析 Maven
# ---------------------------------------------------------------------------
if [ -n "$MAVEN_BIN" ] && [ -x "$MAVEN_BIN" ]; then
    MVN_CMD="$MAVEN_BIN"
elif [ -x "$DEFAULT_MVN_BIN" ]; then
    MVN_CMD="$DEFAULT_MVN_BIN"
else
    MVN_CMD=$(command -v mvn 2>/dev/null)
fi

if [ -z "$MVN_CMD" ] || [ ! -x "$MVN_CMD" ]; then
    env_sh_err "找不到 Maven 可执行文件"
    env_sh_err "已尝试：\$MAVEN_BIN -> ${MAVEN_BIN:-<未设置>}"
    env_sh_err "        默认路径  -> ${DEFAULT_MVN_BIN}"
    env_sh_err "        which mvn -> <未找到>"
    env_sh_err "请安装 Maven 或执行： export MAVEN_BIN=/path/to/mvn"
    exit 1
fi
export MVN_CMD

# ---------------------------------------------------------------------------
# 4. 汇总输出（静默模式：ENV_SH_QUIET=1 时不打印）
# ---------------------------------------------------------------------------
if [ "$ENV_SH_QUIET" != "1" ]; then
    env_sh_info "JAVA_HOME = ${JAVA_HOME}  (JDK ${JAVA_MAJOR}, 来源: ${ENV_SH_JAVA_SOURCE})"
    env_sh_info "MVN_CMD   = ${MVN_CMD}"
fi
