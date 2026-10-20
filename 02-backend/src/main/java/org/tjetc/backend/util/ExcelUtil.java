package org.tjetc.backend.util;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.tjetc.backend.common.BusinessException;
import org.tjetc.backend.common.ResultCode;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel 读写工具（poi-ooxml 5.4.1）：
 * 分类规则批量导入导出（API 7.5/7.6）、工单批量导入（API 6.8）、统计报表导出（API 8.5）复用
 */
public final class ExcelUtil {

    private ExcelUtil() {
    }

    /** 创建 xlsx 工作簿 */
    public static XSSFWorkbook createWorkbook() {
        return new XSSFWorkbook();
    }

    /** 流式写出工作簿到 HTTP 响应（零落盘下载）：设置 xlsx MIME 与附件头后直接回写响应流 */
    public static void writeResponse(XSSFWorkbook workbook, String fileName,
                                     HttpServletResponse response) throws IOException {
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        try (workbook) {
            workbook.write(response.getOutputStream());
        }
    }

    /** 写表头行（加粗） */
    public static void writeHeader(Sheet sheet, String[] headers) {
        Row row = sheet.createRow(0);
        CellStyle style = sheet.getWorkbook().createCellStyle();
        Font font = sheet.getWorkbook().createFont();
        font.setBold(true);
        style.setFont(font);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(style);
        }
    }

    /** 写数据行（null 转空串，数字写数值单元格） */
    public static void writeRow(Sheet sheet, int rowIndex, Object[] values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            Object v = values[i];
            Cell cell = row.createCell(i);
            if (v == null) {
                cell.setCellValue("");
            } else if (v instanceof Number n) {
                cell.setCellValue(n.doubleValue());
            } else {
                cell.setCellValue(String.valueOf(v));
            }
        }
    }

    /** 自动列宽（限制在 8~60 字符宽度内） */
    public static void autoSize(Sheet sheet, int colCount) {
        for (int i = 0; i < colCount; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.min(Math.max(width, 8 * 256), 60 * 256));
        }
    }

    /**
     * 读取全部数据行（跳过首行表头与全空行，每行为字符串数组）
     */
    public static List<String[]> readRows(InputStream in) {
        List<String[]> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(in)) {
            Sheet sheet = workbook.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) { // 跳过表头行
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                int cols = row.getLastCellNum();
                if (cols <= 0) {
                    continue;
                }
                String[] values = new String[cols];
                boolean empty = true;
                for (int c = 0; c < cols; c++) {
                    String v = cellValue(row.getCell(c)).trim();
                    values[c] = v;
                    if (!v.isEmpty()) {
                        empty = false;
                    }
                }
                if (!empty) {
                    rows.add(values);
                }
            }
        } catch (Exception e) {
            // 不向客户端回显解析器内部异常细节，统一提示解析失败
            throw new BusinessException(ResultCode.PARAM_ERROR, "Excel 文件解析失败，请检查文件格式是否正确");
        }
        return rows;
    }

    private static String cellValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? String.valueOf(cell.getDateCellValue())
                    : new BigDecimal(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> cell.getStringCellValue();
        };
    }
}
