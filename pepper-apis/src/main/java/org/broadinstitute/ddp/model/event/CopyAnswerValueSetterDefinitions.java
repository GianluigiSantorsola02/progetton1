package org.broadinstitute.ddp.model.event;

import org.broadinstitute.ddp.db.dao.JdbiProfile;
import org.broadinstitute.ddp.exception.DDPException;

public class CopyAnswerValueSetterDefinitions {
    // If we implement this method, then we can use lambdas for the the setter implementations
    public interface StringValueSetter extends ValueSetter<String> {
        @Override
        default Class<String> getValueType() {
            return String.class;
        }
    }

    private static final StringValueSetter PARTICIPANT_PROFILE_FIRST_NAME_SETTER =
            (newValue, activityInstanceDto, handle) -> handle.attach(JdbiProfile.class)
                    .upsertFirstName(activityInstanceDto.getParticipantId(), newValue) >= 0;

    private static final StringValueSetter PARTICIPANT_PROFILE_LAST_NAME_SETTER =
            (newLastName, activityInstanceDto, handle) -> handle.attach(JdbiProfile.class)
                    .upsertLastName(activityInstanceDto.getParticipantId(), newLastName) >= 0;

    public static ValueSetter<?> findValueSetter(CopyAnswerTarget targetEnum) {
        switch (targetEnum) {
            case PARTICIPANT_PROFILE_LAST_NAME:
                return PARTICIPANT_PROFILE_LAST_NAME_SETTER;
            case PARTICIPANT_PROFILE_FIRST_NAME:
                return PARTICIPANT_PROFILE_FIRST_NAME_SETTER;
            default:
                throw new DDPException("Could not find CopyAnswerTarget definition for:" + targetEnum);
        }
    }

}
