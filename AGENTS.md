# AGENTS.md — tripmap

> 本文件是 AI 编码助手（vibe coding）在本仓库工作的**最高行为约束**。
> 冲突时优先级：本文件 > TASKS.md > 设计说明书。

---

## 1. 项目是做什么的

tripmap 是一个以 **Agent 编排**为核心的旅游行程规划系统：用户用自然语言描述出行背景，系统自动产出结构化的多日行程（每日"一天一个区域"）与旅游攻略。

**核心设计原则（不得违背）：**

1. **几何正确性由算法与外部 API 保证，不由模型保证**——路径、聚类、通勤全部来自确定性代码；
2. **事实全部来自工具**——模型只能看到工具签名，景点存在性、天气、开放时间等一律走工具/API，严禁让 LLM 凭空生成事实数据；
3. **评测双保险**——五项硬校验（代码执行、零 Token）+ LLM 软评分，失败局部回退修复，禁止整轮重跑；
4. **攻略只叙述、不改序**——攻略生成节点按已定顺序输出，不得增删或调换景点。

## 2. 技术栈（已锁定，不得更换）

| 项 | 选型 |
| --- | --- |
| 语言 / 运行时 | Java 17 + Spring Boot 3 |
| 构建 | Maven 多模块 |
| 数据库 | MySQL 8（DDL 必须方言中立，后续平滑迁移 PostgreSQL） |
| 缓存 | Redis |
| 求解器 | 纯 Java 精确枚举（路径排序：全排列 n!/2 去对称 + 时间窗剪枝，n≤8 毫秒级且数学最优） |
| 编排 | Spring AI Alibaba Graph（StateGraph） |
| LLM | DeepSeek（flash 档位）/ 硅基流动（统一 ChatClient 抽象，先跑通一家） |
| 前端 | Vue 3（独立工程 `tripmap-front`，非 Maven 模块） |
| 接口文档 | springdoc-openapi（Swagger UI） |
| 外部服务 | 高德开放平台（POI 采集 + 路径规划）；天气候选和风（非阻塞，随任务再定）；联网搜索已定为 DeepSeek 官方联网搜索（`POST https://api.deepseek.com/v1/tools/search`，不使用博查） |

## 3. 文档索引

新增设计/说明文档时**必须**登记到此处。

| 文档 | 说明 |
| --- | --- |
| [tripmap_MVP设计说明书.md](tripmap_MVP设计说明书.md) | 唯一设计准绳（v1.0 已通过），所有实现以它为准 |
| [tripmap-poi功能设计说明书.md](tripmap-poi功能设计说明书.md) | tripmap-poi 离线 POI 数据加工模块（采集/增强/入库三脚本）的唯一实现依据 |
| [TASKS.md](TASKS.md) | 任务拆解与执行顺序（阶段 0 → M1~M4 → M5 评测），开发按此推进并勾选 |
| [AGENTS.md](AGENTS.md) | 本文件 |

## 4. 项目结构

后端 Maven 多模块，**依赖方向只允许自上而下**，禁止反向依赖、禁止跨层跳跃；`tripmap-server` 是唯一 Spring Boot 装配/运行模块；`tripmap-poi` 是离线数据加工脚本模块（main 方法直跑、零 Spring，不参与运行时，见《tripmap-poi功能设计说明书》）。

```
tripmap/
├── pom.xml                    # 父 POM：统一依赖版本与模块管理
├── tripmap-common/            # DTO、枚举、常量、统一返回体、异常、通用工具（零业务依赖）
│   └── src/main/java/.../common/
├── tripmap-dal/               # 数据层：entity、mapper/repository、通用 DDL 脚本（方言中立）
│   └── src/main/java/.../dal/{entity,mapper}
├── tripmap-tool/              # 工具层：六个工具 + 外部 API 客户端（高德/天气/搜索/LLM 适配）
│   └── src/main/java/.../tool/{poi,weather,commute,cluster,tsp,search}
├── tripmap-poi/               # 离线 POI 数据加工（零 Spring 脚本：采集/增强/入库，不参与运行时）
│   └── src/main/java/.../poi/{collect,enrich,importdb,excel,util}
├── tripmap-graph/             # 编排层：ChatClient 封装、StateGraph 定义、各节点实现、评测与回退
│   └── src/main/java/.../graph/{node,edge,state,eval}
├── tripmap-server/            # 接入层：controller、SSE、全局配置、启动类
│   └── src/main/java/.../server/{controller,config}
├── tripmap-front/             # Vue 3 前端（独立 npm 工程，不参与 Maven 构建）
└── sql/                       # 建表 DDL（方言中立，见 TASKS 1.2）
```

包结构约定：各模块内部按 `controller / service / mapper / entity / dto / config / util` 划分，新代码必须归入既有包，不得新建平行顶层包；`tripmap-poi` 例外，按 `{collect,enrich,importdb,excel,util}` 划分（脚本模块，无 controller/service 语义）。

## 5. 硬性禁止清单（AI 最容易自作主张的点）

- ❌ 不引入 Flyway/Liquibase 等迁移工具（迁 PG 时再统一决策）
- ❌ 不使用 Caffeine 等本地缓存（一律 Redis）
- ❌ 不建任何知识条目表 / 向量库 / RAG；城市介绍、攻略、注意事项全部走联网搜索
- ❌ 不做地图渲染、酒店推荐、大交通查询
- ❌ MVP 阶段只支持杭州，不得采集/接入其他城市数据
- ❌ 不允许 LLM 直接生成景点/天气/开放时间等事实数据（必须走工具）
- ❌ 不得引入设计说明书 2.2 节"不做"清单之外的隐性功能
- ❌ 密钥/API Key 禁止硬编码或入库，一律环境变量注入

## 6. 编码约束

- LLM 调用一律经统一 ChatClient 封装，结构化输出必须做 **ID 回库校验**，清单外 ID 直接拒绝
- 统一返回体与全局异常处理（任务 0.7 已建立），新增接口不得各自造返回格式；格式、码段与用法见第 6.1 节
- 接口文档统一走 springdoc-openapi（Swagger UI：/swagger-ui/index.html）；新增 Controller 接口**必须**加 Swagger 注解描述：`@Tag`（类级分组）、`@Operation`（接口摘要）、`@Parameter`（参数说明）；请求/响应 DTO 字段加 `@Schema` 说明，禁止裸接口入库
- 外部 API 客户端必须带超时、重试（指数退避）、限流；高德调用遵守 QPS
- 每个工具/节点实现必须配单测；真实外部调用测试打 `@Tag("integration")` 标签，默认跳过
- `tripmap-poi` 离线脚本不注册 Spring Bean、不走统一返回体；其中 LLM 仅允许对**联网搜索取证素材**做结构化整理，事实字段（开放时间等）必须带 source 且经人工校对后入库（《tripmap-poi功能设计说明书》§4）
- 注释与提交信息使用中文，关键算法（聚类锚定、双开放路径、回退路由）处写明设计说明书章节出处
- **普通 POJO/DTO/数据载体类一律使用 Lombok 消除样板代码，禁止手写 getter/setter/toString/equals/hashCode**：典型组合 `@Data + @Builder + @NoArgsConstructor + @AllArgsConstructor`（EasyExcel/MyBatis/Spring 反射实例化依赖无参构造，凡加 `@Builder` 必须同时补 `@NoArgsConstructor`/`@AllArgsConstructor`）；含敏感字段（如 apiKey）的类改用 `@Getter`/`@Setter`，避免 `@Data` 默认 toString 泄露密钥；不可变数据载体优先 Java `record`（record 不需要 Lombok）。Lombok 由父 POM 统一 provided 引入（版本走 Spring Boot BOM），子模块**禁止单独声明版本或 scope**

### 6.1 统一返回体 / 异常 / 请求日志（任务 0.7 建立，所有接口必须遵守）

**统一返回体**：所有 Controller 接口返回 `com.tripmap.common.result.Result<T>`，禁止裸业务对象/裸 Map 直接返回。

```json
{ "code": 0, "message": "ok", "data": { }, "traceId": "a1b2c3d4e5f6" }
```

- `code`：number，`0` 成功、非 `0` 失败；前端以 `resp.code === 0` 判断；**错误也返回 HTTP 200**，HTTP 状态码不承载错误语义
- `message`：成功固定 `"ok"`；失败为可读中文，由后端控制，禁止透出框架原始英文异常/堆栈
- `data`：失败时恒为 `null`；成功用 `Result.ok(data)`，无数据用 `Result.ok()`
- `traceId`：由 `TraceIdFilter`/`ResultTraceIdAdvice` 自动填充，报障凭此查日志，代码中不要手动 set

**错误码（`com.tripmap.common.result.ResultCode`，枚举保证唯一，新增码值必须落在既有码段内）**：

| 码段 | 含义 | 已有码 |
| --- | --- | --- |
| `0` | 成功 | — |
| `1xxxxx` | 客户端/参数错误 | 100001 校验失败 / 100002 参数缺失 / 100003 格式非法 / 100004 请求方式不支持 |
| `2xxxxx` | 资源错误 | 200001 资源不存在（含 404 路径） |
| `3xxxxx` | 业务规则（预期内拒绝） | 300001 城市不支持（意图拒绝 R1 复用）/ 300002 规划不可行 |
| `4xxxxx` | 外部服务（可重试） | 400001 高德 / 400002 天气 / 400003 搜索 / 400004 LLM |
| `5xxxxx` | 系统异常（兜底，对外脱敏） | 500001 未捕获异常 |

**异常分层（阿里手册思路收敛为三层，非受检异常，不用异常控制流程）**：

| 场景 | 抛什么 | 日志级别 | 全局处理行为 |
| --- | --- | --- | --- |
| 预期内业务失败 | `BizException(ResultCode)`（可覆写 message） | warn，无堆栈 | 按码原样返回 |
| 外部 API 失败 | `ExternalApiException(ResultCode, supplier, retryable)` | retryable→warn；否则 error 带堆栈 | 按码返回，message 脱敏 |
| 未捕获异常 | 不用抛，兜底 | error 全堆栈 | 固定 500001 + "系统繁忙，请稍后重试" |

**关键类位置**：
- `tripmap-common`：`common/result/Result`、`ResultCode`；`common/exception/BizException`、`ExternalApiException`（common 零 web 依赖，不引入 Spring）
- `tripmap-server` `server/config/`：`GlobalExceptionHandler`、`TraceIdFilter`（入口生成/透传 `X-Trace-Id`，写 MDC 与响应头）、`ResultTraceIdAdvice`、`RequestLogAspect`（controller 包切面：info/warn/error 三级，输出 code+耗时）
- 404 走统一返回体依赖 `application.yml` 的 `spring.mvc.throw-exception-if-no-handler-found=true` + `spring.web.resources.add-mappings=false`（已配置，勿删）

**已知坑（勿重蹈）**：`ResponseBodyAdvice` 若用 `@RestControllerAdvice(basePackages=...)` 限定包，`@ExceptionHandler` 的返回会按"异常处理类自身"判定包归属而漏掉增强，导致错误响应 traceId 为 null——本仓库该 Advice 不限定包，靠 `body instanceof Result` 判断。

## 7. 工作流（每次会话）

1. 打开 [TASKS.md](TASKS.md)，按依赖顺序取**一个**未勾选任务；
2. 实现 + 自测（该任务相关单测通过）；
3. 在 TASKS.md 勾选该任务；
4. 本地 git 提交（见第 8 节）；
5. 用户确认后再进行下一任务。

## 8. Git 规则

- **每次修改操作完成后必须本地提交**；`git commit` 即可，**禁止 `git push`**（除非用户明确要求）
- 提交格式（约定式提交，中文描述）：`feat: ...` / `fix: ...` / `chore: ...` / `docs: ...` / `test: ...`
- 一个任务对应一个提交；改动跨模块时可拆分为多个逻辑提交
- 禁止 `--force` 改写已提交历史；禁止提交密钥、日志、构建产物（见 `.gitignore`）
- **所有项目设计文档一律不入库**，统一加入 `.gitignore`（如 `TASKS.md`、`tripmap_MVP设计说明书.md`，并对后续新增设计文档沿用此规则）；已入库的设计文档需 `git rm --cached` 移出跟踪
