# 部署说明（新手向，从零开始）

<p align="center"><b>简体中文</b> | <a href="deployment_EN.md">English</a></p>

这篇文档假设你**没用过 Docker、没在终端里敲过命令**，只是想把这个记账服务跑起来。会稍微啰嗦一点，但每一步具体该点哪里、该输入什么都会写出来。

如果你以前用过 Docker，直接看文末的[速查版](#速查版给熟手看)就够了。

## 这东西到底是什么、要装在哪

记账分两部分：

- **手机上的 App**：你平时点开看账、记账用的。
- **后端服务**：真正存你数据的地方，是一个要**一直开着**的程序，装在一台 24 小时不关机的设备上。

这篇文档就是教你怎么把"后端服务"装起来。常见的选择：

- **NAS**（群晖、极空间、威联通等）——最推荐，本来就 24 小时开着，功耗低。
- 树莓派、香橙派这类小主机
- 家里一台常年开着的旧电脑
- 云服务器（阿里云、腾讯云等，每月要花钱）

不管用哪种，这台设备都要能装 **Docker**（一种打包好环境的软件，几乎所有 NAS 的应用商店里都有）。

## 第一步：确认你的设备能跑 Docker

### 如果是 NAS（群晖 / 极空间 / 威联通）

去 NAS 自己的"应用中心"或"软件中心"里搜索 **Docker** 或 **Container Manager**，像装普通 App 一样装上就行，不用懂原理。

### 如果是树莓派 / 香橙派 / 普通电脑（装了 Linux）

需要会用终端（SSH 连上去或者直接接显示器键盘），执行官方的一键安装命令：

```bash
curl -fsSL https://get.docker.com | sh
```

这一步如果你完全不懂终端是什么，建议先找个会用 Linux 的朋友帮忙，或者优先选上面的 NAS 路线。

## 第二步：下载这个项目的代码

**方式 A（推荐给新手）：直接下载压缩包**

1. 打开 [https://github.com/EdgeN8v/Ledger](https://github.com/EdgeN8v/Ledger)
2. 点绿色的 **Code** 按钮 → **Download ZIP**
3. 把压缩包传到你的 NAS 上（群晖/极空间自带的"文件管理"或者用 [FileZilla](https://filezilla-project.org/) 之类的工具拖上去都行），解压到一个你知道路径的文件夹，比如 `/docker/ledger/`

**方式 B（熟悉命令行的话）**

```bash
git clone https://github.com/EdgeN8v/Ledger.git
cd Ledger
```

## 第三步：打开终端，进到这个文件夹

这一步**绕不开**，接下来几步都要在终端里敲命令。

- **群晖**：控制面板 → 终端机和 SNMP → 勾选"启用 SSH 服务"，然后用 [PuTTY](https://www.putty.org/)（Windows）或"终端"App（Mac）连上去，用户名密码就是你平时登 NAS 的那个。
- **极空间**：类似，在"设置 → 常规设置"或"安全管理"里开 SSH，同样用 PuTTY/终端连。
- **树莓派等 Linux 机器**：同样用 SSH 连，或者直接接显示器键盘打开终端。

连上之后，用 `cd` 命令进到你刚才解压/克隆出来的文件夹，比如：

```bash
cd /volume1/docker/Ledger
```

（具体路径看你解压到哪儿了；不确定的话，先用 `ls` 看看当前文件夹里有什么，一步步 `cd` 进去。）

## 第四步：准备配置文件（两个密码）

这一步是在告诉程序："数据库密码用这个""谁能用你的账号登录的密钥用这个"。

先复制一份配置模板：

```bash
cp .env.example .env
```

然后打开这个新生成的 `.env` 文件编辑（终端里可以用 `nano .env`，不会用 nano 的话也可以直接在 NAS 的文件管理器里双击用自带的文本编辑器打开）。

把里面的两行改成你自己的内容：

```ini
POSTGRES_PASSWORD=随便换成一串你自己的密码
JWT_SECRET=换成一长串随机字符
```

- `POSTGRES_PASSWORD`：数据库的密码，自己编一个就行，记不记得住无所谓（不会手动用到）。
- `JWT_SECRET`：决定"谁的登录是合法的"，一定要够长、够随机，**不要用生日/手机号这种**。偷懒的话，在终端执行下面这行，会直接生成一串贴进去：

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

（`nano` 编辑完，按 `Ctrl+O` 保存，回车，再按 `Ctrl+X` 退出。）

## 第五步：一键启动

```bash
docker compose up -d --build
```

第一次执行会花几分钟（要下载和编译），终端里会跳一堆字，等它跑完、重新出现命令提示符就是好了。

检查一下有没有成功：

```bash
curl http://localhost:8000/health
```

看到 `{"status":"ok"}` 就说明后端已经在正常运行了。

> **看到报错 "JWT_SECRET must be set"？** 说明第四步的 `.env` 没编辑对，或者编辑完没保存，回去再检查一遍。
>
> **极空间 / 群晖界面里找不到 `docker compose` 命令？** 改用它们自带的"容器管理 / Container Manager"里的"项目"功能，导入这个文件夹里的 `docker-compose.yml` 文件，点部署，效果是一样的。
>
> **NAS 是 ARM 芯片（比如很多极空间、威联通型号）？** 不用担心，上面的命令会在 NAS 本机直接编译适合它芯片的版本，不需要你额外做什么。

## 第六步：创建你自己的账号

为了不让陌生人注册到你的服务器上，默认是**关闭注册**的，所以第一次要临时开一下：

1. 回到 `.env` 文件，在最后加一行：

   ```ini
   REGISTRATION_OPEN=true
   ```

2. 让这行生效：

   ```bash
   docker compose up -d
   ```

3. 打开手机 App，填入你的服务器地址（下一步会讲地址怎么填），点"创建账本"，注册你自己的账号。
4. 家里其他人（比如家人）不需要重复开注册，用 App 里"加入账本"+ 邀请码（在 App"我的"页面能看到）就能加进同一个账本。
5. 都注册完了，把 `.env` 里刚才那行 `REGISTRATION_OPEN=true` 删掉或注释掉（前面加个 `#`），再执行一次 `docker compose up -d`，重新关闭注册。

## 第七步：手机上填服务器地址

打开 App，登录页有个"服务器地址"，按下面哪种方式连接，就填对应的地址。**先从第一种（只在家用）开始，够用就不用折腾后面的**：

| 方式 | 怎么填 | 适合谁 |
|---|---|---|
| 只在家里 WiFi 下用 | `http://你NAS的局域网IP:8000/`（IP 在 NAS 的系统信息里能看到，一般是 `192.168.x.x`） | 最简单，出门不能同步，但手机会先存在本地，回家自动补传，不会丢。 |
| 出门也要用，走 VPN | NAS 上装 WireGuard 或 Tailscale，手机也装，服务器地址填 VPN 给的内网地址 | 想随时记账、又不想把端口暴露到公网的人。 |
| 出门也要用，公网直连 | 用 Caddy / Nginx 配 HTTPS，域名指过去，**绝对不要**把 8000 端口直接暴露到公网 | 不推荐新手做，容易留安全隐患。 |

## 日常维护

### 备份

`docker-compose.yml` 里已经带了一个自动备份的小容器，每天半夜自己把数据库导出到 `./backups/` 文件夹里，留最近 14 天。**但这台 NAS 本身坏了怎么办？** 建议你偶尔手动把 `backups/` 整个文件夹再复制一份到别的硬盘或者网盘，双保险。

要从备份恢复（比如换了台新机器）：

```bash
zcat backups/ledger-日期.sql.gz | docker compose exec -T db psql -U ledger -d ledger
```

### 升级到新版本

以后这个项目更新了，想升级到最新代码：

```bash
git pull
docker compose up -d --build
```

（如果你是用"下载 ZIP"装的，就重新下一份最新的 ZIP，把 `.env` 文件复制过去，再执行 `docker compose up -d --build`。）

数据库结构如果有变化（比如新加了一列），项目的更新说明里会给出一行类似 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` 的语句，复制进 `docker compose exec -T db psql -U ledger -d ledger` 执行一次就行，这种语句只加不删，不会丢数据。

### 手机权限

手机上还要开几个权限（通知使用权、读短信、关闭小米之类的省电限制），这部分和后端无关，在 [README 的"在手机上授权"](../README.md#在手机上授权) 里有写。

---

## 速查版（给熟手看）

```bash
git clone https://github.com/EdgeN8v/Ledger.git
cd Ledger
cp .env.example .env
# 编辑 .env：POSTGRES_PASSWORD、JWT_SECRET
docker compose up -d --build
curl http://localhost:8000/health        # {"status":"ok"}
# 临时打开 REGISTRATION_OPEN=true 注册完账号后关掉
```

App 服务器地址：家里局域网直连最简单；出门用 VPN（WireGuard/Tailscale）；不建议把 8000 直接暴露公网。备份见上方 `backup` 容器，升级 `git pull && docker compose up -d --build`。
