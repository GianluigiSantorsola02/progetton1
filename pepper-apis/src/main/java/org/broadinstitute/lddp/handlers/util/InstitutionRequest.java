package org.broadinstitute.lddp.handlers.util;

import lombok.NonNull;

import java.util.ArrayList;
import java.util.Comparator;

public class InstitutionRequest
{
    public InstitutionRequest() {
    }

    private long id; //submissionId of survey
    private String participantId; //UUID (alt pid)
    private String lastUpdated; //last updated date for institution survey
    private ArrayList<Institution> institutions; //institutions

    public InstitutionRequest(long id, String participantId, ArrayList<Institution> institution, String lastUpdated) {
        this.id = id;
        this.participantId = participantId;
        this.institutions = institution;
        this.lastUpdated = lastUpdated;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getParticipantId() {
        return participantId;
    }

    public void setParticipantId(String participantId) {
        this.participantId = participantId;
    }

    public String getLastUpdated() { return this.lastUpdated;}

    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated;}

    public ArrayList<Institution> getInstitutions() {
        return institutions;
    }

    public void setInstitutions(ArrayList<Institution> institutions) {
        this.institutions = institutions;
    }

    static public class InstitutionsComp implements Comparator<InstitutionRequest> {
        @Override
        public int compare(@NonNull InstitutionRequest request1, @NonNull InstitutionRequest request2) {
            if (request1.getId() > request2.getId()) {
                return 1;
            } else if (request1.getId() < request2.getId()) {
                return -1;
            } else {
                return 0;
            }
        }
    }
}
