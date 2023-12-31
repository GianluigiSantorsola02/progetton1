package org.broadinstitute.ddp.route;

import static io.restassured.RestAssured.given;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.restassured.http.ContentType;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.util.EntityUtils;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.constants.RouteConstants.API;
import org.broadinstitute.ddp.constants.RouteConstants.PathParam;
import org.broadinstitute.ddp.db.AnswerDao;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.ActivityDao;
import org.broadinstitute.ddp.db.dao.ActivityInstanceDao;
import org.broadinstitute.ddp.db.dao.JdbiActivity;
import org.broadinstitute.ddp.db.dao.JdbiActivityInstance;
import org.broadinstitute.ddp.db.dao.JdbiLanguageCode;
import org.broadinstitute.ddp.db.dao.JdbiStudyActivityDashboardNameTranslation;
import org.broadinstitute.ddp.json.activity.ActivityInstanceSummary;
import org.broadinstitute.ddp.model.activity.definition.FormActivityDef;
import org.broadinstitute.ddp.model.activity.definition.FormSectionDef;
import org.broadinstitute.ddp.model.activity.definition.QuestionBlockDef;
import org.broadinstitute.ddp.model.activity.definition.i18n.SummaryTranslation;
import org.broadinstitute.ddp.model.activity.definition.i18n.Translation;
import org.broadinstitute.ddp.model.activity.definition.question.BoolQuestionDef;
import org.broadinstitute.ddp.model.activity.definition.question.TextQuestionDef;
import org.broadinstitute.ddp.model.activity.definition.template.Template;
import org.broadinstitute.ddp.model.activity.instance.answer.Answer;
import org.broadinstitute.ddp.model.activity.instance.answer.BoolAnswer;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.activity.types.ActivityType;
import org.broadinstitute.ddp.model.activity.types.FormType;
import org.broadinstitute.ddp.model.activity.types.InstanceStatusType;
import org.broadinstitute.ddp.model.activity.types.QuestionType;
import org.broadinstitute.ddp.model.activity.types.TemplateType;
import org.broadinstitute.ddp.model.activity.types.TextInputType;
import org.broadinstitute.ddp.util.ConfigManager;
import org.broadinstitute.ddp.util.TestDataSetupUtil;
import org.jdbi.v3.core.Handle;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class UserActivityInstanceListRouteTest extends IntegrationTestSuite.TestCase {

    private static TestDataSetupUtil.GeneratedTestData testData;
    private static FormActivityDef prequal;
    private static String prequal1Guid;
    private static String userGuid;
    private static String token;
    private static String url;
    private static long answerId;
    private static AnswerDao answerDao;
    private static Gson gson;
    private static final String DUMMY_SUMMARY_FOR_CREATED = "Dummy summary for CREATED";

    @BeforeClass
    public static void setup() {
        gson = new Gson();
        Config cfg = ConfigManager.getInstance().getConfig();
        Config sqlConfig = ConfigFactory.parseResources(ConfigFile.SQL_CONF);
        answerDao = AnswerDao.fromSqlConfig(sqlConfig);
        TransactionWrapper.useTxn(handle -> {
            testData = TestDataSetupUtil.generateBasicUserTestData(handle);
            token = testData.getTestingUser().getToken();
            userGuid = testData.getUserGuid();
            setupActivityAndInstance(handle);
        });
        String endpoint = API.USER_ACTIVITIES
                .replace(PathParam.USER_GUID, userGuid)
                .replace(PathParam.STUDY_GUID, testData.getStudyGuid());
        url = RouteTestUtil.getTestingBaseUrl() + endpoint;
    }

    private static void setupActivityAndInstance(Handle handle) {
        String code = "ACT_INST_LIST_ROUTE_" + Instant.now().toEpochMilli();

        String toggleQuestionStableId = "SID_" + System.currentTimeMillis();
        String textQuestionStableId = "SIDT_" + System.currentTimeMillis();


        Template prompt = new Template(TemplateType.TEXT, null, "question that controls toggled block");
        Template yes = new Template(TemplateType.TEXT, null, "show");
        Template no = new Template(TemplateType.TEXT, null, "hide");

        QuestionBlockDef controlBlock = new QuestionBlockDef(BoolQuestionDef.builder(toggleQuestionStableId, prompt, yes, no).build());

        QuestionBlockDef toggledBlock = new QuestionBlockDef(TextQuestionDef.builder(
                TextInputType.TEXT, textQuestionStableId, Template.text("foo")).build());

        String expr = String.format("user.studies[\"%s\"].forms[\"%s\"].questions[\"%s\"].answers.hasTrue()",
                testData.getStudyGuid(), code, toggleQuestionStableId);
        toggledBlock.setShownExpr(expr);

        prequal = FormActivityDef.formBuilder(FormType.PREQUALIFIER, code, "v1", testData.getStudyGuid())
                .addName(new Translation("en", "activity " + code))
                .addSummary(new SummaryTranslation("en", DUMMY_SUMMARY_FOR_CREATED, InstanceStatusType.CREATED))
                .addSection(new FormSectionDef(null, Arrays.asList(controlBlock, toggledBlock)))
                .build();
        handle.attach(ActivityDao.class).insertActivity(prequal,
                RevisionMetadata.now(testData.getUserId(), "add " + code));

        assertNotNull(prequal.getActivityId());
        prequal1Guid = handle.attach(ActivityInstanceDao.class)
                .insertInstance(prequal.getActivityId(), userGuid)
                .getGuid();

        Answer answer = new BoolAnswer(null, toggleQuestionStableId, null, true);
        answerDao.createAnswer(handle, answer, userGuid, prequal1Guid);
        answerId = answer.getAnswerId();

    }

    @AfterClass
    public static void cleanup() {
        TransactionWrapper.useTxn(handle -> {
            answerDao.deleteAnswerByIdAndType(handle, answerId, QuestionType.BOOLEAN);
            handle.attach(ActivityInstanceDao.class).deleteByInstanceGuid(prequal1Guid);
        });
    }

    /**
     * Calls activity instances list endpoint.
     */
    private List<ActivityInstanceSummary> getUserActivities() throws Exception {
        Request request = RouteTestUtil.buildAuthorizedGetRequest(token, url);
        HttpResponse response = request.execute().returnResponse();
        assertEquals(200, response.getStatusLine().getStatusCode());

        ActivityInstanceSummary[] userActivities
                = gson.fromJson(EntityUtils.toString(response.getEntity()), ActivityInstanceSummary[].class);
        assertNotEquals("No activities found for test user", 0, userActivities.length);

        return Arrays.asList(userActivities);
    }

    @Test
    public void testGetUserActivities() throws Exception {
        List<ActivityInstanceSummary> userActivities = getUserActivities();
        Assert.assertFalse(userActivities.isEmpty());
        boolean hasPrequal = false;
        boolean hasReadonlyActivities = false;
        assertEquals(userActivities.size(), 1);
        ActivityInstanceSummary userActivity = userActivities.get(0);
        assertEquals(userActivity.getNumQuestionsAnswered(), 1);
        assertEquals(userActivity.getNumQuestions(), 2);
        if (ActivityType.FORMS.name().equals(userActivity.getActivityType())) {
            if (FormType.PREQUALIFIER.name().equals(userActivity.getActivitySubType())) {
                hasPrequal = true;
            }
        }

        if (userActivity.isReadonly() != null && userActivity.isReadonly()) {
            hasReadonlyActivities = true;
        }

        Assert.assertTrue(StringUtils.isNotBlank(userActivity.getActivityCode()));
        Assert.assertTrue(StringUtils.isNotBlank(userActivity.getActivityName()));
        Assert.assertNotNull(userActivity.getActivitySummary());
        Assert.assertTrue(
                userActivity.getActivitySummary().equals(DUMMY_SUMMARY_FOR_CREATED)
        );

        Assert.assertTrue("Could not find prequal in list of activity instances for test user", hasPrequal);
        Assert.assertFalse(hasReadonlyActivities);
    }

    @Test
    public void testReadonlyActivities() throws Exception {
        Supplier<Long> userActivitiesCounter = () -> {
            try {
                return getUserActivities().stream().filter(ActivityInstanceSummary::isReadonly).count();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        List<ActivityInstanceSummary> userActivities = getUserActivities();
        Assert.assertEquals(0L, userActivitiesCounter.get().longValue());
        Assert.assertFalse(userActivities.isEmpty());

        ActivityInstanceSummary firstActivityInstanceSummary = userActivities.get(0);
        String firstActivityCode = firstActivityInstanceSummary.getActivityCode();
        String firstActivityInstanceGuid = firstActivityInstanceSummary.getActivityInstanceGuid();

        TransactionWrapper.withTxn(handle -> handle.attach(JdbiActivity.class)
                .updateEditTimeoutSecByCode(1L, firstActivityCode, testData.getStudyId()));
        TimeUnit.SECONDS.sleep(1L);
        Assert.assertEquals(1L, userActivitiesCounter.get().longValue());

        TransactionWrapper.withTxn(handle -> handle.attach(JdbiActivity.class)
                .updateEditTimeoutSecByCode(null, firstActivityCode, testData.getStudyId()));

        // Now testing if "is_readonly" overrides the on-the-fly value properly

        TransactionWrapper.withTxn(handle -> handle.attach(JdbiActivityInstance.class)
                .updateIsReadonlyByGuid(true, firstActivityInstanceGuid));
        Assert.assertEquals(1L, userActivitiesCounter.get().longValue());

        TransactionWrapper.withTxn(handle -> handle.attach(JdbiActivityInstance.class)
                .updateIsReadonlyByGuid(false, firstActivityInstanceGuid));
        Assert.assertEquals(0L, userActivitiesCounter.get().longValue());
    }

    @Test
    public void testWhenJustOneInstanceExists_itIsNotNumbered() throws Exception {
        long dashboardNameId = TransactionWrapper.withTxn(handle -> {
            long languageCodeId = handle.attach(JdbiLanguageCode.class).getLanguageCodeId("en");
            return handle.attach(JdbiStudyActivityDashboardNameTranslation.class).insert(
                    prequal.getActivityId(), languageCodeId, "Test prequal"
            );
        });
        List<ActivityInstanceSummary> userActivities = getUserActivities();
        Matcher matcher = Pattern.compile("\\d$").matcher(userActivities.get(0).getActivityDashboardName());
        Assert.assertFalse("The single summary in a group should not be numbered", matcher.find());
        TransactionWrapper.useTxn(handle -> {
            handle.attach(JdbiStudyActivityDashboardNameTranslation.class).deleteById(dashboardNameId);
        });
    }

    @Test
    public void testWhenMultipleInstanceWithTheSameActivityCodeExist_theyAreNumberedCorrectly() throws Exception {
        String prequal2Guid = TransactionWrapper.withTxn(handle -> {
            return handle.attach(ActivityInstanceDao.class)
                    .insertInstance(prequal.getActivityId(), userGuid)
                    .getGuid();
        });
        long dashboardNameId = TransactionWrapper.withTxn(handle -> {
            long languageCodeId = handle.attach(JdbiLanguageCode.class).getLanguageCodeId("en");
            return handle.attach(JdbiStudyActivityDashboardNameTranslation.class).insert(
                    prequal.getActivityId(), languageCodeId, "Test prequal"
            );
        });
        List<ActivityInstanceSummary> userActivities = getUserActivities();
        Assert.assertEquals("The number of instances didn't match the expectation", 2, userActivities.size());
        ActivityInstanceSummary firstPrequal = userActivities
                .stream()
                .filter(p -> p.getActivityInstanceGuid().equals(prequal1Guid))
                .collect(Collectors.toList()).get(0);
        Matcher matcher = Pattern.compile("\\d$").matcher(firstPrequal.getActivityDashboardName());
        Assert.assertFalse("The first summary in a group should not be numbered", matcher.find());
        ActivityInstanceSummary mostRecentPrequal = userActivities
                .stream()
                .filter(p -> p.getActivityInstanceGuid().equals(prequal2Guid))
                .collect(Collectors.toList()).get(0);
        Assert.assertTrue(
                "Numbering does not respect the instance creation date",
                mostRecentPrequal.getActivityDashboardName().endsWith("#2")
        );
        TransactionWrapper.useTxn(handle -> {
            handle.attach(JdbiStudyActivityDashboardNameTranslation.class).deleteById(dashboardNameId);
            handle.attach(ActivityInstanceDao.class).deleteByInstanceGuid(prequal2Guid);
        });
    }

    @Test
    public void testWhenDashboardNameIsMissing_itGetsDefaultedToActivityName() throws Exception {
        List<ActivityInstanceSummary> userActivities = getUserActivities();
        Assert.assertEquals(
                "Missing dashboard name was not defaulted to activity name",
                userActivities.get(0).getActivityName(),
                userActivities.get(0).getActivityDashboardName()
        );
    }

    @Test
    public void test_whenActivityIsExcludeFromDisplay_activityInstancesAreNotReturned() {
        TransactionWrapper.useTxn(handle -> assertEquals(1, handle.attach(JdbiActivity.class)
                .updateExcludeFromDisplayById(prequal.getActivityId(), true)));

        String body = given().auth().oauth2(token)
                .when().get(url)
                .then().assertThat()
                .statusCode(200).contentType(ContentType.JSON)
                .and().extract().body().asString();

        ActivityInstanceSummary[] activities = gson.fromJson(body, ActivityInstanceSummary[].class);

        try {
            // Since this test only has one activity, there should not be any instances returned.
            assertEquals(0, activities.length);
        } finally {
            TransactionWrapper.useTxn(handle -> assertEquals(1, handle.attach(JdbiActivity.class)
                    .updateExcludeFromDisplayById(prequal.getActivityId(), false)));
        }
    }
}
