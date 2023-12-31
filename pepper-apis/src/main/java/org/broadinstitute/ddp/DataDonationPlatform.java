package org.broadinstitute.ddp;

import static com.google.common.net.HttpHeaders.X_FORWARDED_FOR;
import static spark.Spark.afterAfter;
import static spark.Spark.awaitInitialization;
import static spark.Spark.before;
import static spark.Spark.delete;
import static spark.Spark.internalServerError;
import static spark.Spark.notFound;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.stop;
import static spark.Spark.threadPool;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.http.entity.ContentType;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.constants.RouteConstants.API;
import org.broadinstitute.ddp.content.I18nContentRenderer;
import org.broadinstitute.ddp.db.ActivityInstanceDao;
import org.broadinstitute.ddp.db.AnswerDao;
import org.broadinstitute.ddp.db.CancerStore;
import org.broadinstitute.ddp.db.ConsentElectionDao;
import org.broadinstitute.ddp.db.DBUtils;
import org.broadinstitute.ddp.db.FormInstanceDao;
import org.broadinstitute.ddp.db.SectionBlockDao;
import org.broadinstitute.ddp.db.StudyActivityDao;
import org.broadinstitute.ddp.db.StudyAdminDao;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.UserDao;
import org.broadinstitute.ddp.db.UserDaoFactory;
import org.broadinstitute.ddp.filter.AddDDPAuthLoggingFilter;
import org.broadinstitute.ddp.filter.DsmAuthFilter;
import org.broadinstitute.ddp.filter.ExcludePathFilterWrapper;
import org.broadinstitute.ddp.filter.HttpHeaderMDCFilter;
import org.broadinstitute.ddp.filter.MDCAttributeRemovalFilter;
import org.broadinstitute.ddp.filter.MDCLogBreadCrumbFilter;
import org.broadinstitute.ddp.filter.TokenConverterFilter;
import org.broadinstitute.ddp.filter.UserAuthCheckFilter;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.model.dsm.DrugStore;
import org.broadinstitute.ddp.monitoring.PointsReducerFactory;
import org.broadinstitute.ddp.monitoring.StackdriverCustomMetric;
import org.broadinstitute.ddp.monitoring.StackdriverMetricsTracker;
import org.broadinstitute.ddp.pex.PexInterpreter;
import org.broadinstitute.ddp.pex.TreeWalkInterpreter;
import org.broadinstitute.ddp.route.AddProfileRoute;
import org.broadinstitute.ddp.route.CheckIrbPasswordRoute;
import org.broadinstitute.ddp.route.CreateActivityInstanceRoute;
import org.broadinstitute.ddp.route.CreateMailAddressRoute;
import org.broadinstitute.ddp.route.CreateTemporaryUserRoute;
import org.broadinstitute.ddp.route.DeleteMailAddressRoute;
import org.broadinstitute.ddp.route.DeleteMedicalProviderRoute;
import org.broadinstitute.ddp.route.DeleteTempMailingAddressRoute;
import org.broadinstitute.ddp.route.DsmExitUserRoute;
import org.broadinstitute.ddp.route.DsmTriggerOnDemandActivityRoute;
import org.broadinstitute.ddp.route.ErrorRoute;
import org.broadinstitute.ddp.route.ExportStudyRoute;
import org.broadinstitute.ddp.route.GetActivityInstanceRoute;
import org.broadinstitute.ddp.route.GetActivityInstanceStatusTypeListRoute;
import org.broadinstitute.ddp.route.GetAdminStudiesRoute;
import org.broadinstitute.ddp.route.GetConsentSummariesRoute;
import org.broadinstitute.ddp.route.GetConsentSummaryRoute;
import org.broadinstitute.ddp.route.GetCountryAddressInfoRoute;
import org.broadinstitute.ddp.route.GetCountryAddressInfoSummariesRoute;
import org.broadinstitute.ddp.route.GetDeployedAppVersionRoute;
import org.broadinstitute.ddp.route.GetDsmConsentPdfRoute;
import org.broadinstitute.ddp.route.GetDsmDrugSuggestionsRoute;
import org.broadinstitute.ddp.route.GetDsmInstitutionRequestsRoute;
import org.broadinstitute.ddp.route.GetDsmKitRequestsRoute;
import org.broadinstitute.ddp.route.GetDsmMedicalRecordRoute;
import org.broadinstitute.ddp.route.GetDsmOnDemandActivitiesRoute;
import org.broadinstitute.ddp.route.GetDsmParticipantInstitutionsRoute;
import org.broadinstitute.ddp.route.GetDsmParticipantStatusRoute;
import org.broadinstitute.ddp.route.GetDsmReleasePdfRoute;
import org.broadinstitute.ddp.route.GetDsmStudyParticipant;
import org.broadinstitute.ddp.route.GetDsmTriggeredInstancesRoute;
import org.broadinstitute.ddp.route.GetGovernedStudyParticipantsRoute;
import org.broadinstitute.ddp.route.GetInstitutionSuggestionsRoute;
import org.broadinstitute.ddp.route.GetMailAddressRoute;
import org.broadinstitute.ddp.route.GetMailingListRoute;
import org.broadinstitute.ddp.route.GetMedicalProviderListRoute;
import org.broadinstitute.ddp.route.GetParticipantDefaultMailAddressRoute;
import org.broadinstitute.ddp.route.GetParticipantInfoRoute;
import org.broadinstitute.ddp.route.GetParticipantMailAddressRoute;
import org.broadinstitute.ddp.route.GetPrequalifierInstanceRoute;
import org.broadinstitute.ddp.route.GetProfileRoute;
import org.broadinstitute.ddp.route.GetStudiesRoute;
import org.broadinstitute.ddp.route.GetStudyDetailRoute;
import org.broadinstitute.ddp.route.GetStudyPasswordRequirementsRoute;
import org.broadinstitute.ddp.route.GetTempMailingAddressRoute;
import org.broadinstitute.ddp.route.GetUserAnnouncementsRoute;
import org.broadinstitute.ddp.route.GetWorkflowRoute;
import org.broadinstitute.ddp.route.GetWorkspacesRoute;
import org.broadinstitute.ddp.route.HealthCheckRoute;
import org.broadinstitute.ddp.route.JoinMailingListRoute;
import org.broadinstitute.ddp.route.ListCancersRoute;
import org.broadinstitute.ddp.route.PatchFormAnswersRoute;
import org.broadinstitute.ddp.route.PatchMedicalProviderRoute;
import org.broadinstitute.ddp.route.PatchProfileRoute;
import org.broadinstitute.ddp.route.PostMedicalProviderRoute;
import org.broadinstitute.ddp.route.PostPasswordResetRoute;
import org.broadinstitute.ddp.route.PutFormAnswersRoute;
import org.broadinstitute.ddp.route.PutTempMailingAddressRoute;
import org.broadinstitute.ddp.route.SendDsmNotificationRoute;
import org.broadinstitute.ddp.route.SendEmailRoute;
import org.broadinstitute.ddp.route.SendExitNotificationRoute;
import org.broadinstitute.ddp.route.SetParticipantDefaultMailAddressRoute;
import org.broadinstitute.ddp.route.UpdateMailAddressRoute;
import org.broadinstitute.ddp.route.UpdateUserEmailRoute;
import org.broadinstitute.ddp.route.UpdateUserPasswordRoute;
import org.broadinstitute.ddp.route.UserActivityInstanceListRoute;
import org.broadinstitute.ddp.route.UserRegistrationRoute;
import org.broadinstitute.ddp.route.VerifyMailAddressRoute;
import org.broadinstitute.ddp.schedule.DsmCancerLoaderJob;
import org.broadinstitute.ddp.schedule.DsmDrugLoaderJob;
import org.broadinstitute.ddp.schedule.JobScheduler;
import org.broadinstitute.ddp.security.JWTConverter;
import org.broadinstitute.ddp.service.ActivityInstanceService;
import org.broadinstitute.ddp.service.AddressService;
import org.broadinstitute.ddp.service.CancerService;
import org.broadinstitute.ddp.service.ConsentService;
import org.broadinstitute.ddp.service.DsmCancerListService;
import org.broadinstitute.ddp.service.DsmParticipantStatusService;
import org.broadinstitute.ddp.service.FireCloudExportService;
import org.broadinstitute.ddp.service.FormActivityService;
import org.broadinstitute.ddp.service.MedicalRecordService;
import org.broadinstitute.ddp.service.PdfBucketService;
import org.broadinstitute.ddp.service.PdfGenerationService;
import org.broadinstitute.ddp.service.PdfService;
import org.broadinstitute.ddp.service.WorkflowService;
import org.broadinstitute.ddp.transformers.SimpleJsonTransformer;
import org.broadinstitute.ddp.util.ConfigManager;
import org.broadinstitute.ddp.util.DsmDrugLoader;
import org.broadinstitute.ddp.util.LiquibaseUtil;
import org.broadinstitute.ddp.util.LogbackConfigurationPrinter;
import org.broadinstitute.ddp.util.RouteUtil;
import org.quartz.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import spark.Filter;
import spark.Request;
import spark.Response;
import spark.ResponseTransformer;
import spark.Route;
import spark.Spark;
import spark.route.HttpMethod;

public class DataDonationPlatform {

    public static final String MDC_STUDY = "Study";
    public static final String MDC_ROUTE_CLASS = "RouteClass";
    private static final Logger LOG = LoggerFactory.getLogger(DataDonationPlatform.class);
    private static final String[] CORS_HTTP_METHODS = new String[] {"GET", "PUT", "POST", "OPTIONS", "PATCH"};
    private static final String[] CORS_HTTP_HEADERS = new String[] {"Content-Type", "Authorization", "X-Requested-With",
            "Content-Length", "Accept", "Origin", ""};
    private static final Map<String, String> pathToClass = new HashMap<>();
    private static Scheduler scheduler = null;

    /**
     * Stop the server using the default wait time.
     */
    public static void shutdown() {
        shutdown(1000);
    }

    /**
     * Stop the Spark server and give it time to close.
     *
     * @param millisecs milliseconds to wait for Spark to close
     */
    public static void shutdown(int millisecs) {
        if (scheduler != null) {
            JobScheduler.shutdownScheduler(scheduler, false);
        }

        stop();
        try {
            LOG.info("Pausing for {}ms for server to stop", millisecs);
            Thread.sleep(millisecs);
        } catch (InterruptedException e) {
            LOG.warn("Wait interrupted", e);
        }

        LOG.info("ddp shutdown complete");
    }

    public static void main(String[] args) {
        try {
            start();
        } catch (Exception e) {
            LOG.error("Could not start ddp", e);
            shutdown();
        }
    }

    private static void start() {
        LogbackConfigurationPrinter.printLoggingConfiguration();
        Config cfg = ConfigManager.getInstance().getConfig();
        boolean doLiquibase = cfg.getBoolean(ConfigFile.DO_LIQUIBASE);
        int maxConnections = cfg.getInt(ConfigFile.NUM_POOLED_CONNECTIONS);

        String firecloudKeysLocation = System.getProperty(ConfigFile.FIRECLOUD_KEYS_DIR_ENV_VAR);
        if (firecloudKeysLocation == null) {
            LOG.error("System property {} was not set. Exiting program", ConfigFile.FIRECLOUD_KEYS_DIR_ENV_VAR);
            System.exit(-1);
        }
        File firecloudKeysDir = new File(firecloudKeysLocation);
        if (!firecloudKeysDir.exists() || !firecloudKeysDir.isDirectory()) {
            LOG.error("Cannot find directory {} specified in system property {}. Exiting program", firecloudKeysDir,
                    ConfigFile.FIRECLOUD_KEYS_DIR_ENV_VAR);
            System.exit(-1);
        }

        int requestThreadTimeout = cfg.getInt(ConfigFile.THREAD_TIMEOUT);
        String healthcheckPassword = cfg.getString(ConfigFile.HEALTHCHECK_PASSWORD);

        int port = cfg.getInt(ConfigFile.PORT);
        port(port);

        String dbUrl = cfg.getString(ConfigFile.DB_URL);
        LOG.info("Using db {}", dbUrl);

        TransactionWrapper.init(
                new TransactionWrapper.DbConfiguration(TransactionWrapper.DB.APIS, maxConnections, dbUrl));

        threadPool(-1, -1, requestThreadTimeout);
        Config sqlConfig = ConfigFactory.load(ConfigFile.SQL_CONFIG_FILE);
        initSqlCommands(sqlConfig);

        if (doLiquibase) {
            LOG.info("Running liquibase migrations against " + dbUrl);
            LiquibaseUtil.runLiquibase(dbUrl, TransactionWrapper.DB.APIS);
        }

        UserDao userDao = UserDaoFactory.createFromSqlConfig(sqlConfig);

        final AnswerDao answerDao = AnswerDao.fromSqlConfig(sqlConfig);
        SectionBlockDao sectionBlockDao = new SectionBlockDao(new I18nContentRenderer());

        FormInstanceDao formInstanceDao = FormInstanceDao.fromDaoAndConfig(sectionBlockDao, sqlConfig);
        ActivityInstanceDao activityInstanceDao = new ActivityInstanceDao(formInstanceDao);

        PexInterpreter interpreter = new TreeWalkInterpreter();
        final ActivityInstanceService actInstService = new ActivityInstanceService(activityInstanceDao, interpreter);

        final FireCloudExportService fireCloudExportService = FireCloudExportService.fromSqlConfig(sqlConfig);
        final StudyAdminDao studyAdminDao = StudyAdminDao.init(sqlConfig, firecloudKeysDir);

        SimpleJsonTransformer responseSerializer = new SimpleJsonTransformer();

        before("*", new HttpHeaderMDCFilter(X_FORWARDED_FOR));
        before("*", new MDCLogBreadCrumbFilter());

        before("*", new Filter() {
            @Override
            public void handle(Request request, Response response) throws Exception {
                MDC.put(MDC_STUDY, RouteUtil.parseStudyGuid(request.pathInfo()));
            }
        });

        enableCORS("*", String.join(",", CORS_HTTP_METHODS), String.join(",", CORS_HTTP_HEADERS));
        setupCatchAllErrorHandling();

        // before filter converts jwt into DDP_AUTH request attribute
        // we exclude the DSM paths. DSM paths have own separate authentication
        beforeWithExclusion(API.BASE + "/*", new String[] {API.DSM_BASE + "/*", API.CHECK_IRB_PASSWORD},
                new TokenConverterFilter(new JWTConverter(userDao)));
        beforeWithExclusion(API.BASE + "/*", new String[] {API.DSM_BASE + "/*", API.CHECK_IRB_PASSWORD}, new AddDDPAuthLoggingFilter());
        // Internal routes
        get(API.HEALTH_CHECK, new HealthCheckRoute(healthcheckPassword), responseSerializer);
        get(API.DEPLOYED_VERSION, new GetDeployedAppVersionRoute(), responseSerializer);
        get(API.INTERNAL_ERROR, new ErrorRoute(), responseSerializer);

        post(API.REGISTRATION, new UserRegistrationRoute(interpreter), responseSerializer);
        post(API.TEMP_USERS, new CreateTemporaryUserRoute(userDao), responseSerializer);

        // List available studies & get detailed information on a particular study
        get(API.STUDY_ALL, new GetStudiesRoute(), responseSerializer);
        get(API.STUDY_DETAIL, new GetStudyDetailRoute(), responseSerializer);

        get(API.ADDRESS_COUNTRIES, new GetCountryAddressInfoSummariesRoute(), responseSerializer);
        get(API.ADDRESS_COUNTRY_DETAILS, new GetCountryAddressInfoRoute(), responseSerializer);

        // User route filter
        before(API.USER_ALL, new UserAuthCheckFilter()
                .addTempUserWhitelist(HttpMethod.get, API.USER_PROFILE)
                .addTempUserWhitelist(HttpMethod.get, API.USER_STUDY_WORKFLOW)
                .addTempUserWhitelist(HttpMethod.get, API.USER_ACTIVITIES_INSTANCE)
                .addTempUserWhitelist(HttpMethod.patch, API.USER_ACTIVITY_ANSWERS)
                .addTempUserWhitelist(HttpMethod.put, API.USER_ACTIVITY_ANSWERS)
        );

        // Governed participant routes
        get(API.USER_STUDY_PARTICIPANTS, new GetGovernedStudyParticipantsRoute(), responseSerializer);

        // User profile routes
        get(API.USER_PROFILE, new GetProfileRoute(userDao), responseSerializer);
        post(API.USER_PROFILE, new AddProfileRoute(userDao), responseSerializer);
        patch(API.USER_PROFILE, new PatchProfileRoute(userDao), responseSerializer);

        // User mailing address routes
        AddressService addressService = new AddressService(cfg.getString(ConfigFile.EASY_POST_API_KEY),
                cfg.getString(ConfigFile.GEOCODING_API_KEY));

        post(API.PARTICIPANT_ADDRESS, new CreateMailAddressRoute(addressService), responseSerializer);
        get(API.PARTICIPANT_ADDRESS, new GetParticipantMailAddressRoute(addressService), responseSerializer);

        post(API.DEFAULT_PARTICIPANT_ADDRESS,
                new SetParticipantDefaultMailAddressRoute(addressService), responseSerializer);
        get(API.DEFAULT_PARTICIPANT_ADDRESS,
                new GetParticipantDefaultMailAddressRoute(addressService), responseSerializer);

        get(API.ADDRESS, new GetMailAddressRoute(addressService), responseSerializer);
        put(API.ADDRESS, new UpdateMailAddressRoute(addressService), responseSerializer);
        delete(API.ADDRESS, new DeleteMailAddressRoute(addressService));

        get(API.PARTICIPANT_TEMP_ADDRESS, new GetTempMailingAddressRoute(), responseSerializer);
        put(API.PARTICIPANT_TEMP_ADDRESS, new PutTempMailingAddressRoute());
        delete(API.PARTICIPANT_TEMP_ADDRESS, new DeleteTempMailingAddressRoute());

        post(API.ADDRESS_VERIFY, new VerifyMailAddressRoute(addressService), responseSerializer);

        get(API.PARTICIPANTS_INFO_FOR_STUDY, new GetParticipantInfoRoute(), responseSerializer);

        // Workflow routing
        WorkflowService workflowService = new WorkflowService(interpreter);
        get(API.USER_STUDY_WORKFLOW, new GetWorkflowRoute(workflowService), responseSerializer);

        // User study announcements
        get(API.USER_STUDY_ANNOUNCEMENTS, new GetUserAnnouncementsRoute(new I18nContentRenderer()), responseSerializer);

        // User prequalifier instance route
        StudyActivityDao studyActivityDao = new StudyActivityDao();
        get(API.USER_STUDIES_PREQUALIFIER, new GetPrequalifierInstanceRoute(studyActivityDao, activityInstanceDao),
                responseSerializer);

        ConsentService consentService = new ConsentService(interpreter, studyActivityDao, new ConsentElectionDao());
        MedicalRecordService medicalRecordService = new MedicalRecordService(consentService);
        // User consent routes
        get(API.USER_STUDIES_ALL_CONSENTS, new GetConsentSummariesRoute(consentService), responseSerializer);
        get(API.USER_STUDIES_CONSENT, new GetConsentSummaryRoute(consentService), responseSerializer);

        get(API.ACTIVITY_INSTANCE_STATUS_TYPE_LIST, new GetActivityInstanceStatusTypeListRoute(), responseSerializer);

        // jenkins test

        // User activity instance routes
        get(API.USER_ACTIVITIES, new UserActivityInstanceListRoute(activityInstanceDao), responseSerializer);
        post(API.USER_ACTIVITIES, new CreateActivityInstanceRoute(activityInstanceDao), responseSerializer);
        get(API.USER_ACTIVITIES_INSTANCE, new GetActivityInstanceRoute(actInstService, activityInstanceDao), responseSerializer);

        // User activity answers routes
        FormActivityService formService = new FormActivityService(interpreter);
        patch(API.USER_ACTIVITY_ANSWERS,
                new PatchFormAnswersRoute(formService, activityInstanceDao, answerDao),
                responseSerializer);
        put(API.USER_ACTIVITY_ANSWERS, new PutFormAnswersRoute(workflowService, formInstanceDao, interpreter), responseSerializer);

        // Study exit request
        post(API.USER_STUDY_EXIT, new SendExitNotificationRoute());

        // Study admin routes
        before(API.ADMIN_BASE + "/*", new UserAuthCheckFilter());
        get(API.ADMIN_STUDIES, new GetAdminStudiesRoute(studyAdminDao), responseSerializer);
        get(API.ADMIN_WORKSPACES, new GetWorkspacesRoute(fireCloudExportService, studyAdminDao), responseSerializer);
        post(API.EXPORT_STUDY, new ExportStudyRoute(fireCloudExportService, studyAdminDao), responseSerializer);

        Config auth0Config = cfg.getConfig(ConfigFile.AUTH0);
        before(API.DSM_BASE + "/*", new DsmAuthFilter(auth0Config.getString(ConfigFile.AUTH0_DSM_CLIENT_ID),
                auth0Config.getString(ConfigFile.DOMAIN)));

        get(API.DSM_ALL_KIT_REQUESTS, new GetDsmKitRequestsRoute(), responseSerializer);
        get(API.DSM_KIT_REQUESTS_STARTING_AFTER, new GetDsmKitRequestsRoute(), responseSerializer);
        get(API.DSM_STUDY_PARTICIPANT, new GetDsmStudyParticipant(), responseSerializer);
        get(API.DSM_GET_INSTITUTION_REQUESTS, new GetDsmInstitutionRequestsRoute(), responseSerializer);
        get(API.DSM_PARTICIPANT_MEDICAL_INFO, new GetDsmMedicalRecordRoute(medicalRecordService), responseSerializer);
        get(API.DSM_PARTICIPANT_INSTITUTIONS, new GetDsmParticipantInstitutionsRoute(), responseSerializer);

        post(API.DSM_NOTIFICATION, new SendDsmNotificationRoute(), responseSerializer);
        post(API.DSM_TERMINATE_USER, new DsmExitUserRoute(), responseSerializer);

        PdfService pdfService = new PdfService();
        PdfBucketService pdfBucketService = new PdfBucketService(cfg);
        PdfGenerationService pdfGenerationService = new PdfGenerationService();
        get(API.DSM_PARTICIPANT_RELEASE_PDF, new GetDsmReleasePdfRoute(pdfService, pdfBucketService, pdfGenerationService));
        get(API.DSM_PARTICIPANT_CONSENT_PDF, new GetDsmConsentPdfRoute(pdfService, pdfBucketService, pdfGenerationService));

        get(API.DSM_ONDEMAND_ACTIVITIES, new GetDsmOnDemandActivitiesRoute(), responseSerializer);
        get(API.DSM_ONDEMAND_ACTIVITY, new GetDsmTriggeredInstancesRoute(), responseSerializer);
        post(API.DSM_ONDEMAND_ACTIVITY, new DsmTriggerOnDemandActivityRoute(), responseSerializer);

        get(API.AUTOCOMPLETE_INSTITUTION, new GetInstitutionSuggestionsRoute(), responseSerializer);
        get(API.LIST_CANCERS, new ListCancersRoute(new CancerService()), responseSerializer);

        get(API.USER_MEDICAL_PROVIDERS, new GetMedicalProviderListRoute(), responseSerializer);
        post(API.USER_MEDICAL_PROVIDERS, new PostMedicalProviderRoute(), responseSerializer);
        patch(API.USER_MEDICAL_PROVIDER, new PatchMedicalProviderRoute(), responseSerializer);
        delete(API.USER_MEDICAL_PROVIDER, new DeleteMedicalProviderRoute(), responseSerializer);

        post(API.JOIN_MAILING_LIST, new JoinMailingListRoute());

        get(API.GET_STUDY_MAILING_LIST, new GetMailingListRoute(), responseSerializer);

        post(API.CHECK_IRB_PASSWORD, new CheckIrbPasswordRoute(), responseSerializer);

        post(API.SEND_EMAIL, new SendEmailRoute(workflowService), responseSerializer);

        get(API.POST_PASSWORD_RESET, new PostPasswordResetRoute(), responseSerializer);

        get(API.DSM_DRUG_SUGGESTION, new GetDsmDrugSuggestionsRoute(DrugStore.getInstance()), responseSerializer);

        // Routes calling DSM
        URL dsmBaseUrl = null;
        try {
            dsmBaseUrl = new URL(cfg.getString(ConfigFile.DSM_BASE_URL));
        } catch (MalformedURLException e) {
            throw new RuntimeException("Invalid DSM URL {}.  DSM-related routes will fail.", e);
        }
        DsmParticipantStatusService dsmParticipantStatusService = new DsmParticipantStatusService(dsmBaseUrl);
        get(API.PARTICIPANT_STATUS, new GetDsmParticipantStatusRoute(dsmParticipantStatusService), responseSerializer);
        get(API.STUDY_PASSWORD_REQUIREMENTS, new GetStudyPasswordRequirementsRoute(), responseSerializer);

        patch(
                API.UPDATE_USER_PASSWORD,
                new UpdateUserPasswordRoute(),
                responseSerializer
        );
        patch(
                API.UPDATE_USER_EMAIL,
                new UpdateUserEmailRoute(),
                responseSerializer
        );

        Runnable runnable = () -> {
            //load drug list
            DsmDrugLoader drugLoader = new DsmDrugLoader();
            drugLoader.fetchAndLoadDrugs(cfg.getString(ConfigFile.DSM_BASE_URL), cfg.getString(ConfigFile.DSM_JWT_SECRET));

            //load cancer list
            DsmCancerListService service = new DsmCancerListService(cfg.getString(ConfigFile.DSM_BASE_URL));
            List<String> cancerNames = service.fetchCancerList(cfg.getString(ConfigFile.DSM_JWT_SECRET));
            CancerStore.getInstance().populate(cancerNames);
            LOG.info("Loaded {} cancers into pepper.", cancerNames == null ? 0 : cancerNames.size());
        };

        boolean runScheduler = cfg.getBoolean(ConfigFile.RUN_SCHEDULER);
        if (runScheduler) {
            Thread threadDL = new Thread(runnable);
            threadDL.start();

            //setup DDP JobScheduler on server startup
            scheduler = JobScheduler.initializeWith(cfg,
                    DsmDrugLoaderJob::register,
                    DsmCancerLoaderJob::register);
        } else {
            LOG.info("DDP job scheduler is not set to run");
        }

        setupApiActivityFilter();

        afterAfter(new MDCAttributeRemovalFilter(AddDDPAuthLoggingFilter.LOGGING_CLIENTID_PARAM,
                AddDDPAuthLoggingFilter.LOGGING_USERID_PARAM,
                MDC_STUDY,
                MDC_ROUTE_CLASS,
                X_FORWARDED_FOR,
                MDCLogBreadCrumbFilter.LOG_BREADCRUMB));

        awaitInitialization();
        LOG.info("ddp startup complete");
    }

    private static void setupApiActivityFilter() {
        afterAfter((Request request, Response response) -> {
            String endpoint = MDC.get(MDC_ROUTE_CLASS);
            if (endpoint == null) {
                endpoint = "unknown";
            }
            String study = MDC.get(MDC_STUDY);
            String httpMethod = request.requestMethod();
            String participant = MDC.get(AddDDPAuthLoggingFilter.LOGGING_USERID_PARAM);
            String client = MDC.get(AddDDPAuthLoggingFilter.LOGGING_CLIENTID_PARAM);

            new StackdriverMetricsTracker(StackdriverCustomMetric.API_ACTIVITY,
                    study, endpoint, httpMethod, client, participant, response.status(),
                    PointsReducerFactory.buildSumReducer()).addPoint(1, Instant.now().toEpochMilli());
        });
    }

    private static void setupMDC(String path, Route route) {
        pathToClass.put(path, route.getClass().getSimpleName());
        before(path, (request, response) -> MDC.put(MDC_ROUTE_CLASS, pathToClass.get(path)));
    }

    public static void get(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, route);
        Spark.get(path, route, transformer);
    }

    public static void get(String path, Route route) {
        setupMDC(path, route);
        Spark.get(path, route);
    }

    public static void post(String path, Route route) {
        setupMDC(path, route);
        Spark.post(path, route);
    }

    public static void post(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, route);
        Spark.post(path, route, transformer);
    }

    public static void put(String path, Route route) {
        setupMDC(path, route);
        Spark.put(path, route);
    }

    public static void put(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, route);
        Spark.put(path, route, transformer);
    }

    public static void patch(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, route);
        Spark.patch(path, route, transformer);
    }


    private static void setupCatchAllErrorHandling() {
        //JSON for Not Found (code 404) handling
        notFound((request, response) -> {
            LOG.info("[404] Current status: {}", response.status());
            ApiError apiError = new ApiError(ErrorCodes.NOT_FOUND, "This page was not found.");
            response.type(ContentType.APPLICATION_JSON.getMimeType());
            SimpleJsonTransformer jsonTransformer = new SimpleJsonTransformer();
            return jsonTransformer.render(apiError);
        });

        internalServerError((request, response) -> {
            ApiError apiError = new ApiError(ErrorCodes.SERVER_ERROR,
                    "Something unexpected happened.");
            response.type(ContentType.APPLICATION_JSON.getMimeType());
            SimpleJsonTransformer jsonTransformer = new SimpleJsonTransformer();
            return jsonTransformer.render(apiError);
        });
    }

    private static void enableCORS(final String origin, final String methods, final String headers) {
        options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }

            return "OK";
        });

        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", origin);
            response.header("Access-Control-Request-Method", methods);
            response.header("Access-Control-Allow-Headers", headers);
            response.header("Access-Control-Allow-Credentials", "true");
            response.type("application/json");
        });
    }

    private static void initSqlCommands(Config sqlConfig) {
        DBUtils.loadDaoSqlCommands(sqlConfig);
    }

    /**
     * Allow to specify the exclusion of a path from the execution of a filter
     *
     * @param filterPath     the path for which the filter is applicable
     * @param pathsToExclude the paths to exclude the execution of the filter
     * @param filter         the filter
     */
    public static void beforeWithExclusion(String filterPath, String[] pathsToExclude, Filter filter) {
        before(filterPath, new ExcludePathFilterWrapper(filter, Arrays.asList(pathsToExclude)));
    }
}
