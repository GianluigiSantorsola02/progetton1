package org.broadinstitute.dsm.model.elastic.export.tabular;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.dsm.model.Filter;
import org.broadinstitute.dsm.model.ParticipantColumn;
import org.broadinstitute.dsm.statics.ESObjectConstants;
import org.broadinstitute.dsm.util.ElasticSearchUtil;

@Getter
public class FilterExportConfig {
    private final ParticipantColumn column;
    private final ModuleExportConfig parent;
    private final String type;
    private boolean splitOptionsIntoColumns = false;
    // whether this question has details associated with it -- this property is allowed to be dynamic since we don't know if a
    // question has details enabled until we start parsing participant responses
    @Setter
    private boolean hasDetails = false;
    private String collationSuffix = null;
    private Map<String, Object> questionDef = null;
    @Setter
    private int maxRepeats = 1;
    private int questionIndex = -1;

    private List<Map<String, Object>> options = null;
    private List<FilterExportConfig> childConfigs = null;
    private boolean allowMultiple = false;
    private String questionType = null;

    public FilterExportConfig(ModuleExportConfig parent, Filter filterColumn, boolean splitOptionsIntoColumns,
                              String collationSuffix, Map<String, Object> questionDef, int questionIndex) {
        this.column = filterColumn.getParticipantColumn();
        this.type = filterColumn.getType();
        this.parent = parent;
        this.splitOptionsIntoColumns = splitOptionsIntoColumns;
        this.collationSuffix = collationSuffix;
        this.questionIndex = questionIndex;
        this.questionDef = questionDef;
        this.allowMultiple = isAllowMultiple(questionDef);
        this.questionType = this.type;
        if (this.questionDef != null) {
            this.options = getOptionsForQuestion(questionDef);
            this.questionType = (String) questionDef.get(ESObjectConstants.QUESTION_TYPE);
        }
        List<Map<String, Object>> childQuestions = getChildQuestions();
        if (childQuestions != null) {
            this.childConfigs = IntStream.range(0, childQuestions.size())
                    .mapToObj(qNum -> new FilterExportConfig(this, childQuestions.get(qNum), qNum)).collect(Collectors.toList());
        }
    }

    /** short constructor for getting a config for a subquestion of a composite question */
    private FilterExportConfig(FilterExportConfig parent, Map<String, Object> childQuestion, int childIndex) {
        this.parent = parent.getParent();
        this.type = (String) childQuestion.get(ESObjectConstants.QUESTION_TYPE);
        this.questionType = this.type;
        String childStableId = (String) childQuestion.get(ESObjectConstants.STABLE_ID);
        if (StringUtils.isBlank(childStableId)) {
            // some subquestions don't have stableIds (e.g. RELEASE_MINOR_PHYSICIAN from PanCan), we need to ensure the stableId
            // is something unique to avoid values getting overwritten
            childStableId = (String) childQuestion.get(ESObjectConstants.QUESTION_TEXT);
            if (StringUtils.isNotBlank(childStableId)) {
                childStableId = childStableId.toUpperCase().replace(" ", "_");
            } else {
                childStableId = "SUBQUESTION" + childIndex;
            }
        }
        this.column = new ParticipantColumn(childStableId, parent.column.getTableAlias());
        String childDisplayText = (String) childQuestion.get(ESObjectConstants.QUESTION_TEXT);
        if (StringUtils.isBlank(childDisplayText)) {
            childDisplayText = childStableId;
        }
        this.column.setDisplay(childDisplayText);

        this.questionDef = childQuestion;
        this.options = getOptionsForQuestion(childQuestion);

    }

    public boolean isAllowMultiple(Map<String, Object> questionDef) {
        if (questionDef != null && questionDef.get(ESObjectConstants.ALLOW_MULTIPLE) != null) {
            return (boolean) questionDef.get(ESObjectConstants.ALLOW_MULTIPLE);
        }
        return false;
    }

    private List<Map<String, Object>> getOptionsForQuestion(Map<String, Object> questionDef) {
        List<Map<String, Object>> options = (List<Map<String, Object>>) questionDef.get(ESObjectConstants.OPTIONS);
        if (questionDef.containsKey(ESObjectConstants.OPTION_GROUPS)) {
            Object groups = questionDef.get(ESObjectConstants.OPTION_GROUPS);
            if (groups instanceof List) {
                for (Map<String, Object> group : (List<Map<String, Object>>) groups) {
                    if (group.containsKey(ESObjectConstants.OPTIONS)) {
                        options.addAll((List<Map<String, Object>>) group.get(ESObjectConstants.OPTIONS));
                    }
                }
            }
        }
        return options;
    }

    public List<Map<String, Object>> getChildQuestions() {
        return questionDef != null ? (List<Map<String, Object>>) questionDef.get(ESObjectConstants.CHILD_QUESTIONS) : null;
    }

    public boolean isQuestionAnswer() {
        return ElasticSearchUtil.QUESTIONS_ANSWER.equals(column.getObject());
    }
}
