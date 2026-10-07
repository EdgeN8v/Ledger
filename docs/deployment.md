# 部署说明

<p align="center"><b>简体中文</b> | <a href="deployment_EN.md">English</a></p>

后端是一个普通的 Docker Compose 项目（API + Postgres + 一个可选的备份小容器），NAS、树莓派、软路由、云服务器都能跑。

## 1. 起服务

```bash
git clone https://github.com/EdgeN8v/Ledger.git
cd Ledger
cp .env.example .env
```

编辑 `.env`：

```ini
POSTGRES_PASSWORD=换成你自己的数据库密码
JWT_SECRET=换成一段很长的随机字符串
# REGISTRATION_OPEN=true     # 只在创建家人账号的时候临时打开
```

生成随机字符串：`python -c "import secrets; print(secrets.token_urlsafe(48))"`。

```bash
docker compose up -d --build
curl http://localhost:8000/health         # {"status":"ok"}
```

没设置 `JWT_SECRET` 时容器会拒绝启动，这是有意为之。

> **极空间 / 群晖等 NAS**：如果界面里没有 `docker compose`，用它们自带的"项目 / Compose"功能导入 `docker-compose.yml`。如果 NAS 在 ARM 芯片上，直接在 NAS 上 `docker build` 就行，不需要交叉编译。

## 2. 创建账号

默认不允许注册。第一次创建账号：

1. `.env` 里加一行 `REGISTRATION_OPEN=true`，`docker compose up -d`。
2. 在 App 里注册你的账号；家人用"加入账本"和邀请码（App"我的"页里）加入。
3. 删掉那一行，`docker compose up -d`，注册就又关上了。

## 3. 手机怎么连上服务器

按"安全从高到低"排：

| 方式 | 说明 |
|---|---|
| 只在家里局域网 | 最简单，也最安全。服务器地址填 `http://NAS内网IP:8000/`，只有连着家里 WiFi 才能同步（自动记账会先存在手机里，回家后补传）。 |
| 用 VPN 连回家里 | 推荐出门也要用的话选这个。在 NAS 上装 WireGuard 或 Tailscale，手机装客户端，服务器地址填虚拟内网 IP。路由器上不用开放 8000。 |
| 反向代理 + HTTPS 公网访问 | 不推荐。如果一定要，用 Caddy / Nginx 之类加上 HTTPS 和访问限制，**不要把 8000 直接映射到公网**。 |

自动记账靠的是手机一直在转发通知，所以哪怕不在家，手机也会把账目先存在本地，等能连上服务器时再一起上传。

## 4. 备份

`docker-compose.yml` 里的 `backup` 服务每天凌晨 3 点（UTC）把整个数据库导出成 `./backups/ledger-日期.sql.gz`，保留最近 14 份。请把 `backups/` 再复制到另一台机器或另一块盘。

**还原**：

```bash
zcat backups/ledger-XXXX.sql.gz | docker compose exec -T db psql -U ledger -d ledger
```

（还原到一个全新、空的库里。）

## 5. 升级

```bash
git pull
docker compose up -d --build
```

数据库表结构的新增列在启动时不会自动迁移；如果某次更新给已有的表加了列，更新说明里会给出 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 语句，执行一次即可（只加不删，不会丢数据）。新的表会自动创建。

## 6. 适配手机

手机端的权限设置（通知使用权、读短信、小米的省电与自启动）见 [README](../README.md#在手机上授权)。
