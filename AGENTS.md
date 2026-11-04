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
| 求解器 | Google OR-Tools（Java） |
| 编排 | Spring AI Alibaba Graph（StateGraph） |
| LLM | DeepSeek（flash 档位）/ 硅基流动（统一 ChatClient 抽象，先跑通一家） |
| 前端 | Vue 3（独立工程 `tripmap-front`，非 Maven 模块） |
| 接口文档 | springdoc-openapi（Swagger UI） |
| 外部服务 | 高德开放平台（POI + 路径规划）；天气候选和风、搜索候选博查（非阻塞，随任务再定） |

## 3. 文档索引

新增设计/说明文档时**必须**登记到此处。

| 文档 | 说明 |
| --- | --- |
| [tripmap_MVP设计说明书.md](tripmap_MVP设计说明书.md) | 唯一设计准绳（v1.0 已通过），所有实现以它为准 |
| [TASKS.md](TASKS.md) | 任务拆解与执行顺序（阶段 0 → M1~M4 → M5 评测），开发按此推进并勾选 |
| [AGENTS.md](AGENTS.md) | 本文件 |

## 4. 项目结构

后端 Maven 多模块，**依赖方向只允许自上而下**，禁止反向依赖、禁止跨层跳跃；`tripmap-server` 是唯一可启动/装配模块。

```
tripmap/
├── pom.xml                    # 父 POM：统一依赖版本与模块管理
├── tripmap-common/            # DTO、枚举、常量、统一返回体、异常、通用工具（零业务依赖）
│   └── src/main/java/.../common/
├── tripmap-dal/               # 数据层：entity、mapper/repository、通用 DDL 脚本（方言中立）
│   └── src/main/java/.../dal/{entity,mapper}
├── tripmap-tool/              # 工具层：六个工具 + 外部 API 客户端（高德/天气/搜索/LLM 适配）
│   └── src/main/java/.../tool/{poi,weather,commute,cluster,tsp,search}
├── tripmap-graph/             # 编排层：ChatClient 封装、StateGraph 定义、各节点实现、评测与回退
│   └── src/main/java/.../graph/{node,edge,state,eval}
├── tripmap-server/            # 接入层：controller、SSE、全局配置、启动类
│   └── src/main/java/.../server/{controller,config}
├── tripmap-front/             # Vue 3 前端（独立 npm 工程，不参与 Maven 构建）
└── sql/                       # 建表 DDL（方言中立，见 TASKS 1.2）
```

包结构约定：各模块内部按 `controller / service / mapper / entity / dto / config / util` 划分，新代码必须归入既有包，不得新建平行顶层包。

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
- 统一返回体与全局异常处理（已在阶段 0 建立），新增接口不得各自造返回格式
- 接口文档统一走 springdoc-openapi（Swagger UI：/swagger-ui/index.html）；新增 Controller 接口**必须**加 Swagger 注解描述：`@Tag`（类级分组）、`@Operation`（接口摘要）、`@Parameter`（参数说明）；请求/响应 DTO 字段加 `@Schema` 说明，禁止裸接口入库
- 外部 API 客户端必须带超时、重试（指数退避）、限流；高德调用遵守 QPS
- 每个工具/节点实现必须配单测；真实外部调用测试打 `@Tag("integration")` 标签，默认跳过
- 注释与提交信息使用中文，关键算法（聚类锚定、双开放路径、回退路由）处写明设计说明书章节出处

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
