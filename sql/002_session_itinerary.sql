-- tripmap 会话与行程表 DDL（任务 1.2.2 / 1.2.3）
-- 设计依据：tripmap_MVP设计说明书 §3.4（编排主流程）、§7（评测与回退）、§8.2、§12；
--           session/itinerary 表结构为 2026-11 讨论定稿（见 TASKS 1.2.2/1.2.3 条目）
-- 分工原则：
--   session            = 会话"档案卡"：现在处于什么大阶段，一读便知（不存过程数据）
--   session_checkpoint = 图执行快照：每节点一条追加式记录，恢复/回滚依据（M3 接入 StateGraph，可清理）
--   session_message    = 对话"实录"：人说了什么、系统答了什么（SSE 进度事件不入库）
--   itinerary*         = 交付"产品"：单版本设计（评测回退是流程内动作，重跑即覆盖，次数记 session.plan_count）
-- 说明：全表不使用物理外键（与 001_poi.sql 口径一致：离线批量写入与重跑脚本不被约束卡死；PG 迁移少一项行为差异核对）。
--       表间关联为逻辑外键，靠字段命名（xxx_id）+ 应用层维护 + 索引保证；本脚本可重复执行（IF NOT EXISTS 幂等）。
-- 执行前置：000_create_database.sql、001_poi.sql（本脚本已随 1.2.2/1.2.3 在本地 tripmap 库实际执行）
-- 方言中立口径：本脚本以 MySQL 8 可执行版为准；PostgreSQL 迁移差异已用【PG】注释登记（任务 1.2.5 清单同步维护）

USE tripmap;

-- ---------------------------------------------------------------------------
-- 会话表
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，贯穿关联表与前端请求',
    user_id           BIGINT       NULL COMMENT '用户ID（MVP无登录体系，预留；空=匿名）',
    city              VARCHAR(32)  NOT NULL COMMENT '目的地（意图拒绝R1也建会话并记录，用于统计被拒流量）',
    background_schema JSON         NULL COMMENT '背景Schema当前定稿（§12：人员/日期/预算/偏好/必去点）；checkpoint中background通道的"已确认投影"，checkpoint可清理此字段不可丢',
    stage             VARCHAR(16)  NOT NULL DEFAULT 'COLLECTING' COMMENT '会话阶段：COLLECTING信息收集(可中断恢复)/PLANNING执行中(防重复提交)/DONE已产出/FAILED重试3次仍失败',
    plan_count        INT          NOT NULL DEFAULT 0 COMMENT '行程制作次数（含评测回退重跑，评测报告统计用）',
    itinerary_id      BIGINT       NULL COMMENT '当前有效行程id，逻辑指针不设外键（与itinerary.session_id互指，避免循环外键）',
    client_trace_id   VARCHAR(32)  NULL COMMENT '首个请求X-Trace-ID，跨表排查线索',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间，会话列表"最近对话"排序依据',
    PRIMARY KEY (id),
    KEY idx_user_updated (user_id, updated_at)
) COMMENT='规划会话表';

-- ---------------------------------------------------------------------------
-- 图执行快照表（StateGraph checkpoint 落库，M3 任务 3.4.3 接入）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_checkpoint (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id  BIGINT      NOT NULL COMMENT '会话id',
    seq         INT         NOT NULL COMMENT '会话内递增序号，最新一行即当前现场',
    node        VARCHAR(64) NOT NULL COMMENT '最后完成的节点名；正在执行的节点是运行时瞬态不落库，由next_nodes[0]推得',
    next_nodes  JSON        NOT NULL COMMENT '接下来执行的节点列表',
    state_blob  JSON        NOT NULL COMMENT '完整图状态序列化：含背景/候选/聚类结果/usage累计等持久通道；通勤矩阵/天气等可重算数据为瞬态通道不入此列（恢复时空白即重跑，Redis缓存兜底）',
    created_at  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session_seq (session_id, seq)
) COMMENT='图执行checkpoint表（追加式，支持评测回退回放；过期可清理）';

-- ---------------------------------------------------------------------------
-- 会话消息表（对话实录；SSE进度事件为瞬态推送不入此表）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS session_message (
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id BIGINT      NOT NULL COMMENT '会话id',
    role       VARCHAR(16) NOT NULL COMMENT '角色：user/assistant/system',
    msg_type   VARCHAR(16) NOT NULL COMMENT '消息类型：text纯文本/component组件追问/result结果卡',
    content    TEXT        NULL COMMENT '正文（展示用）',
    payload    JSON        NULL COMMENT '消息级结构数据：组件结构、结果卡数据等（一块消费不局部改，JSON合理）',
    node_name  VARCHAR(64) NULL COMMENT '产出该消息的图节点名（M3评测回退排查用）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_session_time (session_id, created_at)
) COMMENT='会话消息表';

-- ---------------------------------------------------------------------------
-- 行程表（单版本：session 1:1；评测回退产生新计划时删旧插新，级联清空天/站点数据）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS itinerary (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id     BIGINT        NOT NULL COMMENT '会话id，业务唯一（单版本设计）',
    title          VARCHAR(64)   NULL COMMENT '摘要标题，结果页顶部用（如"杭州2日游·西湖—西溪"）',
    day_count      INT           NOT NULL COMMENT '行程天数（硬校验#2日期覆盖的SQL可查依据）',
    start_date     DATE          NOT NULL COMMENT '行程开始日期',
    end_date       DATE          NOT NULL COMMENT '行程结束日期',
    total_cost_est DECIMAL(10,2) NULL COMMENT '门票+餐饮+市内交通估算合计（硬校验#1结论；明细在eval_result）',
    total_tokens   JSON          NULL COMMENT '各LLM节点token消耗明细（成本归因，M5评测用）',
    eval_result    JSON          NULL COMMENT '最终版评测证据包：五项硬校验逐项明细+LLM软评分（整块读出，JSON合理）',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_session (session_id)
) COMMENT='行程表（单版本）';

-- ---------------------------------------------------------------------------
-- 行程天表（一天一行）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS itinerary_day (
    id                   BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    itinerary_id         BIGINT      NOT NULL COMMENT '行程id',
    day_seq              TINYINT     NOT NULL COMMENT '第几天，会话内有序',
    date                 DATE        NOT NULL COMMENT '当日日期（硬校验#2/#3直接依据）',
    area_summary         VARCHAR(64) NULL COMMENT '当日区域一句话（如"西湖环线"），天卡片标题',
    weather_snapshot     JSON        NULL COMMENT '当日天气快照{temp,condition,precipitation,degraded}；存快照因展示的是"规划时点的预报"',
    hotel_hint           VARCHAR(128) NULL COMMENT '终点商圈/枢纽提示（§11"建议住宿区域"）',
    substitute_poi_ids   JSON        NULL COMMENT '当日替补景点id清单（§6.4每簇1-2个，清单整块读）',
    total_commute_minutes INT        NULL COMMENT '当日通勤合计（"今日路上约X分钟"）',
    created_at           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_itinerary_day (itinerary_id, day_seq)
) COMMENT='行程天表';

-- ---------------------------------------------------------------------------
-- 日内站点表（一天内按访问顺序的景点站位）
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS itinerary_day_poi (
    id                    BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    day_id                BIGINT       NOT NULL COMMENT '行程天id',
    seq                   TINYINT      NOT NULL COMMENT '访问顺序（unique约束使"改序"在物理上无法静默发生，§1原则4数据层保证）',
    poi_id                BIGINT       NOT NULL COMMENT 'POIid，外键；开放时间冲突校验（硬校验#3）可SQL下推',
    poi_name_snapshot     VARCHAR(128) NOT NULL COMMENT '名称冗余快照：POI改名/下线后历史行程仍可读',
    arrive_time           TIME         NULL COMMENT '预计到达时刻（时间线组装节点产物）',
    depart_time           TIME         NULL COMMENT '预计离开时刻',
    play_duration_minutes INT          NULL COMMENT '含1.2倍缓冲的游玩占用（§6.4）',
    commute_prev_minutes  INT          NULL COMMENT '与上一站通勤分钟数，首站为0',
    wait_minutes          INT          NULL COMMENT '提前到达等开门耗时（§6.3时间窗剪枝：等待计入耗时）',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_day_seq (day_id, seq),
    KEY idx_poi (poi_id)
) COMMENT='行程日内站点表';

-- ---------------------------------------------------------------------------
-- 表间逻辑关联一览（无物理外键，应用层保证）：
--   session_checkpoint.session_id / session_message.session_id / itinerary.session_id → session.id
--   itinerary_day.itinerary_id → itinerary.id
--   itinerary_day_poi.day_id → itinerary_day.id；itinerary_day_poi.poi_id → poi.id（引用完整性由应用层校验）

-- 【PG】迁移差异登记（任务 1.2.5 清单同步）：
--   1. AUTO_INCREMENT → GENERATED ALWAYS AS IDENTITY；TINYINT → SMALLINT（PG无TINYINT）
--   2. ON UPDATE CURRENT_TIMESTAMP → PG无此特性，用触发器或应用层维护 updated_at
--   3. 索引/唯一键语法 → 内联约束 + CREATE INDEX；TIME/DATE/JSON → jsonb（JSON内容校验收敛在代码层）
--   4. session 为 PG 保留字（非保留，可用但不推荐）→ PG 侧建议改名 session_ 或加引号，迁移时统一处理
