package org.broadinstitute.ddp.studybuilder.task;

import com.typesafe.config.Config;
import org.broadinstitute.ddp.db.dao.ActivityDao;
import org.broadinstitute.ddp.db.dao.JdbiQuestion;
import org.broadinstitute.ddp.db.dao.JdbiRevision;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.QuestionDao;
import org.broadinstitute.ddp.db.dao.UserDao;
import org.broadinstitute.ddp.db.dto.QuestionDto;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.user.User;
import org.broadinstitute.ddp.studybuilder.ActivityBuilder;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;

/**
 * One-off task to add adhoc symptom message to TestBoston in deployed environments.
 */
public class OsteoAboutChildV2 implements CustomTask {

    private static final Logger LOG = LoggerFactory.getLogger(OsteoAboutChildV2.class);
    private static final String OSTEO = "CMI-OSTEO";
    private static final String ACTIVITY_CODE = "ABOUTCHILD";
    private static final String VERSION_TAG = "v2";

    private Config studyCfg;
    private Instant timestamp;

    @Override
    public void init(Path cfgPath, Config studyCfg, Config varsCfg) {
        if (!studyCfg.getString("study.guid").equals(OSTEO)) {
            throw new DDPException("This task is only for the " + OSTEO + " study!");
        }

        this.studyCfg = studyCfg;
        this.timestamp = Instant.now();
    }

    @Override
    public void run(Handle handle) {

        final User adminUser = handle.attach(UserDao.class).findUserByGuid(studyCfg.getString("adminUser.guid"))
                .orElseThrow(() -> new DDPException("Could not find admin user by guid"));
        final StudyDto studyDto = handle.attach(JdbiUmbrellaStudy.class).findByStudyGuid(studyCfg.getString("study.guid"));
        final ActivityDao activityDao = handle.attach(ActivityDao.class);
        final SqlHelper helper = handle.attach(SqlHelper.class);
        final JdbiRevision jdbiRevision = handle.attach(JdbiRevision.class);
        final JdbiQuestion jdbiQuestion = handle.attach(JdbiQuestion.class);
        final QuestionDao questionDao = handle.attach(QuestionDao.class);

        final String studyGuid = studyDto.getGuid();
        final long studyId = studyDto.getId();
        final long activityId = ActivityBuilder.findActivityId(handle, studyId, ACTIVITY_CODE);

        final String reason = String.format(
                "Update activity with studyGuid=%s activityCode=%s to versionTag=%s",
                studyGuid, ACTIVITY_CODE, VERSION_TAG);

        final RevisionMetadata meta = new RevisionMetadata(timestamp.toEpochMilli(), adminUser.getId(), reason);

        //change version
        activityDao.changeVersion(activityId, VERSION_TAG, meta);

        // Disable CHILD_HOW_HEAR
        QuestionDto questionHowHereDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "CHILD_HOW_HEAR")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        final long terminatedRevId = jdbiRevision.copyAndTerminate(questionHowHereDto.getRevisionId(), meta);

        long currBlockId = helper.findQuestionBlockId(questionHowHereDto.getId());
        questionDao.disableTextQuestion(questionHowHereDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currBlockId, terminatedRevId);

        LOG.info("Question ('CHILD_HOW_HEAR') successfully disabled");


        // Disable CHILD_EXPERIENCE
        QuestionDto questionExperienceDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "CHILD_EXPERIENCE")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        long currExperienceBlockId = helper.findQuestionBlockId(questionExperienceDto.getId());
        questionDao.disableTextQuestion(questionExperienceDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currExperienceBlockId, terminatedRevId);

        LOG.info("Question ('CHILD_EXPERIENCE') successfully disabled");


        // Disable CHILD_RACE
        QuestionDto questionRaceDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "CHILD_RACE")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        long currRaceBlockId = helper.findQuestionBlockId(questionRaceDto.getId());
        questionDao.disablePicklistQuestion(questionRaceDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currRaceBlockId, terminatedRevId);

        LOG.info("Question ('CHILD_RACE') successfully disabled");


        // Disable CHILD_HISPANIC
        QuestionDto questionHispanicDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "CHILD_HISPANIC")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        long currHispanicBlockId = helper.findQuestionBlockId(questionHispanicDto.getId());
        questionDao.disablePicklistQuestion(questionHispanicDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currHispanicBlockId, terminatedRevId);

        LOG.info("Question ('CHILD_HISPANIC') successfully disabled");
    }

    private interface SqlHelper extends SqlObject {
        @SqlQuery("select block_id from block__question where question_id = :questionId")
        int findQuestionBlockId(@Bind("questionId") long questionId);

        @SqlUpdate("update form_section__block set revision_id = :revisionId where block_id = :blockId")
        void updateFormSectionBlockRevision(@Bind("blockId") long blockId, @Bind("revisionId") long revisionId);
    }

}
