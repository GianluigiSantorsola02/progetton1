package org.broadinstitute.ddp.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.auth0.exception.Auth0Exception;
import org.broadinstitute.ddp.TxnAwareBaseTest;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.util.Auth0MgmtTokenHelper;
import org.broadinstitute.ddp.util.Auth0Util;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Ignore
public class AngioAuth0PasswordResetEmailScript extends TxnAwareBaseTest {
    private static final Logger LOG = LoggerFactory.getLogger(AngioAuth0PasswordResetEmailScript.class);
    private static final String STUDY_GUID = "ANGIO";
    //private static final String STUDY_GUID = "47D94XZTP3";
    private static final String FROM_NAME = "Andrew Zimmer";
    private static final String FROM_EMAIL = "info@ascproject.org";
    private static final String MESSAGE_SUBJECT = "Angio";
    private static final String SENDGRID_TEMPLATE_ID = "7e8c35c8-d244-4166-87b4-a419c7b7c35b";
    private static final String BASE_WEBPAGE_URL = "https://pepper-angio.datadonationplatform.org/password-reset-done";
    private Set<String> userEmailBlackList;

    private String auth0Token;
    private Auth0MgmtTokenHelper auth0MgmtTokenHelper;

    @Before
    public void setup() {
        TransactionWrapper.useTxn(handle -> {
            auth0MgmtTokenHelper = Auth0Util.getManagementTokenHelperForStudy(handle, STUDY_GUID);
            auth0Token = auth0MgmtTokenHelper.getManagementApiToken();
        });
        initializeEmailBlackList();

    }

    private void initializeEmailBlackList() {
        this.userEmailBlackList = new HashSet<>();
    }


    @Ignore
    @Test
    public void sendPasswordResetsForHardcodedUsers() throws Exception {
        StudyPasswordResetEmailGenerator emailGenerator = new StudyPasswordResetEmailGenerator();
        List<String> recipients = new ArrayList<>();
        recipients.add("andrew+1548249683741@broadinstitute.org");
        List<ProfileWithEmail> recipientProfiles = Collections.emptyList();
        // manually add email addresses here

        try {
            recipientProfiles = TransactionWrapper.withTxn(handle -> {
                String auth0Domain = cfg.getConfig(ConfigFile.AUTH0).getString(ConfigFile.DOMAIN);
                return emailGenerator.getProfileWithEmailForEmailAddresses(handle, recipients,
                        auth0Domain, auth0MgmtTokenHelper);
            });
        } catch (Auth0Exception e) {
            LOG.error("Error sending email", e);
        }

        try {
            emailGenerator.sendPasswordResetEmails(STUDY_GUID, recipientProfiles, FROM_NAME, FROM_EMAIL, MESSAGE_SUBJECT, BASE_WEBPAGE_URL,
                    SENDGRID_TEMPLATE_ID, auth0MgmtTokenHelper.getDomain(), auth0Token);
        } catch (DDPException e) {
            LOG.error("Exception executing AngioAuth0PasswordResetEmailScript", e);
        }
    }

    //@Test
    public void sendPasswordResetEmailsToEveryone() {
        StudyPasswordResetEmailGenerator emailGenerator = new StudyPasswordResetEmailGenerator();
        try {
            emailGenerator.sendPasswordResetEmails(STUDY_GUID, userEmailBlackList, FROM_NAME, FROM_EMAIL, MESSAGE_SUBJECT, BASE_WEBPAGE_URL,
                    SENDGRID_TEMPLATE_ID, auth0MgmtTokenHelper.getDomain(), auth0Token);
        } catch (DDPException e) {
            LOG.error("Exception executing AngioAuth0PasswordResetEmailScript", e);
        }
    }


}
