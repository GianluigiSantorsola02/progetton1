package org.broadinstitute.ddp.model.event;

import java.util.HashSet;
import java.util.Set;

import org.broadinstitute.ddp.db.dao.ActivityInstanceDao;
import org.broadinstitute.ddp.db.dto.EventConfigurationDto;
import org.broadinstitute.ddp.pex.PexInterpreter;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An action that marks all of a participant's activity instances for specified target activities as hidden.
 */
public class HideActivitiesEventAction extends EventAction {

    private static final Logger LOG = LoggerFactory.getLogger(HideActivitiesEventAction.class);

    private Set<Long> targetActivityIds = new HashSet<>();

    public HideActivitiesEventAction(EventConfiguration eventConfiguration, EventConfigurationDto dto) {
        this(eventConfiguration, dto.getTargetActivityIds());
    }

    public HideActivitiesEventAction(EventConfiguration eventConfiguration, Set<Long> targetActivityIds) {
        super(eventConfiguration, null);
        if (targetActivityIds != null) {
            this.targetActivityIds.addAll(targetActivityIds);
        }
    }

    @Override
    public void doAction(PexInterpreter interpreter, Handle handle, EventSignal signal) {
        int numUpdated = handle.attach(ActivityInstanceDao.class)
                .bulkUpdateIsHiddenByActivityIds(signal.getParticipantId(), true, targetActivityIds);
        LOG.info("Marked {} activity instances hidden for participant {} and target activities {}",
                numUpdated, signal.getParticipantGuid(), targetActivityIds);
    }
}