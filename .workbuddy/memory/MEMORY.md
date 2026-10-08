# 天机学堂 · 项目长期笔记

> **用法**：新会话开始先只读这个文件（≈3KB）就能接上进度。
> 细节再按需翻 `.workbuddy/memory/YYYY-MM-DD.md`（单日可能 600+ 行，别整篇读）。

## 项目与操作

- 路径 `D:\java\project111\tianji`，当前分支 `master`
- **推送**（两处，从不推远端 master）：
  `git push github master:feature-promotions` ＋ `git push vminternal master:feature-promotions`
- **编译**：`mvn.cmd -o -pl tj-promotion -am compile`（需 `JAVA_HOME` 指向 jdk-21）
- **虚拟机** `192.168.150.101`：nacos 8848 / redis 6379 / mysql 3306 / rabbitmq 5672 / Gogs 10880 / **Seata TC 8099**
  （Gogs 等容器开机后要几分钟才起全）
- 飞书文档：**day12 wiki token `wikcnjOrJnWbgSsB6HqWCHu1iBe`**；
  讲义的关键代码常放在**图片**里 —— 用 `lark-cli docs +media-download --token <file_token> --output '<Windows路径>'` 下载后可直接看

## 进度

- **day11 已完成并推送**（commit `85f67ec`）：优惠券缓存 + 异步领券(MQ) + 异步兑换码 + LUA 脚本，含资源 `resources/lua/*.lua`
- **day12 进行中**
  - ✅ 阶段0：10 个课前资料类已拷入（`strategy/discount` 6 个 + `PermuteUtil` + `tj-api` 3 个 DTO）；
    Seata TC 可用；`tj_promotion.undo_log` 已建
  - ✅ 第2章 智能推荐：`DiscountServiceImpl` 全写完（查券→初筛→细筛→全排列→并行→选最优），`CouponDiscountDTO` 已加 `discountDetail`
  - ✅ 3.1 promotion 侧：`queryDiscountDetailByOrder` + `queryCouponByUserCouponIds`（Mapper+XML）
  - ✅ 3.2 核销：`writeOffCoupon`（`CouponMapper.incrUsedNum`）
  - ✅ 3.3 promotion 侧：`refundCoupon`（`CouponMapper.decrUsedNum`）
  - ✅ 3.4 promotion 侧：`queryDiscountRules`（Controller/Service/Impl，入参已判空防 `IN ()`）
  - ✅ **3.1.4/3.2.3/3.3.3/3.4.3**：新建 `tj-api/.../client/promotion/PromotionClient`（5 方法）+ `fallback/PromotionClientFallback`
    （★ 降级策略：**查类返回空；`writeOffCoupon`/`refundCoupon` 抛 `BizIllegalException`** —— 改类操作不能假装成功）
  - ✅ **3.1.5/3.2.4/3.3.4/3.4.4**：改 `tj-trade` 的 `OrderServiceImpl`
    （`placeOrder` 算优惠+核销、`cancelOrder` 退券、`queryOrderById` 查券规则、`packageOrderDetail` 加 `discountValue` 参）
  - ✅ **3.2.5/3.3.5 Seata**：tj-trade/tj-promotion 两个 pom 加依赖（照抄 tj-course）+ bootstrap.yml 引入 `shared-seata.yaml`
    + `placeOrder`/`cancelOrder` 加 `@GlobalTransactional`
  - ✅ **全量编译 26 个模块 BUILD SUCCESS**
  - ✅ **tj-promotion 实测全绿**（`/available`、`/discount`、`/rules`、`/use`、`/refund` 五个接口，
    含"过期券退成 EXPIRED"分支）—— 详见 `2026-10-06.md`
  - ❌ **未验证**：Seata 全局事务、tj-trade 的 placeOrder/cancelOrder/queryOrderById（跨服务）

## ★★ 虚拟机部署架构（`192.168.150.101`，主机名 `heima`）

**访问方式**（本机 hosts 已配）：
```
192.168.150.101  www.tianji.com      → 前端（虚拟机 nginx :80）
192.168.150.101  api.tianji.com      → 后端 API（nginx 反代到虚拟机网关 :10010）
192.168.150.101  manage/jenkins/git/nacos/mq/xxljob/es.tianji.com
```
**后端服务全部跑在 Docker 容器里**（不在本机！）：
- 容器名 `tj-<模块名>`（如 `tj-auth` / `tj-gateway` / `tj-promotion`）
- **镜像** `tj-<模块名>:latest`，容器内 jar 路径 **`/app/app.jar`**
- **entrypoint** `sh -c "java -jar $JAVA_OPTS /app/app.jar"`
- **网络** `heima-net`；环境变量含 `JAVA_OPTS` / `TZ=Asia/Shanghai`
- **容器内 JDK 11**（`/usr/local/openjdk-11`）
- 端口：gateway **10010** / auth 8081 / user 8082 / search 8083 / media 8084 / message 8085 /
  course 8086 / pay 8087 / trade 8088 / exam 8089 / learning 8090 / remark 8091 / promotion 8092 / data 8093
- **profile 用 `dev`**（服务都在虚拟机上，`nacos.discovery.ip: 192.168.150.101`）

**SSH**：`ssh root@192.168.150.101` **密码 `123321`**（本机 Git-Bash 的 ssh 不能自动输密码，
用 **paramiko** 脚本；venv python 在 `~/.workbuddy/binaries/python/envs/default`，已装 paramiko 5.0.0）

### ⚠️ 虚拟机资源很紧（7.8G 内存）
跑着 25+ 容器：es 600M / jenkins 550M / nacos 540M / seata 280M / xxljob 255M /
gogs / mysql / redis / mq / kibana + 14 个 tj-* 各 200~300M。
**一旦内存耗尽 → JVM 被 OOM 杀，但容器仍显示 `Up`**（因为 entrypoint 是 `sh -c`，java 死了 sh 还在）⚠️
- 排查手法：`docker exec <容器> sh -c 'ps -ef | grep -c [j]ava'`（Hmm — 镜像里**没有 `ps`**！改用 `ls /proc` 或看日志）
- **`docker start` 对"Up 但 java 已死"的容器【无效】**，必须 **`docker restart`** ★

### 🔴🔴 2026-10-06 查出的致命问题：**镜像里的 jar 装错了**
现象：**所有接口都出错**（前端 `AxiosError: Network Error`），排查后发现：
| 容器 | 日志里实际启动的 Application | jar 字节数 | 结论 |
|---|---|---|---|
| `tj-auth` | **`GatewayApplication`** | 59508349 | ❌ **装的是 gateway 的 jar** |
| `tj-gateway` | `GatewayApplication` | **59508349** | ✅（两者字节数完全相同 = 同一个 jar） |
| `tj-pay` | **`PromotionApplication`** | 88948871 | ❌ 装成了 promotion 的 jar |
| `tj-remark` | **`SearchApplication`** | 105064730 | ❌ 装成了 search 的 jar |
| `tj-user` | `UserApplication` | 73182189 | ✅ 正确 |
**⇒ 这三个容器起来后绑的是【别的服务的端口】**（如 tj-auth 绑 10010）⇒ 8081/8087/8091 自然不通；
**而且它们会以错误的服务名注册到 Nacos** ⇒ 网关转发全乱 ⇒ "所有接口都出错"。

**判定"容器里的 jar 对不对"的两个手法**（很有用）：
```bash
# ① 看日志里实际启动的应用类
docker logs tj-auth 2>&1 | grep -oE 'com[.]tianji[.][a-z]+[.][A-Za-z]+Application' | sort -u
# ② 比 jar 字节数（装错的两个容器字节数会完全相同）
docker exec tj-auth sh -c 'stat -c %s /app/app.jar'
```

**修法**（二选一）：
- **A（正路）**：查镜像构建脚本里 jar 的映射，**重新构建正确的镜像**
- **B（临时）**：从本机 `scp` 正确的 jar 上去 → `docker cp <jar> tj-auth:/app/app.jar` → `docker restart tj-auth`
  （`docker cp` 改的是容器可写层，`restart` 不会还原；但 `docker rm` + 重建会还原成错的 ❌）

## ★★ 在本机跑服务的正确姿势（踩过 4 个坑）—— **注意：和虚拟机的部署是两套，别混用**

**★ 坑 0（最关键）：必须用 `--spring.profiles.active=local`**
项目有两套 profile（各服务 `bootstrap-{profile}.yml`）：
- **`dev`**：所有服务部署在**虚拟机**上 → `nacos.discovery.ip: 192.168.150.101`
- **`local`**：**服务在本地跑**、中间件在虚拟机 → `nacos.discovery.ip: 192.168.150.1`
  （`192.168.150.1` 是本机的 VMware 虚拟网卡地址，`ipconfig` 能看到；虚拟机和本机都能访问）
**用错 profile 的后果**：服务把**虚拟机 IP** 注册进 Nacos ⇒ 网关按 Nacos 地址转发 ⇒
`ConnectException: Connection refused: /192.168.150.101:8081` ⇒ 前端报 **`AxiosError: Network Error`**。
（网关日志的判据：`加载auth服务地址成功，http://192.168.150.1:8081/jwks` + `加载jwk秘钥成功！`；
 错的时候是 `http://192.168.150.101:8081` + `加载jwk秘钥失败`）

**坑 1：必须加 `--add-opens`**（JDK 21 跑 Seata 1.5.1/cglib 3.1 和 MP 解析 lambda 都需要）
```
--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED
--add-opens java.base/java.lang.invoke=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED
--add-opens java.base/java.util.concurrent=ALL-UNNAMED --add-opens java.base/java.math=ALL-UNNAMED
--add-opens java.base/java.net=ALL-UNNAMED --add-opens java.base/java.text=ALL-UNNAMED
--add-opens java.base/java.time=ALL-UNNAMED
```

**坑 2：`--server.port` 必须显式加** —— 否则 Tomcat 会去绑 56342（`bootstrap.yml` 的端口被 Nacos 覆盖）

**坑 3：重新打包前必须先停服务** —— Windows 下运行中的 JVM 会**锁住 jar**，`mvn install` 的 repackage 会 FAILURE
（PowerShell：`Get-NetTCPConnection -LocalPort <port> -State Listen` 拿 PID → `Stop-Process -Force`；
 Git-Bash 里 `taskkill //F` 参数格式会失败）

**一键起全部服务**（会打印每个服务的 PID，末尾 `wait` 让进程常驻）：
```bash
cd /d/java/project111/tianji
JAVA="C:/Program Files/Java/latest/jdk-21/bin/java.exe"
JVM="<上面那 9 个 add-opens>"
LOGDIR="/c/Users/Administrator/AppData/Local/Temp/tj-logs"; mkdir -p "$LOGDIR"
up() { "$JAVA" $JVM -Xms200m -Xmx350m -jar "$(ls $1/target/*.jar|grep -v original|head -1)" \
        --server.port=$2 --spring.profiles.active=local > "$LOGDIR/$3.log" 2>&1 & }
up tj-auth/tj-auth-service 8081 auth ; up tj-user 8082 user ; up tj-message/tj-message-service 8085 message
up tj-course 8086 course ; up tj-search 8083 search ; up tj-media 8084 media ; up tj-exam 8089 exam
up tj-remark 8091 remark ; up tj-trade 8088 trade ; up tj-promotion 8092 promotion ; up tj-gateway 10010 gateway
wait
```

**服务端口**：gateway **10010** / auth 8081 / user 8082 / search 8083 / media 8084 / message 8085 /
course 8086 / pay 8087 / trade 8088 / exam 8089 / learning 8090 / remark 8091 / promotion 8092

**⚠️ 两个服务起不来（和业务无关，可跳过）**：
- **`pay`**：微信支付 `certificatesManager` 初始化失败（`下载平台证书返回状态码异常 401`、`商户证书序列号有误`）
  ⇒ 第三方凭证问题，本地没配有效证书
- **`learning`**：`ClassNotFoundException: com.tianji.learning.domain.po.Note`（`NoteMapper.xml` 引用了不存在的类）

**其他**：`mvn -o install` 全量会挂在 tj-media（缺 runtime 依赖）⇒ **去掉 `-o` 联网装**即可；
本地仓库在 `D:\develop\apache-maven-3.9.15\mvn_repo`；
**调 `/user-coupons/**` 必须带 `user-info: <用户id>` header**（否则 401）；
**登录接口**：`POST http://localhost:10010/as/accounts/login`，
body 需要 **`type`（1=密码登录，@NotNull）** + `username`/`cellPhone` + `password`；
测试账号 `jack` / `13500010003`。

## 关键约定（反复踩过的坑）

- **金额单位是「分」**，一律 `int`
- `queryMyCoupons` / `queryCouponByUserCouponIds` 里的 **`uc.id AS creater`** ——
  `coupon.getCreater()` 拿到的是**用户券id**，不是"创建人"
- `UserCouponStatus`（UNUSED=1/USED=2/EXPIRED=3）的 `value` 上有 **`@EnumValue`**
  ⇒ XML 里 `#{status}` 传枚举会自动转成 int
- 策略模式：`Discount` 接口 + 4 个实现 + `DiscountStrategy`（EnumMap 工厂）
- **`CollUtils extends CollectionUtil`（hutool）** ⇒ `intersection` 等方法是继承来的，**存在**
- Spring Boot **2.7.2 默认禁循环依赖**，`tj-promotion` 没配 `allow-circular-references`
- `tj-trade` 的 `Order` / `OrderDetail` 已有 `discountAmount`、`realAmount`、`couponIds`、`realPayAmount`
  ⇒ **做 3.1.5 不用改表**
- **`IN` 是集合语义**：`WHERE id IN (100,100,100)` 只命中一行 —— 需要"按次数累加/累减"时不能用 `IN`

### 📌 定论：`incrUsedNum(couponIds, -1)` 这个批量能不能用？
**语法上能跑**（`IN (200,200)` 合法）。**语义上准不准，取决于入参里有没有"指向同一 coupon 的多张用户券"**：
- 有 ⇒ `IN` 集合语义只命中一行 ⇒ `used_num` **少减**（核销时也少加，成对时误差抵消）
- **讲义没有做任何保证** —— 它默认了"入参里没有重复的 coupon_id"，但**没有任何代码校验**，这依赖调用方行为
- **`refundCoupon` 是对外接口，入参由调用方决定 ⇒ 代码不该假设它不重复**
- **结论：逐个 `decrUsedNum` 对任意入参都正确，是更稳的写法**（`refundCoupon` 现状即为逐个，保持即可）
- 想用批量又想准 → 得在方法内**按 `couponId` 分组统计次数**（`JOIN + GROUP BY + COUNT`），不能信任调用方

## 用户偏好（重要）

- **他是学习者**：默认「我讲思路 → 他写 → 我 review」；**说"帮我改"才动手**；
  **不要主动新建额外产物**（报告/汇总/临时脚本）
- **明确说过「别用讲义那种代码」** = 不喜欢 `.stream().filter().map().collect()` 链式；
  **保留他直白的 `for` 循环风格**
- 他连续纠正过我 3 次（`intersection` 是否存在、`IN` 会不会重复、拿 `availableCouponMap` 论证别的方法）
  ⇒ **凡"存不存在 / 会不会重复 / 能不能编译"，必须先验证，不许靠推理；结论要标【已确认】或【推测】**

## 待验证（虚拟机上电后必做）

1. `queryCouponByUserCouponIds` 的 SQL 真能跑（`<foreach>` 展开 IN + `#{status}` 传枚举）
2. 真实数据里有没有"同一 coupon 对应多张用户券"（影响 `used_num` 的加减）
3. `writeOffCoupon` / `refundCoupon` 实测（状态、有效期、`used_num`）
4. `/user-coupons/available` + `/discount` 端到端实测
