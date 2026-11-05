-- tripmap 数据库初始化脚本（任务 0.5）
-- 说明：仅负责建库；业务表 DDL（方言中立）在任务 1.2 统一编写，迁 PostgreSQL 时再统一引入迁移工具
CREATE DATABASE IF NOT EXISTS tripmap
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;
