package com.tripmap.tool.spi;

import com.tripmap.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工具统一接口契约（任务 2.1.1）：泛型约束、name/execute 约定与工具名集合唯一性。
 */
class TripToolContractTest {

    /** 契约验证用的桩输入 */
    private record StubInput(String value) implements ToolInput {
    }

    /** 契约验证用的桩输出 */
    private record StubOutput(String value) implements ToolOutput {
    }

    private static class StubTool implements TripTool<StubInput, StubOutput> {
        @Override
        public String name() {
            return ToolNames.POI_SEARCH;
        }

        @Override
        public StubOutput execute(StubInput input) {
            if (input.value().isBlank()) {
                // 预期内业务失败必须抛 BizException（接口异常约定）
                throw new BizException(com.tripmap.common.result.ResultCode.PARAM_VALIDATION_FAILED);
            }
            return new StubOutput("ok:" + input.value());
        }
    }

    @Test
    void 工具实现按接口调用且不感知实现类型() {
        TripTool<StubInput, StubOutput> tool = new StubTool();
        assertEquals(ToolNames.POI_SEARCH, tool.name());
        assertEquals("ok:hello", tool.execute(new StubInput("hello")).value());
    }

    @Test
    void 预期内业务失败抛BizException而非返回Null() {
        TripTool<StubInput, StubOutput> tool = new StubTool();
        assertThrows(BizException.class, () -> tool.execute(new StubInput(" ")));
    }

    @Test
    void 工具名集合六个且唯一() {
        assertEquals(6, ToolNames.all().size());
        // 二次获取不可变且内容一致
        assertEquals(ToolNames.all(), ToolNames.all());
    }
}
