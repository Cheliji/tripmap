package com.tripmap.tool.openhours;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * open_hours 校验器单测（tripmap-poi功能设计说明书 §4.6 三道防线之防线 1/2 共用实现）
 *
 * <p>覆盖三态语义：某天=[] 确定闭馆（合法）、时段数组确定开放（有序不重叠 open&lt;close）、
 * 某天=null 未知（合法）、整列空未知（合法）；格式/未知键/字段类型错误全部拒绝。</p>
 */
class OpenHoursValidatorTest {

    private static String validFull() {
        return """
                {"mon": [{"open": "09:00", "close": "17:00"}],
                 "tue": [],
                 "wed": [{"open": "09:00", "close": "12:00"}, {"open": "13:00", "close": "16:30"}],
                 "thu": [{"open": "09:00", "close": "17:00"}],
                 "fri": [{"open": "09:00", "close": "17:00"}],
                 "sat": [{"open": "08:30", "close": "17:30"}],
                 "sun": [{"open": "08:30", "close": "17:30"}],
                 "note": "周一闭馆（法定节假日除外）",
                 "special": [{"date": "2026-10-01", "open": "08:00", "close": "18:00", "note": "国庆假期"}]}""";
    }

    @Test
    void 整列为空即未知合法() {
        assertNull(OpenHoursValidator.validate(null));
        assertNull(OpenHoursValidator.validate(""));
        assertNull(OpenHoursValidator.validate("   "));
    }

    @Test
    void 完整示例通过() {
        assertNull(OpenHoursValidator.validate(validFull()));
    }

    @Test
    void 闭馆日与未知天合法() {
        assertNull(OpenHoursValidator.validate(
                "{\"mon\": [], \"tue\": null, \"wed\": [], \"thu\": null, \"fri\": [], \"sat\": null, \"sun\": []}"));
    }

    @Test
    void 七键必填缺键拒绝() {
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]}"));
        assertNull(OpenHoursValidator.validate(
                "{\"mon\": null, \"tue\": null, \"wed\": null, \"thu\": null, \"fri\": null, \"sat\": null, \"sun\": null}"));
    }

    @Test
    void 非法JSON拒绝() {
        assertNotNull(OpenHoursValidator.validate("not-json"));
    }

    @Test
    void 非对象拒绝() {
        assertNotNull(OpenHoursValidator.validate("[{\"mon\": []}]"));
    }

    @Test
    void 时段格式必须HHmm() {
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"9:00\", \"close\": \"17:00\"}]}"));
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"09:00\", \"close\": \"25:00\"}]}"));
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"09:00\", \"close\": \"17:60\"}]}"));
    }

    @Test
    void open必须早于close() {
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"17:00\", \"close\": \"09:00\"}]}"));
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"09:00\", \"close\": \"09:00\"}]}"));
    }

    @Test
    void 时段必须有序不重叠() {
        assertNotNull(OpenHoursValidator.validate(
                "{\"mon\": [{\"open\": \"13:00\", \"close\": \"15:00\"}, {\"open\": \"09:00\", \"close\": \"12:00\"}]}"));
        assertNotNull(OpenHoursValidator.validate(
                "{\"mon\": [{\"open\": \"09:00\", \"close\": \"12:00\"}, {\"open\": \"11:00\", \"close\": \"15:00\"}]}"));
        // 相邻不重叠合法（12:00 接 12:00）
        assertNull(OpenHoursValidator.validate(
                sevenKeys("[{\"open\": \"09:00\", \"close\": \"12:00\"}, {\"open\": \"12:00\", \"close\": \"15:00\"}]")));
    }

    /** 七键齐全的模板：只替换 mon 的值，其余六天为 null（未知） */
    private static String sevenKeys(String monValue) {
        return "{\"mon\": " + monValue + ", \"tue\": null, \"wed\": null, \"thu\": null,"
                + " \"fri\": null, \"sat\": null, \"sun\": null}";
    }

    @Test
    void 天值必须是数组或null() {
        assertNotNull(OpenHoursValidator.validate("{\"mon\": \"09:00-17:00\"}"));
        assertNotNull(OpenHoursValidator.validate("{\"mon\": 1}"));
    }

    @Test
    void 时段必须含open_close字符串() {
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": \"09:00\"}]}"));
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [{\"open\": 9, \"close\": \"17:00\"}]}"));
    }

    @Test
    void 未知键拒绝防LLM漂移() {
        assertNotNull(OpenHoursValidator.validate("{\"monday\": [{\"open\": \"09:00\", \"close\": \"17:00\"}]}"));
        assertNotNull(OpenHoursValidator.validate("{\"mon\": [], \"closed\": true}"));
    }

    @Test
    void note必须是字符串或null() {
        assertNull(OpenHoursValidator.validate(
                "{\"mon\": [], \"tue\": null, \"wed\": null, \"thu\": null, \"fri\": null,"
                        + " \"sat\": null, \"sun\": null, \"note\": null}"));
        assertNotNull(OpenHoursValidator.validate(
                "{\"mon\": [], \"tue\": null, \"wed\": null, \"thu\": null, \"fri\": null,"
                        + " \"sat\": null, \"sun\": null, \"note\": 123}"));
    }

    @Test
    void special必须是数组或null且内容不校验() {
        assertNull(OpenHoursValidator.validate(
                "{\"mon\": [], \"tue\": null, \"wed\": null, \"thu\": null, \"fri\": null,"
                        + " \"sat\": null, \"sun\": null, \"special\": null}"));
        assertNull(OpenHoursValidator.validate(
                "{\"mon\": [], \"tue\": null, \"wed\": null, \"thu\": null, \"fri\": null,"
                        + " \"sat\": null, \"sun\": null, \"special\": [{\"date\": \"2026-10-01\"}]}"));
        assertNotNull(OpenHoursValidator.validate(
                "{\"mon\": [], \"tue\": null, \"wed\": null, \"thu\": null, \"fri\": null,"
                        + " \"sat\": null, \"sun\": null, \"special\": {}}"));
    }
}
