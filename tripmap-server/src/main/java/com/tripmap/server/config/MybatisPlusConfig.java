package com.tripmap.server.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus Mapper 扫描装配。
 *
 * <p>MyBatis 自动配置只扫描启动类所在包（com.tripmap.server）之下的
 * {@code @Mapper} 接口；DAL 模块的 Mapper 位于 com.tripmap.dal.mapper，
 * 必须显式 {@code @MapperScan} 声明，否则运行期/集成测试报
 * NoSuchBeanDefinitionException（任务 2.2 真实库验证时暴露此缺口）。</p>
 */
@Configuration
@MapperScan("com.tripmap.dal.mapper")
public class MybatisPlusConfig {
}
