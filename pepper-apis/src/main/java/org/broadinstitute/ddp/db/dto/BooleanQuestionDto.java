package org.broadinstitute.ddp.db.dto;

import org.jdbi.v3.core.mapper.Nested;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

/**
 * DTO class to represent boolean question that includes all base question data.
 */
public final class BooleanQuestionDto extends QuestionDto {

    private long trueTemplateId;
    private long falseTemplateId;

    @JdbiConstructor
    public BooleanQuestionDto(@Nested QuestionDto questionDto,
                              @ColumnName("true_template_id") long trueTemplateId,
                              @ColumnName("false_template_id") long falseTemplateId) {
        super(questionDto);
        this.trueTemplateId = trueTemplateId;
        this.falseTemplateId = falseTemplateId;
    }

    public long getTrueTemplateId() {
        return trueTemplateId;
    }

    public long getFalseTemplateId() {
        return falseTemplateId;
    }
}
