package com.example.tracker.model;

import java.math.BigDecimal;

/**
 * A percentage turned into a label a person can act on.
 *
 * WHY THIS IS AN ENUM AND NOT A STORED COLUMN
 * -------------------------------------------
 * A performance level is not a fact about a student; it is an opinion about a
 * percentage, and the percentage is itself derived from the marks. Storing it
 * would create three copies of the same truth - the marks, the percentage and
 * the label - and the moment one mark is corrected two of them are wrong with
 * nothing to say so.
 *
 * So nothing in the database holds a level. It is computed on read, from marks
 * that are the only thing anybody actually enters.
 *
 * WHERE THE THRESHOLDS COME FROM, AND HOW TO CHANGE THEM
 * -----------------------------------------------------
 * The seven bands below are the scale used by South African schools, which is
 * where this system is being built. They are a POLICY CHOICE, not a law of
 * arithmetic - a school on a different scale changes the numbers here and
 * nowhere else, which is the whole reason they live in one place instead of
 * being scattered through the service and the templates.
 *
 * The bands are checked in descending order, so each constant only has to state
 * its own floor.
 */
public enum PerformanceLevel {

    OUTSTANDING(80, "Outstanding achievement"),
    MERITORIOUS(70, "Meritorious achievement"),
    SUBSTANTIAL(60, "Substantial achievement"),
    ADEQUATE(50, "Adequate achievement"),
    MODERATE(40, "Moderate achievement"),
    ELEMENTARY(30, "Elementary achievement"),
    NOT_ACHIEVED(0, "Not achieved");

    private final int floorPercentage;
    private final String description;

    PerformanceLevel(int floorPercentage, String description) {
        this.floorPercentage = floorPercentage;
        this.description = description;
    }

    public int getFloorPercentage() {
        return floorPercentage;
    }

    public String getDescription() {
        return description;
    }

    /**
     * The band a percentage falls into.
     *
     * Takes a BigDecimal rather than a double because that is what the marks are
     * stored as, and converting to double here purely to compare it would
     * reintroduce the rounding this system deliberately avoids everywhere else.
     *
     * A null percentage - which happens when a student has no marks at all -
     * returns null rather than NOT_ACHIEVED. "No marks yet" and "scored under
     * 30%" are different statements, and collapsing them would label every new
     * student a failure on their first day.
     */
    public static PerformanceLevel of(BigDecimal percentage) {
        if (percentage == null) {
            return null;
        }
        for (PerformanceLevel level : values()) {
            if (percentage.compareTo(BigDecimal.valueOf(level.floorPercentage)) >= 0) {
                return level;
            }
        }
        return NOT_ACHIEVED;
    }
}
