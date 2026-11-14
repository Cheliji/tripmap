-- tripmap poi 表 DDL（任务 1.2.1，字段口径见 tripmap_MVP设计说明书 8.1 + tripmap-poi功能设计说明书 §5.3）
-- 执行前置：000_create_database.sql（库 tripmap，utf8mb4）
-- 方言中立口径：本脚本以 MySQL 8 可执行版为准；PostgreSQL 迁移差异已用【PG】注释登记（任务 1.2.5 清单同步维护）

USE tripmap;

CREATE TABLE IF NOT EXISTS poi (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    amap_id          VARCHAR(64)   NOT NULL COMMENT '高德POI ID，业务唯一键（功能三 UPSERT 依据，功能设计说明书 §5.3）',
    name             VARCHAR(255)  NOT NULL COMMENT 'POI名称',
    type             VARCHAR(128)  NULL COMMENT '高德原始分类（分号多段）',
    typecode         VARCHAR(64)   NULL COMMENT '高德分类编码（可能多值，竖线分隔；保留供重映射/溯源）',
    keytag           VARCHAR(64)   NULL COMMENT '特征标签（如5A景区）',
    level            VARCHAR(16)   NULL COMMENT '景区等级（AAAAA等）',
    city             VARCHAR(64)   NOT NULL COMMENT '城市',
    district         VARCHAR(64)   NULL COMMENT '行政区名（高德adname）',
    district_adcode  INT           NULL COMMENT '区县行政区划码 GB/T 2260',
    longitude        DECIMAL(10,6) NOT NULL COMMENT '经度 GCJ-02（空间计算在代码层，不用空间类型，设计说明书8.1）',
    latitude         DECIMAL(10,6) NOT NULL COMMENT '纬度 GCJ-02',
    category         VARCHAR(32)   NULL COMMENT '项目内分类（8枚举中文标签，PoiCategory）',
    tags             JSON          NULL COMMENT '标签数组（受控词表闭集20个，PoiTagVocab；不参与SQL过滤，设计说明书8.1）',
    rating           DECIMAL(3,1)  NULL COMMENT '高德评分',
    cost             DECIMAL(10,2) NULL COMMENT '人均消费（元）',
    ticket_price     DECIMAL(10,2) NULL COMMENT '门票价格，0=免费',
    suggest_duration INT           NULL COMMENT '建议游玩时长（分钟），检索硬过滤字段（设计说明书8.1消费拓扑）',
    open_hours       JSON          NULL COMMENT '结构化开放时段：mon~sun七键，[]=闭馆，null/整列空=未知（§4.6三态语义；仅服务闭馆预检/时间窗剪枝/硬校验，不做检索条件）',
    opentime2        TEXT          NULL COMMENT '高德原始营业说明全文（open_hours 展示兜底）',
    brief            TEXT          NULL COMMENT '简介（客观概述，兼作候选精筛素材，§4.7）',
    address          VARCHAR(512)  NULL COMMENT '地址',
    tel              VARCHAR(64)   NULL COMMENT '电话',
    photos           TEXT          NULL COMMENT '图片URL（分号分隔）',
    official_account VARCHAR(128)  NULL COMMENT '景点公众号名称',
    remark           VARCHAR(512)  NULL COMMENT 'AI生成字段声明（运行时LLM时效性说明依据，§4.1 v1.4）',
    source           VARCHAR(32)   NOT NULL DEFAULT '高德+AI增强' COMMENT '数据来源（与8.1"高德/人工"枚举兼容）',
    status           TINYINT       NOT NULL DEFAULT 1 COMMENT '1正常 2闭园 3下架（检索硬过滤字段）',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_amap_id (amap_id),
    KEY idx_city_status (city, status),
    KEY idx_district (district_adcode)
) COMMENT='POI景点表';

-- 【PG】迁移差异登记（任务 1.2.5 清单同步）：
--   1. AUTO_INCREMENT            → GENERATED ALWAYS AS IDENTITY 或 bigserial
--   2. ON UPDATE CURRENT_TIMESTAMP → PG 无此特性，用触发器或应用层维护 updated_at
--   3. UNIQUE KEY / KEY 索引语法  → 内联 PRIMARY KEY(amap_id) 约束 + CREATE INDEX 语句
--   4. JSON 类型                  → PG 用 jsonb（注意 MySQL JSON 与 jsonb 函数语法差异，JSON 内容校验收敛在代码层，见 §4.6）
--   5. 功能三生成的 ON DUPLICATE KEY UPDATE → ON CONFLICT (amap_id) DO UPDATE SET ...（§5.1 方言注记）
