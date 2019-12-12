package org.broadinstitute.ddp.model.activity.definition.question;

import java.time.Year;
import java.util.function.Predicate;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;

import com.google.gson.annotations.SerializedName;

/**
 * Represents presentation configuration used for picklist dropdowns of a date question.
 * Note the year values follow the AD calendar scheme, and there is no year zero.
 */
public class DatePicklistDef {

    public static int DEFAULT_YEARS_FORWARD = 5;
    public static int DEFAULT_YEARS_BACK = 100;
    public static boolean DEFAULT_USE_MONTH_NAMES = true;

    @SerializedName("useMonthNames")
    private Boolean useMonthNames;

    @PositiveOrZero
    @SerializedName("yearsForward")
    private Integer yearsForward;

    @PositiveOrZero
    @SerializedName("yearsBack")
    private Integer yearsBack;

    @Positive
    @SerializedName("yearAnchor")
    private Integer yearAnchor;

    @Positive
    @SerializedName("firstSelectedYear")
    private Integer firstSelectedYear;

    public DatePicklistDef(Boolean useMonthNames, Integer yearsForward, Integer yearsBack, Integer yearAnchor,
                           Integer firstSelectedYear) {
        this.useMonthNames = useMonthNames;
        this.yearsForward = checkInt(yearsForward, (n -> n >= 0), "yearsForward should be positive or zero");
        this.yearsBack = checkInt(yearsBack, (n -> n >= 0), "yearsBack should be positive or zero");
        this.yearAnchor = checkInt(yearAnchor, (n -> n > 0), "yearAnchor should be positive");
        this.firstSelectedYear = checkInt(firstSelectedYear, (n -> n > 0), "firstSelectedYear should be positive");
    }

    public Boolean getUseMonthNames() {
        return useMonthNames;
    }

    public Integer getYearsForward() {
        return yearsForward;
    }

    public Integer getYearsBack() {
        return yearsBack;
    }

    public Integer getYearAnchor() {
        return yearAnchor;
    }

    public Integer getFirstSelectedYear() {
        return firstSelectedYear;
    }

    /**
     * Get start year (years back from year anchor).
     */
    public int getStartYear() {
        Year anchor = (yearAnchor == null ? Year.now() : Year.of(yearAnchor));
        int back = (yearsBack == null ? DEFAULT_YEARS_BACK : yearsBack);
        return anchor.minusYears(back).getValue();
    }

    /**
     * Get end year (years forward from year anchor).
     */
    public int getEndYear() {
        Year anchor = (yearAnchor == null ? Year.now() : Year.of(yearAnchor));
        int forward = (yearsForward == null ? DEFAULT_YEARS_FORWARD : yearsForward);
        return anchor.plusYears(forward).getValue();
    }

    /**
     * If number is not null, check that it satisfies given condition.
     */
    private Integer checkInt(Integer num, Predicate<Integer> condition, String message) {
        if (num != null && !condition.test(num)) {
            throw new IllegalArgumentException(message);
        }
        return num;
    }
}
