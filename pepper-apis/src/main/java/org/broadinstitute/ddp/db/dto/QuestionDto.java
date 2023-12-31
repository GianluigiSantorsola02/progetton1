package org.broadinstitute.ddp.db.dto;

import javax.annotation.Nullable;

import org.broadinstitute.ddp.model.activity.types.QuestionType;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

public class QuestionDto {

    private QuestionType type;
    private long id;
    private String stableId;
    private long promptTemplateId;
    private Long additionalInfoHeaderTemplateId;
    private Long additionalInfoFooterTemplateId;
    private long activityId;
    private boolean isRestricted;
    private boolean isDeprecated;
    private boolean hideNumber;
    private long revisionId;
    private long revisionStart;
    private Long revisionEnd;

    @JdbiConstructor
    public QuestionDto(@ColumnName("question_type_code") QuestionType type,
                       @ColumnName("question_id") long id,
                       @ColumnName("stable_id") String stableId,
                       @ColumnName("question_prompt_template_id") long promptTemplateId,
                       @ColumnName("info_header_template_id") Long additionalInfoHeaderTemplateId,
                       @ColumnName("info_footer_template_id") Long additionalInfoFooterTemplateId,
                       @ColumnName("study_activity_id") long activityId,
                       @ColumnName("is_restricted") boolean isRestricted,
                       @ColumnName("is_deprecated") Boolean isDeprecated,
                       @ColumnName("hide_number") Boolean hideNumber,
                       @ColumnName("revision_id") long revisionId,
                       @ColumnName("revision_start") long revisionStart,
                       @ColumnName("revision_end") Long revisionEnd) {
        this.type = type;
        this.id = id;
        this.stableId = stableId;
        this.promptTemplateId = promptTemplateId;
        this.additionalInfoHeaderTemplateId = additionalInfoHeaderTemplateId;
        this.additionalInfoFooterTemplateId = additionalInfoFooterTemplateId;
        this.activityId = activityId;
        this.isRestricted = isRestricted;
        this.isDeprecated = isDeprecated == null ? false : isDeprecated;
        this.hideNumber = hideNumber == null ? false : hideNumber;
        this.revisionId = revisionId;
        this.revisionStart = revisionStart;
        this.revisionEnd = revisionEnd;
    }

    protected QuestionDto(QuestionDto other) {
        this.type = other.type;
        this.id = other.id;
        this.stableId = other.stableId;
        this.promptTemplateId = other.promptTemplateId;
        this.additionalInfoHeaderTemplateId = other.additionalInfoHeaderTemplateId;
        this.additionalInfoFooterTemplateId = other.additionalInfoFooterTemplateId;
        this.activityId = other.activityId;
        this.isRestricted = other.isRestricted;
        this.isDeprecated = other.isDeprecated;
        this.hideNumber = other.hideNumber;
        this.revisionId = other.revisionId;
        this.revisionStart = other.revisionStart;
        this.revisionEnd = other.revisionEnd;
    }

    public QuestionType getType() {
        return type;
    }

    public long getId() {
        return id;
    }

    public String getStableId() {
        return stableId;
    }

    public long getPromptTemplateId() {
        return promptTemplateId;
    }

    @Nullable
    public Long getAdditionalInfoHeaderTemplateId() {
        return additionalInfoHeaderTemplateId;
    }

    @Nullable
    public Long getAdditionalInfoFooterTemplateId() {
        return additionalInfoFooterTemplateId;
    }

    public long getActivityId() {
        return activityId;
    }

    public boolean isRestricted() {
        return isRestricted;
    }

    public boolean isDeprecated() {
        return isDeprecated;
    }

    public boolean shouldHideNumber() {
        return hideNumber;
    }

    public long getRevisionId() {
        return revisionId;
    }

    public long getRevisionStart() {
        return revisionStart;
    }

    public Long getRevisionEnd() {
        return revisionEnd;
    }
}
