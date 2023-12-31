package org.broadinstitute.ddp.model.activity.definition.question;

import java.util.ArrayList;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import com.google.gson.annotations.SerializedName;
import org.broadinstitute.ddp.model.activity.definition.template.Template;
import org.broadinstitute.ddp.model.activity.definition.validation.RuleDef;
import org.broadinstitute.ddp.model.activity.types.QuestionType;
import org.broadinstitute.ddp.model.activity.types.SuggestionType;
import org.broadinstitute.ddp.model.activity.types.TextInputType;
import org.broadinstitute.ddp.util.MiscUtil;

public final class TextQuestionDef extends QuestionDef {

    @NotNull
    @SerializedName("inputType")
    private TextInputType inputType;

    @SerializedName("suggestionType")
    private SuggestionType suggestionType;

    @Valid
    @SerializedName("placeholderTemplate")
    private Template placeholderTemplate;

    @SerializedName("suggestions")
    private List<String> suggestions;

    public static Builder builder() {
        return new Builder();
    }

    public static Builder builder(TextInputType inputType, String stableId, Template prompt) {
        return new Builder()
                .setInputType(inputType)
                .setStableId(stableId)
                .setPrompt(prompt);
    }

    public TextQuestionDef(String stableId, boolean isRestricted, Template promptTemplate, Template placeholderTemplate,
                           Template additionalInfoHeaderTemplate, Template additionalInfoFooterTemplate,
                           List<RuleDef> validations, TextInputType inputType, SuggestionType suggestionType, boolean hideNumber) {
        this(stableId,
                isRestricted,
                promptTemplate,
                placeholderTemplate,
                additionalInfoHeaderTemplate,
                additionalInfoFooterTemplate,
                validations,
                inputType,
                hideNumber);
        this.suggestionType = suggestionType;
    }

    public TextQuestionDef(String stableId, boolean isRestricted, Template promptTemplate,
                           Template placeholderTemplate,
                           Template additionalInfoHeaderTemplate, Template additionalInfoFooterTemplate,
                           List<RuleDef> validations, TextInputType inputType, boolean hideNumber) {
        super(QuestionType.TEXT,
                stableId,
                isRestricted,
                promptTemplate,
                additionalInfoHeaderTemplate,
                additionalInfoFooterTemplate,
                validations,
                hideNumber);
        this.inputType = MiscUtil.checkNonNull(inputType, "inputType");
        this.placeholderTemplate = placeholderTemplate;
    }

    public TextQuestionDef(String stableId, boolean isRestricted, Template promptTemplate, Template placeholderTemplate,
                           Template additionalInfoHeaderTemplate, Template additionalInfoFooterTemplate,
                           List<RuleDef> validations, TextInputType inputType, SuggestionType suggestionType,
                           List<String> suggestions, boolean hideNumber) {
        this(stableId,
                isRestricted,
                promptTemplate,
                placeholderTemplate,
                additionalInfoHeaderTemplate,
                additionalInfoFooterTemplate,
                validations,
                inputType,
                hideNumber);
        this.suggestionType = suggestionType;
        this.suggestions = suggestions;
    }

    public TextInputType getInputType() {
        return inputType;
    }

    public SuggestionType getSuggestionType() {
        return suggestionType;
    }

    public Template getPlaceholderTemplate() {
        return placeholderTemplate;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public static final class Builder extends AbstractQuestionBuilder<Builder> {

        private TextInputType inputType;

        private SuggestionType suggestionType;
        private Template placeholderTemplate;
        private List<String> suggestions;

        private Builder() {
            // Use static factories.
        }

        @Override
        protected Builder self() {
            return this;
        }

        public Builder setInputType(TextInputType inputType) {
            this.inputType = inputType;
            return this;
        }

        public Builder setSuggestionType(SuggestionType suggestionType) {
            this.suggestionType = suggestionType;
            return this;
        }

        public Builder setPlaceholderTemplate(Template placeholderTemplate) {
            this.placeholderTemplate = placeholderTemplate;
            return self();
        }

        public Builder addSuggestions(List<String> suggestionsToAdd) {
            if (suggestions == null) {
                suggestions = new ArrayList<>();
            }
            suggestions.addAll(suggestionsToAdd);
            return self();
        }

        public TextQuestionDef build() {
            TextQuestionDef question = new TextQuestionDef(stableId,
                                                            isRestricted,
                                                            prompt,
                                                            placeholderTemplate,
                                                            getAdditonalInfoHeader(),
                                                            getAdditonalInfoFooter(),
                                                            validations,
                                                            inputType,
                                                            suggestionType,
                                                            suggestions,
                                                            hideNumber);
            configure(question);
            return question;
        }
    }
}
