package org.broadinstitute.dsm.model.filter.prefilter.osteo;

import org.broadinstitute.dsm.db.dto.ddp.instance.DDPInstanceDto;
import org.broadinstitute.dsm.model.elastic.ESActivities;
import org.broadinstitute.dsm.model.elastic.search.ElasticSearchParticipantDto;
import org.broadinstitute.dsm.model.filter.prefilter.StudyPreFilter;
import org.broadinstitute.dsm.model.filter.prefilter.StudyPreFilterPayload;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;

public class OldOsteoPreFilterTest {

    @Test
    public void filter() {
        int ddpInstanceId = 1;
        String newOsteoInstanceName = "Osteo";
        List<ESActivities> activities = new ArrayList<>(List.of(
                new ESActivities("CONSENT", "v2"),
                new ESActivities("PREQUAL", "v2"),
                new ESActivities("FAMILY_HISTORY", "v1"),
                new ESActivities("ABOUT_YOU", "v1")
        ));
        ElasticSearchParticipantDto esDto = new ElasticSearchParticipantDto.Builder().withActivities(activities).build();
        DDPInstanceDto ddpInstanceDto = new DDPInstanceDto.Builder().withInstanceName(newOsteoInstanceName).withDdpInstanceId(ddpInstanceId).build();
        Optional<StudyPreFilter> preFilter = StudyPreFilter.fromPayload(StudyPreFilterPayload.of(esDto, ddpInstanceDto));
        preFilter.ifPresent(StudyPreFilter::filter);
        assertEquals(2, esDto.getActivities().size());
        assertEquals(List.of("FAMILY_HISTORY", "ABOUT_YOU"), esDto.getActivities().stream().map(ESActivities::getActivityCode).collect(Collectors.toList()));
    }
}