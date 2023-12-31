package org.broadinstitute.ddp.housekeeping.schedule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.typesafe.config.Config;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.DataExportDao;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.JdbiUser;
import org.broadinstitute.ddp.db.dao.JdbiUserStudyEnrollment;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.db.dto.UserDto;
import org.broadinstitute.ddp.export.DataExporter;
import org.broadinstitute.ddp.export.DataSyncRequest;
import org.jdbi.v3.core.Handle;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This manages a queue of requests, so only one instance of this job should run at a time.
 */
@DisallowConcurrentExecution
public class DataSyncJob implements Job {

    private static final Logger LOG = LoggerFactory.getLogger(DataSyncJob.class);
    private static final String DATA_EXPORTER = "exporter";

    public static JobKey getKey() {
        return Keys.EsExport.SyncJob;
    }

    public static void register(Scheduler scheduler, Config cfg) throws SchedulerException {
        boolean enabled = cfg.getBoolean(ConfigFile.Elasticsearch.SYNC_ENABLED);
        if (!enabled) {
            LOG.warn("Job {} is disabled, no data will be synced to elasticsearch", getKey());
            return;
        }

        JobDataMap map = new JobDataMap();
        map.put(DATA_EXPORTER, new DataExporter(cfg));

        JobDetail job = JobBuilder.newJob(DataSyncJob.class)
                .withIdentity(getKey())
                .usingJobData(map)
                .requestRecovery(false)
                .storeDurably(true)
                .build();
        scheduler.addJob(job, true);
        LOG.info("Added job {} to scheduler", getKey());

        int intervalSecs = cfg.getInt(ConfigFile.Elasticsearch.SYNC_INTERVAL_SECS);
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(Keys.EsExport.SyncTrigger)
                .forJob(getKey())
                .withSchedule(SimpleScheduleBuilder
                        .repeatSecondlyForever(intervalSecs))
                .startNow()
                .build();

        scheduler.scheduleJob(trigger);
        LOG.info("Added trigger {} for job {} with interval {} seconds", trigger.getKey(), getKey(), intervalSecs);
    }

    @Override
    public void execute(JobExecutionContext ctx) throws JobExecutionException {
        try {
            boolean esJobCurrentlyRunning = ctx.getScheduler()
                    .getCurrentlyExecutingJobs().stream()
                    .anyMatch(jctx -> jctx.getJobDetail().getKey().equals(StudyExportToESJob.getKey()));
            if (esJobCurrentlyRunning) {
                LOG.warn("Regular elasticsearch export job currently running, skipping sync job");
                return;
            }

            JobDataMap map = ctx.getMergedJobDataMap();
            DataExporter exporter = (DataExporter) map.get(DATA_EXPORTER);
            TransactionWrapper.useTxn(TransactionWrapper.DB.APIS, handle -> run(handle, exporter));
        } catch (Exception e) {
            throw new JobExecutionException(e, false);
        }
    }

    private void run(Handle handle, DataExporter exporter) {
        List<DataSyncRequest> requests = handle.attach(DataExportDao.class).findLatestDataSyncRequests();
        if (requests.isEmpty()) {
            return;
        }

        long start = System.currentTimeMillis();

        // Deduplicate the requests using sets.
        Map<Long, Set<Long>> studyUsers = new HashMap<>();
        Set<Long> userIdsToQueryStudies = new HashSet<>();
        Set<Long> usersToRefreshEmails = new HashSet<>();

        for (DataSyncRequest request : requests) {
            if (request.isRefreshUserEmail()) {
                usersToRefreshEmails.add(request.getUserId());
            }
            if (request.getStudyId() == null) {
                userIdsToQueryStudies.add(request.getUserId());
            } else {
                studyUsers.computeIfAbsent(request.getStudyId(), id -> new HashSet<>()).add(request.getUserId());
            }
        }

        if (!userIdsToQueryStudies.isEmpty()) {
            handle.attach(JdbiUserStudyEnrollment.class)
                    .findAllLatestByUserIds(userIdsToQueryStudies)
                    .forEach(status -> studyUsers.computeIfAbsent(status.getStudyId(), id -> new HashSet<>()).add(status.getUserId()));
        }

        if (!usersToRefreshEmails.isEmpty()) {
            Set<String> auth0UserIds = handle.attach(JdbiUser.class).findByUserIds(new ArrayList<>(usersToRefreshEmails))
                    .stream().map(UserDto::getAuth0UserId).collect(Collectors.toSet());
            DataExporter.evictCachedAuth0Emails(auth0UserIds);
        }

        Set<Long> distinctUsers = new HashSet<>();
        for (Map.Entry<Long, Set<Long>> entry : studyUsers.entrySet()) {
            Long studyId = entry.getKey();
            Set<Long> userIds = entry.getValue();
            StudyDto studyDto = handle.attach(JdbiUmbrellaStudy.class).findById(studyId);
            if (studyDto.isDataExportEnabled()) {
                LOG.info("Syncing data for study {}", studyDto.getGuid());
                exporter.exportParticipantsToElasticsearchByIds(handle, studyDto, userIds, true);
                distinctUsers.addAll(userIds);
            } else {
                LOG.warn("Study {} does not have data export enabled, skipping data sync", studyDto.getGuid());
            }
        }

        DataSyncRequest latestRequest = requests.get(0);    // Already sorted in descending order.
        handle.attach(DataExportDao.class).deleteDataSyncRequestsAtOrOlderThan(latestRequest.getId());

        long elapsed = System.currentTimeMillis() - start;
        LOG.info("Finished job {}, took {} ms, processed {} requests, synced {} users",
                getKey(), elapsed, requests.size(), distinctUsers.size());
    }
}
