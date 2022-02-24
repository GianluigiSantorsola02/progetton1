package org.broadinstitute.dsm.model.elastic.filter.splitter;

public class DateLowerSplitter extends LessThanEqualsSplitter {

    @Override
    public String[] getValue() {
        // STR_TO_DATE('2012-01-01', %yyyy-%MM-%dd)
        return DateSplitterHelper.splitter(splittedFilter[1]);
    }
}