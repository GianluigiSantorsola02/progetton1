package org.broadinstitute.ddp.db.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.broadinstitute.ddp.TxnAwareBaseTest;
import org.broadinstitute.ddp.db.AnswerDao;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.UserDaoFactory;
import org.broadinstitute.ddp.db.dto.ActivityInstanceDto;
import org.broadinstitute.ddp.db.dto.UserDto;
import org.broadinstitute.ddp.db.dto.UserProfileDto;
import org.broadinstitute.ddp.model.activity.definition.FormActivityDef;
import org.broadinstitute.ddp.model.activity.definition.FormSectionDef;
import org.broadinstitute.ddp.model.activity.definition.QuestionBlockDef;
import org.broadinstitute.ddp.model.activity.definition.i18n.Translation;
import org.broadinstitute.ddp.model.activity.definition.question.TextQuestionDef;
import org.broadinstitute.ddp.model.activity.definition.template.Template;
import org.broadinstitute.ddp.model.activity.instance.answer.TextAnswer;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.activity.types.TextInputType;
import org.broadinstitute.ddp.util.TestDataSetupUtil;
import org.jdbi.v3.core.Handle;
import org.junit.BeforeClass;
import org.junit.Test;

public class UserDaoTest extends TxnAwareBaseTest {

    private static TestDataSetupUtil.GeneratedTestData testData;
    private static FormActivityDef form;
    private static String textSid;

    @BeforeClass
    public static void setup() {
        TransactionWrapper.useTxn(handle -> {
            testData = TestDataSetupUtil.generateBasicUserTestData(handle);

            long now = Instant.now().toEpochMilli();
            textSid = "text" + now;

            form = FormActivityDef.generalFormBuilder("ACT" + now, "v1", testData.getStudyGuid())
                    .addName(new Translation("en", "dummy activity"))
                    .addSection(new FormSectionDef(null, Arrays.asList(
                            new QuestionBlockDef(TextQuestionDef
                                    .builder(TextInputType.TEXT, textSid, Template.text("text"))
                                    .build()))))
                    .build();
            handle.attach(ActivityDao.class).insertActivity(form, RevisionMetadata.now(testData.getUserId(), "test"));
            assertNotNull(form.getActivityId());
        });
    }

    @Test
    public void testDeleteTempUsers_noExpiredUsers() {
        TransactionWrapper.useTxn(handle -> {
            long now = Instant.now().toEpochMilli();
            UserDao userDao = handle.attach(UserDao.class);

            assertTrue(userDao.findExpiredTemporaryUserIds(now).isEmpty());
            assertEquals(0, userDao.deleteAllExpiredTemporaryUsers());
        });
    }

    @Test
    public void testDeleteTempUsers_onlyDeletesDataForExpiredTempUsers() {
        TransactionWrapper.useTxn(handle -> {
            UserDto tempUser1 = newTempUser(handle);
            populateData(handle, tempUser1);

            UserDto tempUser2 = newTempUser(handle);
            populateData(handle, tempUser2);

            UserDto permUser = newTempUser(handle);
            populateData(handle, permUser);
            handle.attach(UserDao.class).upgradeUserToPermanentById(permUser.getUserId(), "fake123");

            // Expire the temp user
            long now = Instant.now().toEpochMilli();
            handle.attach(JdbiUser.class).updateExpiresAtById(tempUser1.getUserId(), now - 10000);

            UserDao userDao = handle.attach(UserDao.class);
            assertEquals(1, userDao.deleteAllExpiredTemporaryUsers());

            ensureDataExists(handle, tempUser2);
            ensureDataExists(handle, permUser);

            handle.rollback();
        });
    }

    private UserDto newTempUser(Handle handle) {
        return UserDaoFactory.createFromSqlConfig(sqlConfig)
                .createTemporaryUser(handle, testData.getAuth0ClientId());
    }

    private void populateData(Handle handle, UserDto tempUser) {
        long langId = handle.attach(JdbiLanguageCode.class).getLanguageCodeId("en");

        assertEquals(1, handle.attach(JdbiProfile.class)
                .insert(new UserProfileDto(
                        tempUser.getUserId(), "first", "last", null, null, null, null, langId, "en", false)));

        ActivityInstanceDto instance = handle.attach(ActivityInstanceDao.class)
                .insertInstance(form.getActivityId(), tempUser.getUserGuid());

        AnswerDao answerDao = AnswerDao.fromSqlConfig(sqlConfig);
        assertNotNull(answerDao.createAnswer(handle,
                new TextAnswer(null, textSid, null, "ans"),
                tempUser.getUserGuid(), instance.getGuid()));
    }

    private void ensureDataExists(Handle handle, UserDto userDto) {
        assertNotNull(handle.attach(JdbiProfile.class)
                .getUserProfileByUserId(userDto.getUserId()));

        List<ActivityInstanceDto> instances = handle.attach(JdbiActivityInstance.class)
                .findAllByUserIdAndStudyId(userDto.getUserId(), testData.getStudyId());

        assertEquals(1, instances.size());
        assertEquals(1, AnswerDao.fromSqlConfig(sqlConfig)
                .getAnswerGuidsForQuestion(handle, instances.get(0).getGuid(), textSid)
                .size());
    }
}
