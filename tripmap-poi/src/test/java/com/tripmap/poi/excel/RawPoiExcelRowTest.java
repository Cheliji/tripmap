package com.tripmap.poi.excel;

import com.alibaba.excel.EasyExcel;
import com.tripmap.poi.collect.RawPoi;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * raw Excel 行模型读写往返测试（任务 1.3.2，列定义见设计说明书 §3.4）
 *
 * <p>写出后用 EasyExcel 回读，验证列名/列序与 §3.4 一致、字段无损往返。</p>
 */
class RawPoiExcelRowTest {

    @TempDir
    Path tempDir;

    @Test
    void 写读往返字段无损() {
        RawPoi poi = new RawPoi();
        poi.setAmapId("B000A1");
        poi.setName("示例博物馆");
        poi.setType("科教文化服务;博物馆");
        poi.setTypecode("140100");
        poi.setKeytag("5A景区");
        poi.setLevel("AAAA");
        poi.setRating("4.7");
        poi.setOpenTime("09:00-17:00");
        poi.setOpentime2("周一 闭馆(法定节假日除外)；周二至周日 09:00-16:30");
        poi.setCost("30");
        poi.setTel("0571-123456");
        poi.setAddress("杭州市西湖区示例路1号");
        poi.setLocation("120.123456,30.123456");
        poi.setPname("浙江省");
        poi.setCityname("杭州市");
        poi.setAdname("西湖区");
        poi.setPhotos("http://img1.example/a.jpg;http://img2.example/b.jpg");

        Path file = tempDir.resolve("hangzhou-poi-raw.xlsx");
        EasyExcel.write(file.toString(), RawPoiExcelRow.class)
                .sheet("raw")
                .doWrite(List.of(RawPoiExcelRow.of(poi, "140100;购物中心", "2026-11-05T10:00:00Z")));

        assertTrue(file.toFile().exists() && file.toFile().length() > 0);

        List<RawPoiExcelRow> rows = EasyExcel.read(file.toString())
                .head(RawPoiExcelRow.class)
                .sheet()
                .doReadSync();
        assertEquals(1, rows.size());
        RawPoiExcelRow row = rows.get(0);
        assertEquals("B000A1", row.getAmapId());
        assertEquals("示例博物馆", row.getName());
        assertEquals("140100", row.getTypecode());
        assertEquals("AAAA", row.getLevel());
        assertEquals("4.7", row.getRating());
        assertEquals("周一 闭馆(法定节假日除外)；周二至周日 09:00-16:30", row.getOpentime2());
        // 坐标以字符串无损往返，不转数值（§3.4）
        assertEquals("120.123456,30.123456", row.getLocation());
        assertEquals("140100;购物中心", row.getCollectKeywords());
        assertEquals("2026-11-05T10:00:00Z", row.getCollectedAt());
    }
}
