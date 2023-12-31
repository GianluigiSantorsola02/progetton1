package org.broadinstitute.ddp.route;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.constants.RouteConstants.PathParam;
import org.broadinstitute.ddp.content.I18nContentRenderer;
import org.broadinstitute.ddp.db.ActivityInstanceDao;
import org.broadinstitute.ddp.db.AnswerDao;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.DataExportDao;
import org.broadinstitute.ddp.db.dao.JdbiActivityInstance;
import org.broadinstitute.ddp.db.dao.JdbiCompositeQuestion;
import org.broadinstitute.ddp.db.dao.JdbiLanguageCode;
import org.broadinstitute.ddp.db.dao.JdbiNumericQuestion;
import org.broadinstitute.ddp.db.dao.JdbiQuestion;
import org.broadinstitute.ddp.db.dao.JdbiUser;
import org.broadinstitute.ddp.db.dao.QuestionDao;
import org.broadinstitute.ddp.db.dto.ActivityInstanceDto;
import org.broadinstitute.ddp.db.dto.CompositeQuestionDto;
import org.broadinstitute.ddp.db.dto.NumericQuestionDto;
import org.broadinstitute.ddp.db.dto.QuestionDto;
import org.broadinstitute.ddp.db.dto.UserDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.exception.OperationNotAllowedException;
import org.broadinstitute.ddp.exception.RequiredParameterMissingException;
import org.broadinstitute.ddp.exception.UnexpectedNumberOfElementsException;
import org.broadinstitute.ddp.json.AnswerResponse;
import org.broadinstitute.ddp.json.AnswerSubmission;
import org.broadinstitute.ddp.json.PatchAnswerPayload;
import org.broadinstitute.ddp.json.PatchAnswerResponse;
import org.broadinstitute.ddp.json.errors.AnswerExistsError;
import org.broadinstitute.ddp.json.errors.AnswerValidationError;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.model.activity.instance.answer.AgreementAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.Answer;
import org.broadinstitute.ddp.model.activity.instance.answer.BoolAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.CompositeAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.DateAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.DateValue;
import org.broadinstitute.ddp.model.activity.instance.answer.NumericAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.NumericIntegerAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.PicklistAnswer;
import org.broadinstitute.ddp.model.activity.instance.answer.SelectedPicklistOption;
import org.broadinstitute.ddp.model.activity.instance.answer.TextAnswer;
import org.broadinstitute.ddp.model.activity.instance.question.CompositeQuestion;
import org.broadinstitute.ddp.model.activity.instance.question.Question;
import org.broadinstitute.ddp.model.activity.instance.validation.Rule;
import org.broadinstitute.ddp.model.activity.types.ActivityType;
import org.broadinstitute.ddp.model.activity.types.InstanceStatusType;
import org.broadinstitute.ddp.model.activity.types.NumericType;
import org.broadinstitute.ddp.model.activity.types.QuestionType;
import org.broadinstitute.ddp.security.DDPAuth;
import org.broadinstitute.ddp.service.FormActivityService;
import org.broadinstitute.ddp.util.ActivityInstanceUtil;
import org.broadinstitute.ddp.util.FormActivityStatusUtil;
import org.broadinstitute.ddp.util.GsonPojoValidator;
import org.broadinstitute.ddp.util.JsonValidationError;
import org.broadinstitute.ddp.util.ResponseUtil;
import org.broadinstitute.ddp.util.RouteUtil;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Route;

public class PatchFormAnswersRoute implements Route {

    private static final Logger LOG = LoggerFactory.getLogger(PatchFormAnswersRoute.class);

    private Gson gson;
    private GsonPojoValidator checker;
    private FormActivityService formService;
    private ActivityInstanceDao activityInstanceDao;
    private AnswerDao answerDao;

    public PatchFormAnswersRoute(
            FormActivityService formService,
            ActivityInstanceDao activityInstanceDao,
            AnswerDao answerDao
    ) {
        this.gson = new Gson();
        this.checker = new GsonPojoValidator();
        this.formService = formService;
        this.activityInstanceDao = activityInstanceDao;
        this.answerDao = answerDao;
    }

    @Override
    public Object handle(Request request, Response response) {
        String participantGuid = request.params(PathParam.USER_GUID);
        String studyGuid = request.params(PathParam.STUDY_GUID);
        String activityInstanceGuid = request.params(PathParam.INSTANCE_GUID);

        DDPAuth ddpAuth = RouteUtil.getDDPAuth(request);
        String operatorGuid = ddpAuth.getOperator() != null ? ddpAuth.getOperator() : participantGuid;

        LOG.info("Attempting to patch answers for activity instance {}", activityInstanceGuid);

        PatchAnswerResponse result = TransactionWrapper.withTxn(handle -> {
            UserDto userDto = handle.attach(JdbiUser.class).findByUserGuid(participantGuid);
            if (userDto.isTemporary()) {
                Optional<ActivityInstanceDto> activityInstanceDto =
                        handle.attach(JdbiActivityInstance.class).getByActivityInstanceGuid(activityInstanceGuid);
                if (!activityInstanceDto.isPresent()) {
                    String msg = "Activity instance " + activityInstanceGuid + " is not found";
                    ResponseUtil.haltError(response, 404, new ApiError(ErrorCodes.ACTIVITY_NOT_FOUND, msg));
                } else if (!activityInstanceDto.get().isAllowUnauthenticated()) {
                    String msg = "Activity instance " + activityInstanceGuid + " not accessible to unauthenticated users";
                    ResponseUtil.haltError(response, 401, new ApiError(ErrorCodes.OPERATION_NOT_ALLOWED, msg));
                }
            }

            ActivityType activityType = activityInstanceDao.getActivityTypeByGuids(handle,
                    participantGuid, studyGuid, activityInstanceGuid);
            if (activityType == null) {
                String msg = "Activity instance " + activityInstanceGuid + " is not found";
                ResponseUtil.haltError(response, 404, new ApiError(ErrorCodes.ACTIVITY_NOT_FOUND, msg));
                return null;
            }

            if (!ActivityType.FORMS.equals(activityType)) {
                String msg = "Activity " + activityInstanceGuid + " is not a form activity that accepts answers";
                LOG.info(msg);
                ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.INVALID_REQUEST, msg));
                return null;
            }

            PatchAnswerPayload payload = parseBodyPayload(request);
            if (payload == null) {
                String msg = "Unable to process request body";
                ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.BAD_PAYLOAD, msg));
                return null;
            }

            PatchAnswerResponse res = new PatchAnswerResponse();
            List<AnswerSubmission> submissions = payload.getSubmissions();
            if (submissions == null || submissions.isEmpty()) {
                LOG.info("No answer submissions to process");
                return res;
            }

            if (ActivityInstanceUtil.isReadonly(handle, activityInstanceGuid)) {
                String msg = "Activity instance with GUID " + activityInstanceGuid
                        + " is read-only, cannot submit answer(s) for it";
                LOG.info(msg);
                ResponseUtil.haltError(response, 422, new ApiError(ErrorCodes.ACTIVITY_INSTANCE_IS_READONLY, msg));
                return null;
            }

            JdbiQuestion jdbiQuestion = handle.attach(JdbiQuestion.class);

            String isoLanguageCode = ddpAuth.getPreferredLanguage() != null
                    ? ddpAuth.getPreferredLanguage() : I18nContentRenderer.DEFAULT_LANGUAGE_CODE;
            JdbiLanguageCode jdbiLanguageCode = handle.attach(JdbiLanguageCode.class);
            Long languageCodeId = jdbiLanguageCode.getLanguageCodeId(isoLanguageCode);

            try {
                for (AnswerSubmission submission : submissions) {
                    String questionStableId = extractQuestionStableId(submission, response);

                    Optional<QuestionDto> optDto = jdbiQuestion.findDtoByStableIdAndInstanceGuid(questionStableId, activityInstanceGuid);
                    QuestionDto questionDto = extractQuestionDto(response, questionStableId, optDto);
                    Question question = handle.attach(QuestionDao.class).getQuestionByActivityInstanceAndDto(questionDto,
                            activityInstanceGuid, false, languageCodeId);

                    Answer answer = convertAnswer(handle, response, activityInstanceGuid, questionStableId,
                            submission.getAnswerGuid(), questionDto, submission.getValue());
                    if (answer == null) {
                        String msg = "Answer value does not have expected format for question stable id " + questionStableId;
                        LOG.info(msg);
                        ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.BAD_PAYLOAD, msg));
                        return null;
                    }

                    // Run constraint checks before processing validation rules.
                    checkAnswerConstraints(response, answer);

                    Rule failure = validateAnswer(answer, activityInstanceGuid, question);
                    if (failure != null) {
                        String msg = "An answer submission failed validation for its question";
                        LOG.info(msg);
                        ResponseUtil.haltError(response, 422, new AnswerValidationError(msg,
                                questionStableId, failure.getRuleType(), failure.getMessage()));
                        return null;
                    }

                    // todo: proper usage of flag when requirements are better understood
                    boolean forceAdd = false;

                    // Attempt to figure out the answer to update if one exists.
                    if (answer.getAnswerGuid() == null && !forceAdd) {
                        List<String> answerGuids =
                                answerDao.getAnswerGuidsForQuestion(handle, activityInstanceGuid, questionStableId);
                        if (answerGuids.size() > 1) {
                            String msg = "Question is already answered. Provide the answer guid to update.";
                            LOG.info(msg);
                            ResponseUtil.haltError(response, 409, new AnswerExistsError(msg, questionStableId));
                        } else if (answerGuids.size() == 1) {
                            answer.setAnswerGuid(answerGuids.get(0));
                        } else {
                            // No answers exist. Fall through to create a new answer.
                        }
                    }

                    if (answer.getAnswerGuid() == null) {
                        String guid = answerDao.createAnswer(handle, answer, operatorGuid, activityInstanceGuid);
                        answer.setAnswerGuid(guid);
                        LOG.info("Created answer with guid {} for question stable id {}", guid, questionStableId);
                    } else {
                        Long answerId = answerDao.getAnswerIdByGuids(handle, activityInstanceGuid, answer.getAnswerGuid());
                        if (answerId == null) {
                            String msg = "Answer with guid " + answer.getAnswerGuid() + " is not found";
                            ResponseUtil.haltError(response, 404, new ApiError(ErrorCodes.ANSWER_NOT_FOUND, msg));
                            return null;
                        }
                        answerDao.updateAnswerById(handle, activityInstanceGuid, answerId, answer, operatorGuid);
                        LOG.info("Updated answer with guid {} for question stable id {}",
                                answer.getAnswerGuid(), questionStableId);
                    }
                    res.addAnswer(new AnswerResponse(questionStableId, answer.getAnswerGuid()));
                }
            } catch (NoSuchElementException e) {
                LOG.warn(e.getMessage());
                ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.NO_SUCH_ELEMENT, e.getMessage()));
            } catch (UnexpectedNumberOfElementsException e) {
                LOG.warn(e.getMessage());
                ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.UNEXPECTED_NUMBER_OF_ELEMENTS, e.getMessage()));
            } catch (OperationNotAllowedException e) {
                LOG.warn(e.getMessage());
                ResponseUtil.haltError(response, 422, new ApiError(ErrorCodes.OPERATION_NOT_ALLOWED, e.getMessage()));
            } catch (RequiredParameterMissingException e) {
                LOG.warn(e.getMessage());
                ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.REQUIRED_PARAMETER_MISSING, e.getMessage()));
            }

            res.setBlockVisibilities(formService.getBlockVisibilities(handle, participantGuid, activityInstanceGuid));
            FormActivityStatusUtil.updateFormActivityStatus(
                    handle,
                    InstanceStatusType.IN_PROGRESS,
                    activityInstanceGuid,
                    operatorGuid
            );
            handle.attach(DataExportDao.class).queueDataSync(participantGuid, studyGuid);
            return res;
        });

        response.status(200);
        return result;
    }

    private QuestionDto extractQuestionDto(Response response, String questionStableId, Optional<QuestionDto> optQuestionDto) {
        if (!optQuestionDto.isPresent()) {
            String msg = "Question with stable id " + questionStableId + " is not found in form activity";
            ResponseUtil.haltError(response, 404, new ApiError(ErrorCodes.QUESTION_NOT_FOUND, msg));
            return null;
        }
        return optQuestionDto.get();
    }

    private String extractQuestionStableId(AnswerSubmission submission, Response response) {
        String stableId = submission.getQuestionStableId();
        if (StringUtils.isBlank(stableId)) {
            String msg = "An answer submission is missing a question stable id";
            LOG.info(msg);
            ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.BAD_PAYLOAD, msg));
            return null;
        }
        return stableId;
    }

    /**
     * Parse the JSON payload.
     *
     * @param request the incoming request
     * @return payload object, or null if failed parsing
     */
    private PatchAnswerPayload parseBodyPayload(Request request) {
        PatchAnswerPayload payload = null;
        try {
            JsonElement json = gson.fromJson(request.body(), JsonElement.class);
            if (json != null && json.isJsonObject() && json.getAsJsonObject()
                    .has(PatchAnswerPayload.ANSWERS_LIST_KEY)) {
                JsonElement answersList = json.getAsJsonObject().get(PatchAnswerPayload.ANSWERS_LIST_KEY);
                if (answersList != null && answersList.isJsonArray()) {
                    payload = gson.fromJson(json, PatchAnswerPayload.class);
                }
            }
        } catch (JsonSyntaxException e) {
            LOG.info("Unable to parse request payload: " + e.getMessage());
        }
        return payload;
    }

    /**
     * Convert given data to an answer object.
     *
     * @param handle       the jdbi handle
     * @param instanceGuid the activity instance guid
     * @param stableId     the question stable id
     * @param guid         the answer guid, or null
     * @param value        the answer value
     * @return answer object, or null if no answer value given
     */
    private Answer convertAnswer(Handle handle, Response response, String instanceGuid, String stableId, String guid,
                                 QuestionDto questionDto, JsonElement value) {
        switch (questionDto.getType()) {
            case BOOLEAN:
                return convertBoolAnswer(stableId, guid, value);
            case PICKLIST:
                return convertPicklistAnswer(stableId, guid, value);
            case TEXT:
                return convertTextAnswer(stableId, guid, value);
            case DATE:
                return convertDateAnswer(stableId, guid, value);
            case NUMERIC:
                return convertNumericAnswer(handle, questionDto, guid, value);
            case AGREEMENT:
                return convertAgreementAnswer(stableId, guid, value);
            case COMPOSITE:
                return convertCompositeAnswer(handle, response, instanceGuid, stableId, guid, value);
            default:
                throw new RuntimeException("Unhandled question type " + questionDto.getType());
        }
    }

    /**
     * Convert given data to boolean answer.
     *
     * @param stableId the question stable id
     * @param guid     the answer guid, or null
     * @param value    the answer value
     * @return boolean answer object, or null if value is not boolean
     */
    private BoolAnswer convertBoolAnswer(String stableId, String guid, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            boolean boolValue = value.getAsJsonPrimitive().getAsBoolean();
            return new BoolAnswer(null, stableId, guid, boolValue);
        } else {
            return null;
        }
    }

    /**
     * Convert given data to picklist answer.
     *
     * @param stableId the question stable id
     * @param guid     the answer guid, or null
     * @param value    the answer value
     * @return picklist answer object, or null if value is not a list of options
     */
    private PicklistAnswer convertPicklistAnswer(String stableId, String guid, JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            return null;
        }
        try {
            Type selectedOptionListType = new TypeToken<ArrayList<SelectedPicklistOption>>() {
            }.getType();
            List<SelectedPicklistOption> selected = gson.fromJson(value, selectedOptionListType);
            return new PicklistAnswer(null, stableId, guid, selected);
        } catch (JsonSyntaxException e) {
            LOG.warn("Failed to convert submitted answer to a picklist answer", e);
            return null;
        }
    }

    /**
     * Converts the text answer.
     *
     * @param stableId the question stable id
     * @param guid     the answer guid, or null
     * @param value    the answer value
     * @return text answer object, or null if value is not a string
     */
    private TextAnswer convertTextAnswer(String stableId, String guid, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String textValue = value.getAsJsonPrimitive().getAsString();
            return new TextAnswer(null, stableId, guid, textValue);
        } else {
            return null;
        }
    }

    /**
     * Converts given data to date answer.
     *
     * @param stableId the question stable id
     * @param guid     the answer guid, or null
     * @param value    the answer value
     * @return date answer object, or null if value is not as expected
     */
    private DateAnswer convertDateAnswer(String stableId, String guid, JsonElement value) {
        if (value != null && value.isJsonObject()) {
            try {
                DateValue dateValue = gson.fromJson(value, DateValue.class);
                return new DateAnswer(null, stableId, guid, dateValue);
            } catch (JsonSyntaxException e) {
                LOG.warn("Failed to convert submitted answer to a date answer", e);
                return null;
            }
        } else {
            LOG.info("Provided answer value for question stable id {} and with "
                    + "answer guid {} is not an object", stableId, guid);
            return null;
        }
    }

    private NumericAnswer convertNumericAnswer(Handle handle, QuestionDto questionDto, String guid, JsonElement value) {
        if (value == null || value.isJsonNull() || (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())) {
            NumericQuestionDto numericQuestionDto = handle.attach(JdbiNumericQuestion.class)
                    .findDtoByQuestionId(questionDto.getId())
                    .orElseThrow(() -> new DDPException("Could not find numeric question with id " + questionDto.getId()));
            if (numericQuestionDto.getNumericType() == NumericType.INTEGER) {
                Long intValue = null;
                if (value != null && !value.isJsonNull()) {
                    intValue = value.getAsLong();
                }
                return new NumericIntegerAnswer(null, questionDto.getStableId(), guid, intValue);
            } else {
                throw new DDPException("Unhandled numeric answer type " + numericQuestionDto.getNumericType());
            }
        } else {
            return null;
        }
    }

    private CompositeAnswer convertCompositeAnswer(Handle handle, Response response, String instanceGuid,
                                                   String parentStableId, String answerGuid, JsonElement value) {
        final Consumer<String> haltError = (String msg) -> {
            LOG.info(msg);
            ResponseUtil.haltError(response, HttpStatus.SC_BAD_REQUEST, new ApiError(ErrorCodes.BAD_PAYLOAD, msg));
        };
        if (value != null && value.isJsonArray()) {
            CompositeAnswer compAnswer = new CompositeAnswer(null, parentStableId, answerGuid);
            JdbiCompositeQuestion compositeQuestionDao = handle.attach(JdbiCompositeQuestion.class);
            Optional<CompositeQuestionDto> compositeQuestionOpt = compositeQuestionDao
                    .findDtoByInstanceGuidAndStableId(instanceGuid, parentStableId);
            if (!compositeQuestionOpt.isPresent()) {
                String msg = "Could not locate parent composite question id with stableId:" + parentStableId
                        + " and activity instance guid:" + instanceGuid;
                haltError.accept(msg);
            }
            CompositeQuestionDto compositeQuestion = compositeQuestionOpt.get();
            JsonArray childAnswersJsonArray = value.getAsJsonArray();
            if (!compositeQuestion.isAllowMultiple() && childAnswersJsonArray.size() > 1) {
                haltError.accept("Answers to composite question with stable id: " + parentStableId
                        + " are restricted to only one row");
            }
            childAnswersJsonArray.forEach((jsonChildRowElement) -> {
                if (jsonChildRowElement.isJsonArray()) {
                    JsonArray jsonChildRowArray = jsonChildRowElement.getAsJsonArray();
                    Set<String> stableQuestionIdsAllowedInRow = compositeQuestion.getChildQuestions().stream()
                            .map(child -> child.getStableId()).collect(Collectors.toSet());
                    List<Answer> childAnswersRow = new ArrayList<>();
                    jsonChildRowArray.forEach(jsonChildAnswer -> {
                        AnswerSubmission childAnswerSubmission = gson.fromJson(jsonChildAnswer, AnswerSubmission.class);
                        if (childAnswerSubmission == null) {
                            haltError.accept("A child answer had an invalid answer");
                        }
                        String childAnswerStableId = extractQuestionStableId(childAnswerSubmission, response);
                        Optional<QuestionDto> correspondingChildQuestion = compositeQuestion.getChildQuestionByStableId(
                                childAnswerStableId);
                        if (!correspondingChildQuestion.isPresent()) {
                            haltError.accept("Question stable id:" + childAnswerStableId + " in child answer is not "
                                    + "valid");
                        }
                        if (!stableQuestionIdsAllowedInRow.remove(childAnswerStableId)) {
                            haltError.accept("Question stable id:" + childAnswerStableId + "was used more than once "
                                    + "in answer");
                        }
                        QuestionDto childQuestionDto = extractQuestionDto(response, childAnswerStableId, correspondingChildQuestion);
                        childAnswersRow.add(convertAnswer(handle, response, instanceGuid, childAnswerStableId,
                                childAnswerSubmission.getAnswerGuid(), childQuestionDto, childAnswerSubmission.getValue()));
                    });
                    compAnswer.addRowOfChildAnswers(childAnswersRow);
                } else {
                    haltError.accept("An composite answer submission was expected to be an array but was not");
                }
            });
            return compAnswer;
        } else {
            LOG.info("Provided answer value for question stable id {} and with "
                    + "answer guid {} is expected to be a JSON array", parentStableId, answerGuid);
            return null;
        }
    }

    /**
     * Converts given data to agreement answer.
     *
     * @param stableId the question stable id
     * @param guid     the answer guid, or null
     * @param value    the answer value
     * @return agreement answer object, or null if value is not as expected
     */
    private AgreementAnswer convertAgreementAnswer(String stableId, String guid, JsonElement value) {
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return new AgreementAnswer(null, stableId, guid, value.getAsJsonPrimitive().getAsBoolean());
        }
        return null;
    }

    /**
     * Runs constraint checks on provided answer. Halts with a 400 response if there are errors.
     *
     * @param response the spark response
     * @param answer   the answer to check
     */
    private void checkAnswerConstraints(Response response, Answer answer) {
        List<JsonValidationError> errors = checker.validateAsJson(answer);
        if (!errors.isEmpty()) {
            String errorMsg = errors.stream()
                    .map(JsonValidationError::toDisplayMessage)
                    .collect(Collectors.joining(", "));
            String msg = "Answer submission with stable id " + answer.getQuestionStableId()
                    + " and answer guid " + answer.getAnswerGuid() + " failed check: " + errorMsg;
            LOG.warn(msg);
            ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.BAD_PAYLOAD, msg));
        }
        if (QuestionType.DATE.equals(answer.getQuestionType())) {
            Optional<String> failure = ((DateAnswer) answer).getValue().checkFieldCompatibility();
            if (failure.isPresent()) {
                String msg = "Answer submission with stable id " + answer.getQuestionStableId()
                        + " and answer guid " + answer.getAnswerGuid() + " failed check: "
                        + failure.get();
                LOG.warn(msg);
                ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.BAD_PAYLOAD, msg));
            }
        }
    }

    /**
     * Ensure the given answer passes its validation rules.
     *
     * @param answer       the answer to validate
     * @param instanceGuid the activity instance guid
     * @param question     the question object
     * @return the failed validation rule info, or null if validation passed
     */
    private Rule validateAnswer(Answer answer, String instanceGuid, Question question) {
        List<Answer> answersToCheck = new ArrayList<>();
        answersToCheck.add(answer);
        if (answer.getQuestionType() == QuestionType.COMPOSITE) {
            ((CompositeAnswer) answer).getValue().stream()
                    .map(answerRow -> answerRow.getValues())
                    .flatMap(Collection::stream)
                    .filter(each -> each != null)
                    .forEach(answersToCheck::add);
        }

        Map<String, Question> questionMap = new HashMap<>();
        questionMap.put(question.getStableId(), question);
        if (question.getQuestionType() == QuestionType.COMPOSITE) {
            ((CompositeQuestion) question).getChildren()
                    .forEach(child -> questionMap.put(child.getStableId(), child));
        }

        for (Answer currentAnswer : answersToCheck) {
            Question currentQuestion = questionMap.get(currentAnswer.getQuestionStableId());
            if (currentQuestion == null) {
                String msg = "Could not find question using activity instance guid " + instanceGuid
                        + " and question stable id " + currentAnswer.getQuestionStableId();
                LOG.warn(msg);
                throw new NoSuchElementException(msg);
            }

            // For some reason if I don't declare intermediate stream of type Stream<Rule>
            // the filter considers the parameter to be of type Object
            Stream<Rule> questionRules = currentQuestion.getValidations().stream();
            Optional<Rule> firstFailedRule = questionRules
                    .filter(rule -> !rule.getAllowSave())
                    .filter(rule -> !rule.validate(currentQuestion, currentAnswer))
                    .findFirst();
            if (firstFailedRule.isPresent()) {
                return firstFailedRule.get();
            }
        }

        return null;
    }
}
