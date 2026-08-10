package com.example.tracker.dto;

import java.util.List;

/**
 * The report a bulk import hands back: what was actually stored, row by row.
 *
 * PARTIAL SUCCESS, NOT ALL-OR-NOTHING
 * A spreadsheet of two hundred students with three typos should not lose the
 * other 197 - each row is validated and saved independently, in its own
 * transaction (see AdminService), and this summary is how the admin finds
 * out which three to fix and re-upload. temporaryPassword is included only
 * for rows that succeeded, since it is the one time this system will ever
 * say it in plain text.
 */
public record ImportSummary(int totalRows, int succeeded, int failed, List<ImportRowResult> rows) {

    public record ImportRowResult(
            int rowNumber,
            boolean success,
            String identifier,
            String externalId,
            String temporaryPassword,
            String message) {
    }
}
