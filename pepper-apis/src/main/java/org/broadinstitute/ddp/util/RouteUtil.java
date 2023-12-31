package org.broadinstitute.ddp.util;

import static org.broadinstitute.ddp.constants.RouteConstants.BEARER;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.constants.RouteConstants;
import org.broadinstitute.ddp.content.ContentStyle;
import org.broadinstitute.ddp.filter.TokenConverterFilter;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.security.DDPAuth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.utils.SparkUtils;

public class RouteUtil {

    private static final Logger LOG = LoggerFactory.getLogger(RouteUtil.class);

    private static final String STUDIES_PATH_MARKER = "studies";
    private static final int STUDIES_MARKER_IDX = 4;
    private static final int STUDY_GUID_IDX = 5;

    /**
     * Returns the {@link org.broadinstitute.ddp.security.DDPAuth auth object}
     * associated with this request.  Will always be non-null.
     */
    @Nonnull
    public static DDPAuth getDDPAuth(Request req) {
        DDPAuth ddpAuth = req.attribute(TokenConverterFilter.DDP_TOKEN);
        if (ddpAuth == null) {
            ddpAuth = new DDPAuth();
        }
        return ddpAuth;
    }

    public static String makeAuthBearerHeader(String headerValue) {
        return BEARER + headerValue;
    }

    /**
     * Gets the internal guid of the client for this request.
     * May be null.
     */
    public static String getClientGuid(Request req) {
        return getDDPAuth(req).getClient();
    }

    public static String getAcceptLanguageHeader(Request req) {
        return req.headers("Accept-Language");
    }

    /**
     * Helper to parse out the content style header. The default is returned if header is missing, and the request is
     * halted with an error response if the given style is invalid.
     *
     * @param request      the request
     * @param response     the response
     * @param defaultStyle the default style to use
     * @return the content style
     */
    public static ContentStyle parseContentStyleHeaderOrHalt(Request request, Response response, ContentStyle defaultStyle) {
        String value = request.headers(RouteConstants.Header.DDP_CONTENT_STYLE);
        if (value == null) {
            return defaultStyle;
        }

        try {
            // Massage the value to match enum, and in turn allow a bit of flexibility to clients.
            return ContentStyle.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Unable to convert ddp content style header value '{}'", value, e);
            String styleValues = Arrays.stream(ContentStyle.values())
                    .map(ContentStyle::name)
                    .collect(Collectors.joining(", "));
            String msg = String.format("Invalid content style: '%s'. Should be one of: %s.", value, styleValues);
            ResponseUtil.haltError(response, 400, new ApiError(ErrorCodes.MALFORMED_HEADER, msg));
            return null;
        }
    }

    /**
     * Grab the study guid from the URI path. This assumes it is a study path and does a bit of sanity check for that.
     *
     * @param path the URI path
     * @return the study guid or null if the study could not be parsed
     */
    public static String parseStudyGuid(String path) {
        List<String> parts = SparkUtils.convertRouteToList(path);
        String studyGuid = null;
        if (parts.size() > STUDY_GUID_IDX && STUDIES_PATH_MARKER.equals(parts.get(STUDIES_MARKER_IDX))) {
            studyGuid = parts.get(STUDY_GUID_IDX);
        }
        return studyGuid;
    }
}
