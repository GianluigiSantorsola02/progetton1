package org.broadinstitute.ddp.route;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpStatus;
import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.constants.RouteConstants.PathParam;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.JdbiUser;
import org.broadinstitute.ddp.db.dao.JdbiUserStudyEnrollment;
import org.broadinstitute.ddp.db.dao.PdfDao;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.db.dto.UserDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.model.pdf.PdfConfigInfo;
import org.broadinstitute.ddp.model.pdf.PdfConfiguration;
import org.broadinstitute.ddp.model.pdf.PdfVersion;
import org.broadinstitute.ddp.model.user.EnrollmentStatusType;
import org.broadinstitute.ddp.service.PdfBucketService;
import org.broadinstitute.ddp.service.PdfGenerationService;
import org.broadinstitute.ddp.service.PdfService;
import org.broadinstitute.ddp.util.ResponseUtil;
import org.jdbi.v3.core.Handle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Route;

public class GetPdfRoute implements Route {

    private static final Logger LOG = LoggerFactory.getLogger(GetPdfRoute.class);

    private final PdfService pdfService;
    private final PdfBucketService pdfBucketService;
    private final PdfGenerationService pdfGenerationService;

    public GetPdfRoute(PdfService pdfService, PdfBucketService pdfBucketService, PdfGenerationService pdfGenerationService) {
        this.pdfService = pdfService;
        this.pdfBucketService = pdfBucketService;
        this.pdfGenerationService = pdfGenerationService;
    }

    @Override
    public Object handle(Request request, Response response) {
        String studyGuid = request.params(PathParam.STUDY_GUID);
        String participantGuidOrAltPid = request.params(PathParam.USER_GUID);
        String configName = request.params(PathParam.CONFIG_NAME);

        LOG.info("Attempting to fetch {} pdf for study {} and participant {}", configName, studyGuid, participantGuidOrAltPid);

        return TransactionWrapper.withTxn(handle -> {
            StudyDto studyDto = handle.attach(JdbiUmbrellaStudy.class).findByStudyGuid(studyGuid);
            if (studyDto == null) {
                ApiError err = new ApiError(ErrorCodes.STUDY_NOT_FOUND, "Could not find study with guid " + studyGuid);
                LOG.warn(err.getMessage());
                throw ResponseUtil.haltError(response, HttpStatus.SC_NOT_FOUND, err);
            }

            UserDto userDto = handle.attach(JdbiUser.class).findByLegacyAltPidIfNotFoundByUserGuid(participantGuidOrAltPid);
            if (userDto == null) {
                String msg = "Could not find participant with GUID or Legacy AltPid " + participantGuidOrAltPid;
                ApiError err = new ApiError(ErrorCodes.USER_NOT_FOUND, msg);
                LOG.warn(err.getMessage());
                throw ResponseUtil.haltError(response, HttpStatus.SC_NOT_FOUND, err);
            }

            Optional<PdfConfigInfo> pdfConfigInfo = handle.attach(PdfDao.class)
                    .findConfigInfoByStudyGuidAndName(studyGuid, configName);
            if (pdfConfigInfo.isEmpty()) {
                String msg = String.format("Could not find pdf configuration for study with GUID %s and configuration name %s ",
                        studyGuid, configName);
                ApiError err = new ApiError(ErrorCodes.PDF_CONFIG_NAME_NOT_FOUND, msg);
                LOG.warn(err.getMessage());
                return ResponseUtil.haltError(response, HttpStatus.SC_NOT_FOUND, err);
            }
            PdfConfigInfo configInfo = pdfConfigInfo.get();

            haltIfUserNotEnrolled(handle, userDto, studyDto, response);

            try {
                PdfVersion pdfVersion = pdfService.findConfigVersionForUser(
                        handle, configInfo.getId(), userDto.getUserGuid(), studyGuid);

                String umbrellaGuid = handle.attach(JdbiUmbrellaStudy.class).getUmbrellaGuidForStudyGuid(studyGuid);
                String blobName = PdfBucketService.getBlobName(
                        umbrellaGuid,
                        studyGuid,
                        userDto.getUserGuid(),
                        configInfo.getConfigName(),
                        pdfVersion.getVersionTag());

                InputStream pdf = pdfBucketService.getPdfFromBucket(blobName).orElse(null);
                if (pdf == null) {
                    LOG.info("Could not find {} pdf for participant {} using filename {}, generating",
                            configInfo.getConfigName(), userDto.getUserGuid(), blobName);
                    PdfConfiguration pdfConfig = handle.attach(PdfDao.class).findFullConfig(pdfVersion);
                    byte[] pdfBytes = pdfGenerationService.generateFlattenedPdfForConfiguration(
                            pdfConfig,
                            userDto.getUserGuid(),
                            handle);
                    pdf = new ByteArrayInputStream(pdfBytes);
                    pdfBucketService.sendPdfToBucket(blobName, new ByteArrayInputStream(pdfBytes));
                    LOG.info("Uploaded pdf to bucket {} with filename {}", pdfBucketService.getBucketName(), blobName);
                }

                byte[] pdfBytes = IOUtils.toByteArray(pdf);
                response.type("application/pdf");
                response.header("Content-Disposition", String.format("inline; filename=\"%s.pdf\"",
                        configInfo.getFilename()));

                HttpServletResponse raw = response.raw();
                raw.getOutputStream().write(pdfBytes);
                raw.getOutputStream().flush();
                raw.getOutputStream().close();
                return raw;
            } catch (IOException | DDPException e) {
                String msg = String.format("Failed to fetch %s pdf for study %s and participant %s",
                        configName, studyGuid, participantGuidOrAltPid);
                ApiError err = new ApiError(ErrorCodes.SERVER_ERROR, msg);
                LOG.error(err.getMessage());
                throw ResponseUtil.haltError(response, HttpStatus.SC_INTERNAL_SERVER_ERROR, err);
            }
        });
    }

    /**
     * Ensures the user is enrolled (not registered or exited), or halts the execution of the route.
     *
     * @param handle   the database handle
     * @param userDto  the user
     * @param studyDto the study
     * @param response the route response
     */
    private void haltIfUserNotEnrolled(Handle handle, UserDto userDto, StudyDto studyDto, Response response) {
        EnrollmentStatusType enrollmentStatusType = handle.attach(JdbiUserStudyEnrollment.class)
                .getEnrollmentStatusByUserAndStudyIds(userDto.getUserId(), studyDto.getId())
                .orElseThrow(() -> {
                    String msg = "Could not find enrollment status for user with GUID " + userDto.getUserGuid();
                    ApiError err = new ApiError(ErrorCodes.USER_NOT_FOUND, msg);
                    LOG.error(err.getMessage());
                    return ResponseUtil.haltError(response, HttpStatus.SC_INTERNAL_SERVER_ERROR, err);
                });

        if (enrollmentStatusType != EnrollmentStatusType.ENROLLED) {
            String msg = "User " + userDto.getUserGuid() + " was not enrolled in study " + studyDto.getGuid();
            ApiError err = new ApiError(ErrorCodes.UNSATISFIED_PRECONDITION, msg);
            LOG.error(err.getMessage());
            throw ResponseUtil.haltError(response, HttpStatus.SC_INTERNAL_SERVER_ERROR, err);
        }
    }
}
