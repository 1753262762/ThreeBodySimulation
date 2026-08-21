# Three Body Lab 公网部署故障复盘

## 1. 背景与影响

Three Body Lab 采用单体同源部署：Vue 静态资源、REST API 和原生 WebSocket 由同一个 Spring Boot JAR 提供。Linux 服务器通过 systemd 运行 Java 服务，Java 仅监听 `127.0.0.1:18721`，NGINX 在公网端口 `8721` 提供反向代理。

首次公网部署后出现两个用户可见问题：

1. 点击“开始实验”后页面一直显示“正在重连”，无法持续接收实时模拟数据。
2. 进入实验详情页后刷新浏览器，Spring 返回 Whitelabel Error Page，状态码为 404。

REST 创建实验和查询数据仍然成功，因此故障影响的是实时连接和前端子路由刷新，而不是整个 Java 服务不可用。

## 2. 排查证据

排查没有从 CPU、带宽或 JVM 参数开始猜测，而是逐层核对进程、HTTP、NGINX 和 WebSocket：

- systemd 显示 Java 服务自部署后 `NRestarts=0`，没有发生进程重启。
- 内核日志中没有 OOM、Killed process 或崩溃记录。
- 故障期间 `/api/v1/presets`、实验创建和实验详情均正常返回 2xx。
- NGINX access log 显示 `/ws/v1/experiments/{id}` 持续返回 403，前端因此按退避策略重复连接。
- 对同一个实验执行握手对照：
  - `Origin` 带公网端口、`Host` 不带端口时返回 403。
  - `Origin` 与 `Host` 都带公网端口时返回 `101 Switching Protocols`。
- 刷新实验详情页时，NGINX 将 `/experiments/{id}` 原样转发给 Spring；后端没有这个 REST 映射，因此返回 404，而不是返回 Vue 的 `index.html`。

这些证据排除了“Java 服务断连”、CPU 压力、带宽不足和 OOM，最终把问题限定在反向代理请求头与 SPA 路由处理上。

## 3. 根因

### 3.1 WebSocket 403

初始 NGINX 配置使用：

```nginx
proxy_set_header Host $host;
```

NGINX 的 `$host` 不保留非标准公网端口。浏览器发送的 Origin 是 `http://<server-ip>:8721`，而 Spring WebSocket 握手看到的 Host 变成 `<server-ip>`。Spring 的同源校验认为 Origin 与目标 Host 不一致，在进入业务 WebSocket handler 前直接返回 403。

仅仅配置 `Upgrade` 和 `Connection` 请求头，只能说明代理具备升级连接的条件，不能证明握手一定会被应用接受。

### 3.2 页面刷新 404

前端使用 Vue Router 的 `createWebHistory()`。应用内导航到 `/experiments/{id}` 或 `/reports/{id}` 时由 Vue 处理，但浏览器刷新会直接向服务器请求该路径。

初始 NGINX 配置没有 SPA fallback，Spring 也没有对应页面路由，于是返回 Whitelabel 404。

## 4. 本次失误

- 部署验收只检查了根页面和 REST API，没有使用真实浏览器 Origin 验证 WebSocket 握手与消息接收。
- 没有覆盖“进入前端子路由后刷新”这一生产部署必测场景。
- 把“NGINX 已设置 Upgrade 头”错误等同于“WebSocket 已端到端可用”。
- 服务器配置最初只存在于临时文件，没有先作为可审阅、可复用的部署资产固化到仓库。

这是部署验收覆盖不足，而不是物理模拟算法或性能设计错误。

## 5. 修复内容

### 仓库文件

- `deploy/nginx/three-body-lab.conf`
  - WebSocket 使用 `$http_host` 保留端口。
  - 转发 `X-Forwarded-Host`、`X-Forwarded-Port` 和协议头。
  - 将 `/api/`、`/ws/`、`/assets/` 与页面路由分开处理。
  - 只对页面路由启用 `index.html` fallback，避免把 API 或静态资源 404 伪装成 HTML 200。
- `deploy/systemd/three-body-lab.service`
  - 固化 Java 的用户、目录、回环监听、内存参数、自动重启与文件系统保护设置。
- `README.md`
  - 增加 Linux 服务器部署、日志、验证和故障排查说明。
- `docs/PUBLIC_DEPLOYMENT_POSTMORTEM.md`
  - 保存本次复盘和面试表达材料。

### 服务器文件

- `/etc/nginx/conf.d/three-body-lab.conf`：替换为仓库固化的 NGINX 配置。
- `/etc/systemd/system/three-body-lab.service`：当前 systemd 配置的安装位置，本次故障不需要重启或修改 Java 服务。
- `/opt/three-body-lab/three-body-lab.jar`：应用 JAR，本次故障没有业务代码变更，因此无需替换。
- `/var/lib/three-body-lab/.threebody-lab`：实验数据目录，本次修复不修改或删除其中的数据。

## 6. 实施验证

2026-08-21 上线修复后的实际验收结果：

- `nginx -t` 通过，NGINX 平滑 reload 后保持 active。
- 线上 NGINX 配置与仓库文件 SHA-256 一致。
- Java 仍为修复前的同一进程，`NRestarts=0`，没有为本次配置修复重启。
- 公网创建临时实验成功，WebSocket 建立为 Open，NGINX access log 记录 HTTP 101。
- 客户端实际收到 `SNAPSHOT` 业务消息，而不是只完成空握手。
- 直接请求 `/experiments/{id}` 与 `/reports/{id}` 均返回 200 和 Vue 应用入口。
- 不存在的 `/api/` 路径与 `/assets/` 文件仍返回 404，没有被 SPA fallback 掩盖；普通客户端路由回退为 Vue 页面。
- 验收实验完成取消和删除，现有实验数据未被修改。
- 服务器保留修复前配置备份；上传到 `/tmp` 的临时配置已在验收后删除。

## 7. 改进措施

后续部署验收采用完整链路检查：

1. 根页面返回 200，静态资源可以加载。
2. REST 就绪接口和关键写入接口返回预期状态。
3. 使用与浏览器一致的 Origin 完成 WebSocket HTTP 101 握手。
4. 创建临时实验并实际收到至少一条 WebSocket 业务消息，而不只检查握手头。
5. 直接访问并刷新 `/experiments/{id}`、`/reports/{id}`。
6. 检查进程重启次数、OOM 日志、NGINX 4xx/5xx 和应用日志。
7. 配置先进入仓库审阅，再备份线上文件，执行 `nginx -t` 后平滑 reload；失败时恢复备份。

在非标准端口或 TLS 终止代理场景中，额外核对 Host、Origin、`X-Forwarded-Host`、`X-Forwarded-Port` 和 `X-Forwarded-Proto`，不能只检查 Upgrade 头。

## 8. 面试表达

### 30 秒版本

我在把一个 Spring Boot、Vue 和原生 WebSocket 的三体模拟项目部署到 NGINX 后，遇到实时连接持续重连和子路由刷新 404。最初看起来像 Java 服务或带宽问题，但我通过 systemd、内核和 NGINX 日志确认 JVM 没有重启，REST 也正常，真正失败的是 WebSocket 403。随后用 Host/Origin 对照握手证明 NGINX 的 `$host` 丢失了非标准端口，触发 Spring 同源校验；刷新 404 则是 Vue History 路由缺少 SPA fallback。我修正代理头、分离 API/WS/静态资源路由并补上页面回退，同时把真实 WebSocket 消息和子路由刷新加入部署验收清单。

### STAR 版本

**Situation：** 项目从本地部署到一台 Linux 公网服务器，NGINX 代理运行在非标准端口。根页面和 REST 正常，但开始实验后 WebSocket 一直重连，刷新实验页又出现 Whitelabel 404。

**Task：** 在不破坏现有实验数据、不盲目调整推送协议或扩容的前提下，定位实时链路和路由故障，并形成可重复部署方案。

**Action：** 我先检查 systemd 重启次数、内核 OOM 和 REST 日志，确认 Java 没有退出。NGINX 日志显示 WebSocket 持续 403，于是对同一请求改变 Host 是否携带端口进行对照，带端口得到 101，从而定位到 `$host` 丢失端口导致的 Spring Origin 校验失败。随后把 WebSocket、API、静态资源和页面路由拆分配置，用 `$http_host` 保留端口，并只为前端页面添加 SPA fallback。上线前先备份，`nginx -t` 通过后平滑 reload。

**Result：** WebSocket 握手恢复为 101，前端能够持续接收实时消息，实验页和报告页刷新不再返回 404；Java 全程没有重启，实验数据未受影响。更重要的是，我把真实消息、Origin/Host、子路由刷新和回滚检查纳入了部署验收清单，并将 NGINX/systemd 配置固化到仓库。

### 可能的深入追问

**为什么 `$host` 和 `$http_host` 的差异会导致 403？**

`$http_host` 来自客户端 Host 请求头，通常保留显式端口；`$host` 是 NGINX 归一化后的主机名，可能不包含非标准端口。Spring WebSocket 默认执行同源校验，当 Origin 中含 `:8721`、代理后的 Host 不含端口时，两者不再同源。

**为什么不直接允许所有 WebSocket Origin？**

通配 Origin 会绕开症状，但削弱浏览器侧的跨站保护，也掩盖代理配置错误。同源部署应当正确保留外部 Host 和端口；如果未来前后端分域，再配置明确的 Origin 白名单。

**为什么不改成 Hash 路由？**

Hash 路由确实不要求服务器 fallback，但会改变现有 URL 形式和路由契约。当前部署已有 NGINX，增加正确的 SPA fallback 改动更小，也保留可读的 History URL。

**为什么没有先扩容或降低推送频率？**

因为进程、OOM、REST 和握手状态已经提供了更直接的证据。403 是协议拒绝，不是带宽拥塞；在根因明确前调整算力或协议只会增加变量。

**如何避免再次发生？**

把配置作为代码管理，并在发布检查中同时覆盖 HTTP 200、REST、带真实 Origin 的 WebSocket 101、实际消息接收、前端子路由刷新、服务重启次数和回滚路径。
