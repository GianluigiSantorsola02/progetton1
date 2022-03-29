package org.broadinstitute.ddp.studybuilder.task;

import com.google.gson.Gson;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.broadinstitute.ddp.db.DBUtils;
import org.broadinstitute.ddp.db.dao.ActivityDao;
import org.broadinstitute.ddp.db.dao.CopyConfigurationSql;
import org.broadinstitute.ddp.db.dao.JdbiFormActivityFormSection;
import org.broadinstitute.ddp.db.dao.JdbiFormSection;
import org.broadinstitute.ddp.db.dao.JdbiQuestion;
import org.broadinstitute.ddp.db.dao.JdbiRevision;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.QuestionDao;
import org.broadinstitute.ddp.db.dao.SectionBlockDao;
import org.broadinstitute.ddp.db.dao.UserDao;
import org.broadinstitute.ddp.db.dto.QuestionDto;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.model.activity.definition.FormBlockDef;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.user.User;
import org.broadinstitute.ddp.studybuilder.ActivityBuilder;
import org.broadinstitute.ddp.util.ConfigUtil;
import org.broadinstitute.ddp.util.GsonUtil;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

/**
 * One-off task to add adhoc symptom message to TestBoston in deployed environments.
 */
public class OsteoAboutYouV2 implements CustomTask {

    private static final Logger LOG = LoggerFactory.getLogger(OsteoAboutYouV2.class);
    private static final String DATA_FILE = "patches/about-you-v2.conf";
    private static final String STUDY_GUID = "CMI-OSTEO";
    private static final String ACTIVITY_CODE = "ABOUTYOU";
    private static final String VERSION_TAG = "v2";

    private Config studyCfg;
    private Instant timestamp;
    private Config dataCfg;
    private Gson gson;

    @Override
    public void init(Path cfgPath, Config studyCfg, Config varsCfg) {
        File file = cfgPath.getParent().resolve(DATA_FILE).toFile();
        if (!file.exists()) {
            throw new DDPException("Data file is missing: " + file);
        }

        this.dataCfg = ConfigFactory.parseFile(file);

        if (!studyCfg.getString("study.guid").equals(STUDY_GUID)) {
            throw new DDPException("This task is only for the " + STUDY_GUID + " study!");
        }

        this.studyCfg = studyCfg;
        this.timestamp = Instant.now();
        this.gson = GsonUtil.standardGson();
    }

    @Override
    public void run(Handle handle) {

        final User adminUser = handle.attach(UserDao.class).findUserByGuid(studyCfg.getString("adminUser.guid"))
                .orElseThrow(() -> new DDPException("Could not find admin user by guid"));

        final StudyDto studyDto = handle.attach(JdbiUmbrellaStudy.class)
                .findByStudyGuid(studyCfg.getString("study.guid"));

        final SqlHelper helper = handle.attach(SqlHelper.class);
        final ActivityDao activityDao = handle.attach(ActivityDao.class);
        final QuestionDao questionDao = handle.attach(QuestionDao.class);
        final JdbiRevision jdbiRevision = handle.attach(JdbiRevision.class);
        final JdbiQuestion jdbiQuestion = handle.attach(JdbiQuestion.class);
        final JdbiFormSection jdbiFormSection = handle.attach(JdbiFormSection.class);
        final JdbiFormActivityFormSection jdbiFormActivityFormSection = handle.attach(JdbiFormActivityFormSection.class);
        final CopyConfigurationSql copyConfigurationSql = handle.attach(CopyConfigurationSql.class);

        final String studyGuid = studyDto.getGuid();
        final long studyId = studyDto.getId();
        final long activityId = ActivityBuilder.findActivityId(handle, studyId, ACTIVITY_CODE);

        final String reason = String.format(
                "Update activity with studyGuid=%s activityCode=%s to versionTag=%s",
                studyGuid, ACTIVITY_CODE, VERSION_TAG);
        final RevisionMetadata meta = new RevisionMetadata(timestamp.toEpochMilli(), adminUser.getId(), reason);

        //change version
        var versionDto = activityDao.changeVersion(activityId, VERSION_TAG, meta);

        // Disable HOW_HEAR
        QuestionDto questionHowHereDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "HOW_HEAR")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        final long terminatedRevId = jdbiRevision.copyAndTerminate(questionHowHereDto.getRevisionId(), meta);

        final long currHowHereBlockId = helper.findQuestionBlockId(questionHowHereDto.getId());
        questionDao.disableTextQuestion(questionHowHereDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currHowHereBlockId, terminatedRevId);
        LOG.info("Question ('HOW_HEAR') successfully disabled");

        // Disable EXPERIENCE
        QuestionDto questionExperienceDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "EXPERIENCE")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        final long currExperienceBlockId = helper.findQuestionBlockId(questionExperienceDto.getId());
        questionDao.disableTextQuestion(questionExperienceDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currExperienceBlockId, terminatedRevId);
        LOG.info("Question ('EXPERIENCE') successfully disabled");

        // Disable RACE
        QuestionDto questionRaceDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "RACE")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        final long currRaceBlockId = helper.findQuestionBlockId(questionRaceDto.getId());
        questionDao.disablePicklistQuestion(questionRaceDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currRaceBlockId, terminatedRevId);
        LOG.info("Question ('RACE') successfully disabled");

        // Disable HISPANIC
        QuestionDto questionHispanicDto =
                jdbiQuestion.findLatestDtoByStudyIdAndQuestionStableId(studyId, "HISPANIC")
                        .orElseThrow(() -> new DDPException("Could not find question dto by studyId and question sid"));

        final long currHispanicBlockId = helper.findQuestionBlockId(questionHispanicDto.getId());
        questionDao.disablePicklistQuestion(questionHispanicDto.getId(), meta);
        helper.updateFormSectionBlockRevision(currHispanicBlockId, terminatedRevId);
        LOG.info("Question ('HISPANIC') successfully disabled");

        //add new section
        final var firstSection = jdbiFormActivityFormSection
                .findOrderedSectionMemberships(activityId, meta.getTimestamp()).get(0);
        final long newFormSectionId = jdbiFormSection.insert(jdbiFormSection.generateUniqueCode(), null);
        final int sectionOrder = firstSection.getDisplayOrder() - 1;
        jdbiFormActivityFormSection.insert(activityId, newFormSectionId, versionDto.getRevId(), sectionOrder);
        LOG.info("New section successfully created with displayOrder={} and revision={}", sectionOrder, versionDto.getRevId());

        //add new WHO_IS_FILLING_ABOUTYOU question
        SectionBlockDao sectionBlockDao = handle.attach(SectionBlockDao.class);
        FormBlockDef raceDef = gson.fromJson(ConfigUtil.toJson(dataCfg.getConfig("who_filling_q")), FormBlockDef.class);
        sectionBlockDao.insertBlockForSection(activityId, newFormSectionId, sectionOrder, raceDef, versionDto.getRevId());
        LOG.info("Question ('WHO_IS_FILLING_ABOUTYOU') successfully added");

        // Delete copy configs
        Set<Long> locationIds = helper.findCopyConfigsByQuestionSid(Set.of(
                questionHowHereDto.getId(),
                questionExperienceDto.getId(),
                questionRaceDto.getId(),
                questionHispanicDto.getId()));

        Set<Long> configPairs = helper.findCopyConfigPairsByLocIds(locationIds);

        DBUtils.checkDelete(configPairs.size(), copyConfigurationSql.deleteCopyConfigPairs(configPairs));
        DBUtils.checkDelete(locationIds.size(), copyConfigurationSql.bulkDeleteCopyLocations(locationIds));
        LOG.info("Copy configs successfully deleted");
    }

    private interface SqlHelper extends SqlObject {
        @SqlQuery("select block_id from block__question where question_id = :questionId")
        int findQuestionBlockId(@Bind("questionId") long questionId);

        @SqlQuery("select copy_location_id from copy_answer_location where question_stable_code_id in (<questionSid>)")
        Set<Long> findCopyConfigsByQuestionSid(
                @BindList(value = "questionSid", onEmpty = BindList.EmptyHandling.NULL) Set<Long> questionSid);

        @SqlQuery("select copy_configuration_pair_id from copy_configuration_pair "
                + "where source_location_id in (<locIds>) or target_location_id in (<locIds>)")
        Set<Long> findCopyConfigPairsByLocIds(
                @BindList(value = "locIds", onEmpty = BindList.EmptyHandling.NULL) Set<Long> locIds);

        @SqlUpdate("update form_section__block set revision_id = :revisionId where block_id = :blockId")
        void updateFormSectionBlockRevision(@Bind("blockId") long blockId, @Bind("revisionId") long revisionId);
    }
}