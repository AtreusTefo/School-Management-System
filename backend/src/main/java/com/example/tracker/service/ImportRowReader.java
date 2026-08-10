package com.example.tracker.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an uploaded CSV or Excel file into rows of column-name to cell-text,
 * for the admin panel's bulk student/teacher import.
 *
 * ONE READER FOR BOTH FORMATS, DECIDED BY CONTENT, NOT BY FILENAME
 * A ".csv" extension is exactly as untrustworthy as any other client-supplied
 * label - the same reasoning SubmissionService already applies to an
 * uploaded PDF's declared content type. An .xlsx file is a zip archive, and
 * every zip begins with the two bytes 'P' 'K'; anything else is read as
 * plain text. That is a real structural check, not a rename away from being
 * fooled the way trusting the extension would be.
 *
 * EVERY VALUE COMES BACK AS A STRING. Reading an Excel NUMERIC cell as text
 * (getNumericCellValue formatted without a decimal point when it is a whole
 * number) keeps this reader ignorant of what a column MEANS - a date of
 * birth, a national id, a class name are all just cells to this class. The
 * caller (AdminService) is the one place that knows how to parse and
 * validate each column, which keeps that knowledge from spreading into a
 * file-format reader that should not need to change if a new column is
 * added.
 */
@Component
public class ImportRowReader {

    private static final byte[] ZIP_MAGIC = { 0x50, 0x4B };

    public List<Map<String, String>> read(byte[] content) {
        if (looksLikeZip(content)) {
            return readExcel(content);
        }
        return readCsv(content);
    }

    private boolean looksLikeZip(byte[] content) {
        return content.length >= 2 && content[0] == ZIP_MAGIC[0] && content[1] == ZIP_MAGIC[1];
    }

    private List<Map<String, String>> readExcel(byte[] content) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = workbook.getSheetAt(0);
            List<Map<String, String>> rows = new ArrayList<>();

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                return rows;
            }
            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(cellText(cell).trim());
            }

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean anyValue = false;
                for (int c = 0; c < headers.size(); c++) {
                    String text = cellText(row.getCell(c)).trim();
                    if (!text.isEmpty()) {
                        anyValue = true;
                    }
                    values.put(headers.get(c), text);
                }
                if (anyValue) {
                    rows.add(values);
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read that spreadsheet.", e);
        }
    }

    private String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            // A whole number (a national id, a class name that is all digits)
            // should read back as "12345", not "12345.0".
            return (value == Math.floor(value) && !Double.isInfinite(value))
                    ? String.valueOf((long) value)
                    : String.valueOf(value);
        }
        return cell.toString();
    }

    /**
     * A minimal CSV reader: comma-separated, an optional double-quoted field
     * for values containing a comma, "" as an escaped quote inside one. Not a
     * general-purpose CSV library - the admin panel's own export/import round
     * trip is the only producer of these files, so the format it needs to
     * accept is exactly this, not the full range RFC 4180 allows.
     */
    private List<Map<String, String>> readCsv(byte[] content) {
        List<Map<String, String>> rows = new ArrayList<>();
        String text = new String(content, StandardCharsets.UTF_8);
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            return rows;
        }

        List<String> headers = parseCsvLine(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            List<String> fields = parseCsvLine(lines.get(i));
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < headers.size(); c++) {
                values.put(headers.get(c), c < fields.size() ? fields.get(c) : "");
            }
            rows.add(values);
        }
        return rows;
    }

    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n")) {
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    inQuotes = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString().trim());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString().trim());
        return fields;
    }
}
