package org.broadinstitute.ddp.route;

import static spark.Spark.halt;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import com.auth0.exception.Auth0Exception;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpStatus;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.ActivityInstanceDao;
import org.broadinstitute.ddp.db.dao.ClientDao;
import org.broadinstitute.ddp.db.dao.DataExportDao;
import org.broadinstitute.ddp.db.dao.EventDao;
import org.broadinstitute.ddp.db.dao.JdbiCountry;
import org.broadinstitute.ddp.db.dao.JdbiLanguageCode;
import org.broadinstitute.ddp.db.dao.JdbiMailingList;
import org.broadinstitute.ddp.db.dao.JdbiProfile;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.JdbiUserStudyEnrollment;
import org.broadinstitute.ddp.db.dao.QueuedEventDao;
import org.broadinstitute.ddp.db.dao.StudyGovernanceDao;
import org.broadinstitute.ddp.db.dao.UserDao;
import org.broadinstitute.ddp.db.dao.UserGovernanceDao;
import org.broadinstitute.ddp.db.dto.EventConfigurationDto;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.db.dto.UserProfileDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.json.LocalRegistrationResponse;
import org.broadinstitute.ddp.json.UserRegistrationPayload;
import org.broadinstitute.ddp.json.UserRegistrationResponse;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.model.activity.types.EventActionType;
import org.broadinstitute.ddp.model.activity.types.EventTriggerType;
import org.broadinstitute.ddp.model.governance.Governance;
import org.broadinstitute.ddp.model.governance.GovernancePolicy;
import org.broadinstitute.ddp.model.user.EnrollmentStatusType;
import org.broadinstitute.ddp.model.user.User;
import org.broadinstitute.ddp.pex.PexInterpreter;
import org.broadinstitute.ddp.security.StudyClientConfiguration;
import org.broadinstitute.ddp.util.Auth0MgmtTokenHelper;
import org.broadinstitute.ddp.util.Auth0Util;
import org.broadinstitute.ddp.util.ConfigManager;
import org.broadinstitute.ddp.util.ResponseUtil;
import org.broadinstitute.ddp.util.ValidatedJsonInputRoute;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

/**
 * Creates a new user. This route is expected to be locked
 * down via Auth0 IP ranges and shared secret header.
 * Irrespective of whether user exists or is new, the corresponding
 * ddp user id is returned so that auth0 can add it
 * as the {@link org.broadinstitute.ddp.constants.Auth0Constants#DDP_USER_ID_CLAIM}.
 */
public class UserRegistrationRoute extends ValidatedJsonInputRoute<UserRegistrationPayload> {

    private static final Logger LOG = LoggerFactory.getLogger(UserRegistrationRoute.class);

    private static final String EN_LANGUAGE_CODE = "en";
    private static final String MODE_LOGIN = "login";

    private final PexInterpreter interpreter;

    public UserRegistrationRoute(PexInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    @Override
    protected int getValidationErrorStatus() {
        return HttpStatus.SC_BAD_REQUEST;
    }

    // todo arz write route filter that checks secret header
    @Override
    public Object handle(Request request, Response response, UserRegistrationPayload payload) throws Exception {
        checkRequiredPayloadProperties(response, payload);

        var doLocalRegistration = payload.isLocalRegistration();
        String auth0ClientId = payload.getAuth0ClientId();
        String studyGuid = payload.getStudyGuid();

        LOG.info("Attempting registration with client {} and study {}", auth0ClientId, studyGuid);

        return TransactionWrapper.withTxn(handle -> {
            String auth0UserId = payload.getAuth0UserId();
            String auth0Domain = payload.getAuth0Domain();
            if (doLocalRegistration && StringUtils.isBlank(auth0Domain)) {
                auth0Domain = ConfigManager.getInstance().getConfig().getConfig(ConfigFile.AUTH0).getString(ConfigFile.DOMAIN);
                LOG.info("Using auth0 domain {} for local registration", auth0Domain);
            }

            StudyDto study = handle.attach(JdbiUmbrellaStudy.class).findByDomainAndStudyGuid(auth0Domain, studyGuid);
            if (study == null) {
                ApiError err = new ApiError(ErrorCodes.STUDY_NOT_FOUND, "Could not find study with guid " + studyGuid);
                LOG.warn(err.getMessage());
                throw ResponseUtil.haltError(response, HttpStatus.SC_NOT_FOUND, err);
            }

            StudyClientConfiguration clientConfig = handle.attach(ClientDao.class).getConfiguration(auth0ClientId, auth0Domain);
            if (clientConfig == null) {
                LOG.warn("Attempted to register user {} using Auth0 client {} that is revoked or not found", auth0UserId, auth0ClientId);
                throw halt(HttpStatus.SC_UNAUTHORIZED);
            }

            Auth0Util auth0Util = new Auth0Util(auth0Domain);
            Auth0MgmtTokenHelper mgmtTokenHelper = Auth0Util.getManagementTokenHelperForDomain(handle, auth0Domain);

            String auth0ClientSecret = clientConfig.getAuth0SigningSecret();
            Auth0Util.RefreshTokenResponse auth0RefreshResponse = null;
            if (doLocalRegistration) {
                auth0RefreshResponse = auth0Util.getRefreshTokenFromCode(payload.getAuth0Code(), auth0ClientId,
                        auth0ClientSecret, payload.getRedirectUri());
                auth0UserId = Auth0Util.getVerifiedAuth0UserId(auth0RefreshResponse.getIdToken(), auth0Domain);
                LOG.info("Successfully exchanged auth0 code for auth0UserId {} for local registration", auth0UserId);
            }

            String ddpUserGuid;
            User operatorUser = handle.attach(UserDao.class).findUserByAuth0UserId(auth0UserId, study.getAuth0TenantId()).orElse(null);
            if (operatorUser == null) {
                LOG.info("Attempting to register new user {} with client {} and study {}", auth0UserId, auth0ClientId, studyGuid);
                operatorUser = registerUser(response, payload, handle, auth0Domain, auth0ClientId, auth0UserId);

                GovernancePolicy policy = handle.attach(StudyGovernanceDao.class).findPolicyByStudyGuid(studyGuid).orElse(null);
                User studyUser;
                if (policy == null) {
                    LOG.info("No study governance policy found, continuing with operator user as the study user");
                    studyUser = operatorUser;
                } else {
                    studyUser = handleGovernancePolicy(response, payload, handle, policy, operatorUser, clientConfig);
                }

                registerUserWithStudy(handle, study, studyUser);
                handle.attach(DataExportDao.class).queueDataSync(studyUser.getId());

                unregisterEmailFromStudyMailingList(handle, study, operatorUser, auth0Util, mgmtTokenHelper);
                queueUserRegisteredEvents(handle, study, operatorUser);

                ddpUserGuid = operatorUser.getGuid();
            } else {
                LOG.info("Attempting to register existing user {} with client {} and study {}", auth0UserId, auth0ClientId, studyGuid);
                ddpUserGuid = handleExistingUser(response, payload, handle, study, operatorUser, auth0Util, mgmtTokenHelper);
            }

            if (doLocalRegistration) {
                return saveDDPGuidInAuth0Metadata(mgmtTokenHelper, ddpUserGuid, auth0UserId,
                        auth0ClientId, auth0ClientSecret, auth0RefreshResponse.getRefreshToken());
            } else {
                return new UserRegistrationResponse(ddpUserGuid);
            }
        });
    }

    private void checkRequiredPayloadProperties(Response response, UserRegistrationPayload payload) {
        StringBuilder sb = new StringBuilder();

        if (!payload.isLocalRegistration()) {
            if (StringUtils.isBlank(payload.getAuth0Domain())) {
                sb.append("Property 'auth0Domain' is required\n");
            }
            if (StringUtils.isBlank(payload.getAuth0UserId())) {
                sb.append("Property 'auth0UserId' is required\n");
            }
        }

        String msg = sb.toString().trim();
        if (StringUtils.isNotBlank(msg)) {
            ApiError err = new ApiError(ErrorCodes.BAD_PAYLOAD, msg);
            LOG.warn("Missing properties in payload: {}", err.getMessage());
            throw ResponseUtil.haltError(response, HttpStatus.SC_BAD_REQUEST, err);
        }
    }

    private String handleExistingUser(Response response, UserRegistrationPayload payload, Handle handle, StudyDto study, User user,
                                      Auth0Util auth0Util, Auth0MgmtTokenHelper mgmtTokenHelper) {
        String tempUserGuid = payload.getTempUserGuid();
        if (tempUserGuid != null) {
            String msg = String.format("Using existing user to upgrade temporary user with guid '%s' is not supported", tempUserGuid);
            LOG.warn(msg);
            throw ResponseUtil.haltError(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, new ApiError(ErrorCodes.NOT_SUPPORTED, msg));
        }

        EnrollmentStatusType status = handle.attach(JdbiUserStudyEnrollment.class)
                .getEnrollmentStatusByUserAndStudyGuids(user.getGuid(), study.getGuid())
                .orElse(null);

        if (status == null) {
            // Find if user is a proxy of any other user in the study, whether active or not.
            long numGovernances = handle.attach(UserGovernanceDao.class)
                    .findGovernancesByProxyAndStudyGuids(user.getGuid(), study.getGuid())
                    .count();
            if (numGovernances > 0) {
                LOG.info("Existing user {} is a proxy of {} users in study {}", user.getGuid(), numGovernances, study.getGuid());
                return user.getGuid();
            } else if (MODE_LOGIN.equals(payload.getMode())) {
                String msg = String.format("User needs to register with study '%s' before logging in", study.getGuid());
                LOG.warn(msg);
                throw ResponseUtil.haltError(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, new ApiError(ErrorCodes.SIGNUP_REQUIRED, msg));
            } else {
                registerUserWithStudy(handle, study, user);
                handle.attach(DataExportDao.class).queueDataSync(user.getId());
                unregisterEmailFromStudyMailingList(handle, study, user, auth0Util, mgmtTokenHelper);
                queueUserRegisteredEvents(handle, study, user);
                return user.getGuid();
            }
        } else {
            LOG.info("Existing user {} is in study {} with status {}", user.getGuid(), study.getGuid(), status);
            return user.getGuid();
        }
    }

    private User registerUser(Response response, UserRegistrationPayload payload, Handle handle,
                              String auth0Domain, String auth0ClientId, String auth0UserId) {
        User user;
        UserDao userDao = handle.attach(UserDao.class);
        if (payload.getTempUserGuid() != null) {
            User tempUser = validateTemporaryUser(response, userDao, payload.getTempUserGuid());
            user = upgradeTemporaryUser(response, userDao, tempUser, auth0UserId);
        } else {
            user = userDao.createUser(auth0Domain, auth0ClientId, auth0UserId);
            LOG.info("Registered user {} with client {}", auth0UserId, auth0ClientId);
        }
        initProfilePreferredLanguage(handle, user, payload.getAuth0ClientCountryCode());
        return user;
    }

    private User handleGovernancePolicy(Response response, UserRegistrationPayload payload, Handle handle,
                                        GovernancePolicy policy, User operatorUser, StudyClientConfiguration clientConfig) {
        boolean shouldCreateGoverned;
        try {
            String expression = policy.getShouldCreateGovernedUserExpr().getText();
            shouldCreateGoverned = interpreter.eval(expression, handle, operatorUser.getGuid(), null);
        } catch (Exception e) {
            String msg = "Error while evaluating study governance policy for study " + policy.getStudyGuid();
            LOG.warn(msg, e);
            throw ResponseUtil.haltError(response, HttpStatus.SC_INTERNAL_SERVER_ERROR, new ApiError(ErrorCodes.SERVER_ERROR, msg));
        }
        if (!shouldCreateGoverned) {
            LOG.info("Governance policy evaluated and no governed user will be created");
            return operatorUser;
        }

        UserGovernanceDao userGovernanceDao = handle.attach(UserGovernanceDao.class);
        Governance gov = userGovernanceDao.createGovernedUserWithGuidAlias(clientConfig.getClientId(), operatorUser.getId());
        userGovernanceDao.grantGovernedStudy(gov.getId(), policy.getStudyId());
        LOG.info("Created governed user with guid {} and granted access to study {} for proxy {}",
                gov.getGovernedUserGuid(), policy.getStudyGuid(), operatorUser.getGuid());

        User governedUser = handle.attach(UserDao.class).findUserById(gov.getGovernedUserId())
                .orElseThrow(() -> new DDPException("Could not find governed user with id " + gov.getGovernedUserId()));
        initProfilePreferredLanguage(handle, governedUser, payload.getAuth0ClientCountryCode());

        int numInstancesReassigned = handle.attach(ActivityInstanceDao.class)
                .reassignInstancesInStudy(policy.getStudyId(), operatorUser.getId(), gov.getGovernedUserId());
        LOG.info("Re-assigned {} activity instances in study {} from operator {} to governed user {}",
                numInstancesReassigned, policy.getStudyGuid(), operatorUser.getGuid(), gov.getGovernedUserGuid());

        return governedUser;
    }

    private void registerUserWithStudy(Handle handle, StudyDto study, User user) {
        JdbiUserStudyEnrollment jdbiEnrollment = handle.attach(JdbiUserStudyEnrollment.class);
        EnrollmentStatusType status = jdbiEnrollment
                .getEnrollmentStatusByUserAndStudyGuids(user.getGuid(), study.getGuid())
                .orElse(null);
        if (status == null) {
            EnrollmentStatusType initialStatus = EnrollmentStatusType.REGISTERED;
            jdbiEnrollment.changeUserStudyEnrollmentStatus(user.getGuid(), study.getGuid(), initialStatus);
            LOG.info("Registered user {} with status {} in study {}", user.getGuid(), initialStatus, study.getGuid());
        } else {
            LOG.warn("User {} is already registered in study {} with status {}", user.getGuid(), study.getGuid(), status);
        }
    }

    private void unregisterEmailFromStudyMailingList(Handle handle, StudyDto study, User user,
                                                     Auth0Util auth0Util, Auth0MgmtTokenHelper mgmtTokenHelper) {
        String userEmail = null;
        try {
            userEmail = auth0Util.getAuth0User(user.getAuth0UserId(), mgmtTokenHelper.getManagementApiToken()).getEmail();
        } catch (Auth0Exception e) {
            LOG.error("Auth0 request to retrieve auth0 user {} failed", user.getAuth0UserId(), e);
        }
        if (StringUtils.isNotBlank(userEmail)) {
            int numDeleted = handle.attach(JdbiMailingList.class).deleteByEmailAndStudyId(userEmail, study.getId());
            if (numDeleted == 1) {
                LOG.info("Removed user {} from study {} mailing list", user.getAuth0UserId(), study.getGuid());
            } else if (numDeleted > 1) {
                LOG.warn("Removed {} mailing list entries for user {} and study {}", numDeleted, user.getAuth0UserId(), study.getGuid());
            }
        } else {
            LOG.error("No email for user {} to remove them from mailing list of study {}", user.getAuth0UserId(), study.getGuid());
        }
    }

    /**
     * Saves the user's ddp guid in auth0's user app metadata
     * so that different clients can keep track of different ddp
     * user ids for the same auth0 user id.  Returns the object
     * that should be sent to the client.
     */
    private LocalRegistrationResponse saveDDPGuidInAuth0Metadata(Auth0MgmtTokenHelper mgmtTokenHelper, String ddpUserGuid,
                                                                 String auth0UserId, String auth0ClientId,
                                                                 String auth0ClientSecret, String refreshToken) {
        // set the ddp user guid in the user's app metadata, keyed by client id so that
        // different deployments can maintain different generated guids
        LOG.info("Setting auth0 user's metadata so that auth0 user {} has ddp user guid {} for client {}",
                auth0UserId, ddpUserGuid, auth0ClientId);

        Auth0Util auth0Util = new Auth0Util(mgmtTokenHelper.getDomain());

        auth0Util.setDDPUserGuidForAuth0User(ddpUserGuid, auth0UserId, auth0ClientId,
                                             mgmtTokenHelper.getManagementApiToken());

        return new LocalRegistrationResponse(auth0Util.refreshToken(auth0ClientId, auth0ClientSecret, refreshToken));
    }

    private User validateTemporaryUser(Response response, UserDao userDao, String tempUserGuid) {
        User tempUser = userDao.findUserByGuid(tempUserGuid).orElse(null);

        if (tempUser == null) {
            String msg = String.format("Could not find temporary user with guid '%s'", tempUserGuid);
            LOG.warn(msg);
            throw ResponseUtil.haltError(response, HttpStatus.SC_NOT_FOUND, new ApiError(ErrorCodes.USER_NOT_FOUND, msg));
        } else if (!tempUser.isTemporary()) {
            String msg = String.format("User with guid '%s' is not a temporary user", tempUserGuid);
            LOG.warn(msg);
            throw ResponseUtil.haltError(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, new ApiError(ErrorCodes.NOT_SUPPORTED, msg));
        } else if (tempUser.isExpired()) {
            String msg = String.format("Temporary user with guid '%s' has already expired", tempUserGuid);
            LOG.warn(msg);
            throw ResponseUtil.haltError(response, HttpStatus.SC_UNPROCESSABLE_ENTITY, new ApiError(ErrorCodes.EXPIRED, msg));
        }

        return tempUser;
    }

    private User upgradeTemporaryUser(Response response, UserDao userDao, User tempUser, String auth0UserId) {
        String tempUserGuid = tempUser.getGuid();
        try {
            userDao.upgradeUserToPermanentById(tempUser.getId(), auth0UserId);
            LOG.info("Upgraded temporary user with guid '{}'", tempUserGuid);
        } catch (Exception e) {
            String msg = String.format("Error while upgrading temporary user with guid '%s'", tempUserGuid);
            LOG.warn(msg, e);
            throw ResponseUtil.haltError(response, HttpStatus.SC_INTERNAL_SERVER_ERROR, new ApiError(ErrorCodes.SERVER_ERROR, msg));
        }
        return userDao.findUserByGuid(tempUserGuid).orElseThrow(() -> new DDPException("Could not find user with guid " + tempUserGuid));
    }

    private void initProfilePreferredLanguage(Handle handle, User user, String auth0ClientCountryCode) {
        Long languageId = handle.attach(JdbiCountry.class).getPrimaryLanguageIdByCountryCode(auth0ClientCountryCode);
        if (languageId == null) {
            languageId = handle.attach(JdbiLanguageCode.class).getLanguageCodeId(EN_LANGUAGE_CODE);
        }

        JdbiProfile jdbiProfile = handle.attach(JdbiProfile.class);
        UserProfileDto profile = jdbiProfile.getUserProfileByUserId(user.getId());

        if (profile == null) {
            profile = UserProfileDto.withOnlyPreferredLang(user.getId(), languageId);
            int numInserted = jdbiProfile.insert(profile);
            if (numInserted != 1) {
                throw new DDPException(String.format(
                        "Could not initialize user profile for user with guid '%s'",
                        user.getGuid()));
            }
        } else if (profile.getPreferredLanguageId() == null) {
            int numUpdated = jdbiProfile.updatePreferredLangId(user.getId(), languageId);
            if (numUpdated != 1) {
                throw new DDPException(String.format(
                        "Could not update preferred language for user with guid '%s'",
                        user.getGuid()));
            }
        }
    }

    private void queueUserRegisteredEvents(Handle handle, StudyDto studyDto, User user) {
        QueuedEventDao queuedEventDao = handle.attach(QueuedEventDao.class);
        List<EventConfigurationDto> configs = handle.attach(EventDao.class)
                .getActiveDispatchConfigsByStudyIdAndTrigger(studyDto.getId(), EventTriggerType.USER_REGISTERED);

        for (EventConfigurationDto config : configs) {
            if (config.getEventActionType() != EventActionType.NOTIFICATION) {
                LOG.error("Event action type {} (in eventConfigurationId={}) is currently not supported for trigger type {}, skipping",
                        config.getEventActionType(), config.getEventConfigurationId(), EventTriggerType.USER_REGISTERED);
                continue;
            }

            Integer delayBeforePosting = config.getSecondsToWaitBeforePosting();
            if (delayBeforePosting == null) {
                delayBeforePosting = 0;
            }
            long postAfter = Instant.now().getEpochSecond() + delayBeforePosting;

            long queuedEventId = queuedEventDao.insertNotification(
                    config.getEventConfigurationId(),
                    postAfter,
                    user.getId(),
                    user.getId(),
                    Collections.emptyMap());

            LOG.info("Queued notification event with id={} for eventConfigurationId={} postAfter={} userGuid={}",
                    queuedEventId, config.getEventConfigurationId(), postAfter, user.getGuid());
        }
    }
}
