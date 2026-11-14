package com.tripmap.dal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 会话消息表实体（表 session_message，见《tripmap数据库设计说明书》§3.4）。
 * role：user/assistant/system；msgType：text/component/result。
 * SSE 进度事件不入此表；token 消耗累计在 checkpoint 的 usage 通道。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_message")
public class SessionMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 角色：user/assistant/system */
    private String role;

    /** 消息类型：text纯文本/component组件追问/result结果卡 */
    private String msgType;

    /** 正文 */
    private String content;

    /** 消息级结构数据 JSON（组件结构、结果卡数据） */
    private String payload;

    /** 产出该消息的图节点名 */
    private String nodeName;

    private LocalDateTime createdAt;
}
