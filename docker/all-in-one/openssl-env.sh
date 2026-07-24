#!/bin/bash
# 统一 Tongsuo 运行环境（被 entrypoint / TLS / TSA 脚本 source）
# shellcheck disable=SC2034

export PATH="/usr/local/tongsuo/bin:${PATH:-/usr/bin:/bin}"
export LD_LIBRARY_PATH="/usr/local/tongsuo/lib:/usr/local/tongsuo/lib64${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

if [ -x /usr/local/tongsuo/bin/openssl ]; then
    export OPENSSL_BIN=/usr/local/tongsuo/bin/openssl
elif [ -x /usr/local/bin/openssl-gm ]; then
    export OPENSSL_BIN=/usr/local/bin/openssl-gm
else
    export OPENSSL_BIN="$(command -v openssl || true)"
fi

# Tongsuo 编译时 openssldir=/usr/local/tongsuo/ssl
# 即使 unset OPENSSL_CONF，库仍会读该路径下的 openssl.cnf
_ensure_openssl_cnf() {
    local target="/usr/local/tongsuo/ssl/openssl.cnf"
    local fallback="/etc/tsa/openssl/openssl-runtime.cnf"
    mkdir -p /usr/local/tongsuo/ssl
    if [ ! -s "${target}" ]; then
        if [ -s "${fallback}" ]; then
            cp -f "${fallback}" "${target}"
            echo "[INFO] 已安装兜底 openssl.cnf -> ${target}"
        else
            # 最后手段：写最小配置，保证 req -x509 能跑
            cat > "${target}" <<'EOF'
[ req ]
default_bits = 2048
default_md = sha256
distinguished_name = req_distinguished_name
prompt = no
[ req_distinguished_name ]
CN = tsa
EOF
            echo "[INFO] 已写入最小 openssl.cnf -> ${target}"
        fi
    fi
    export OPENSSL_CONF="${target}"
}
_ensure_openssl_cnf
