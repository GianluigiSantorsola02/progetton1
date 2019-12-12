package org.broadinstitute.ddp.db.dao;

import java.util.NoSuchElementException;

import org.broadinstitute.ddp.db.DaoException;
import org.broadinstitute.ddp.db.dto.ActivityDto;
import org.broadinstitute.ddp.db.dto.ActivityVersionDto;
import org.broadinstitute.ddp.model.activity.definition.ActivityDef;
import org.broadinstitute.ddp.model.activity.definition.ConsentActivityDef;
import org.broadinstitute.ddp.model.activity.definition.FormActivityDef;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.activity.types.ActivityType;
import org.jdbi.v3.sqlobject.CreateSqlObject;
import org.jdbi.v3.sqlobject.SqlObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface ActivityDao extends SqlObject {

    Logger LOG = LoggerFactory.getLogger(ActivityDao.class);

    @CreateSqlObject
    JdbiActivity getJdbiActivity();

    @CreateSqlObject
    JdbiActivityVersion getJdbiActivityVersion();

    @CreateSqlObject
    JdbiRevision getJdbiRevision();

    @CreateSqlObject
    FormActivityDao getFormActivityDao();

    @CreateSqlObject
    ConsentActivityDao getConsentActivityDao();


    /**
     * Convenience method to create new form activity by inserting all its related data. A new revision will be created
     * from given metadata and used for insertions.
     *
     * @param form the form activity definition
     * @param meta the revision metadata
     * @return activity version info
     */
    default ActivityVersionDto insertActivity(FormActivityDef form, RevisionMetadata meta) {
        long revId = getJdbiRevision().insertStart(meta.getTimestamp(), meta.getUserId(), meta.getReason());
        getFormActivityDao().insertActivity(form, revId);
        return new ActivityVersionDto(form.getVersionId(), form.getActivityId(), form.getVersionTag(),
                revId, meta.getTimestamp(), null);
    }

    /**
     * Convenience method to create new consent activity by inserting all its related data. A new revision will be
     * created from given metadata and used for insertions.
     *
     * @param consent the consent activity definition
     * @param meta    the revision metadata
     * @return activity version info
     */
    default ActivityVersionDto insertConsent(ConsentActivityDef consent, RevisionMetadata meta) {
        long revId = getJdbiRevision().insertStart(meta.getTimestamp(), meta.getUserId(), meta.getReason());
        getConsentActivityDao().insertActivity(consent, revId);
        return new ActivityVersionDto(consent.getVersionId(), consent.getActivityId(), consent.getVersionTag(),
                revId, meta.getTimestamp(), null);
    }

    /**
     * Create new version for given activity by terminating currently active version tag. A new revision will be
     * created for the new version tag using the given metadata.
     *
     * @param activityId the associated activity
     * @param versionTag the new version tag
     * @param meta       the revision metadata
     * @return the new activity version info
     */
    default ActivityVersionDto changeVersion(long activityId, String versionTag, RevisionMetadata meta) {
        JdbiActivityVersion jdbiVersion = getJdbiActivityVersion();
        JdbiRevision jdbiRev = getJdbiRevision();

        ActivityVersionDto currVersion = jdbiVersion.getActiveVersion(activityId)
                .orElseThrow(() -> new NoSuchElementException("Cannot find active version for activity " + activityId));
        if (meta.getTimestamp() < currVersion.getRevStart()) {
            throw new IllegalArgumentException("Timestamp " + meta.getTimestamp()
                    + " cannot be less than start time " + currVersion.getRevStart());
        }

        long oldRevId = currVersion.getRevId();
        long newRevId = jdbiRev.copyAndTerminate(oldRevId, meta);
        int numUpdated = jdbiVersion.updateRevisionIdById(currVersion.getId(), newRevId);
        if (numUpdated != 1) {
            throw new DaoException("Unable to terminate active activity version " + currVersion.getId());
        }
        if (jdbiRev.tryDeleteOrphanedRevision(oldRevId)) {
            LOG.info("Deleted orphaned revision {} by activity {}", oldRevId, activityId);
        }

        long newVersionRevId = jdbiRev.insert(meta.getUserId(), meta.getTimestamp(), null, meta.getReason());
        long newVersionId = jdbiVersion.insert(activityId, versionTag, newVersionRevId);

        return new ActivityVersionDto(newVersionId, activityId, versionTag, newVersionRevId, meta.getTimestamp(), null);
    }

    default ActivityDef findDefByDtoAndVersion(ActivityDto activityDto, ActivityVersionDto versionDto) {
        ActivityType type = getJdbiActivity().findActivityTypeById(activityDto.getActivityTypeId());
        if (ActivityType.FORMS.equals(type)) {
            return getFormActivityDao().findDefByDtoAndVersion(activityDto, versionDto);
        }
        throw new DaoException("Unhandled activity type " + type);
    }
}
