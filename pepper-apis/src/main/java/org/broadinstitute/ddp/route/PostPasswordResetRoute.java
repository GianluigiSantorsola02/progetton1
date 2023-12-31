package org.broadinstitute.ddp.route;

import java.util.Optional;

import okhttp3.HttpUrl;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpStatus;

import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.constants.RouteConstants.QueryParam;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.JdbiClient;
import org.broadinstitute.ddp.db.dto.ClientDto;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.util.ResponseUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Request;
import spark.Response;
import spark.Route;

/**
 * Auth0 callback route executed when the user's password is reset
 * Returns a redirect URL for a client
 */
public class PostPasswordResetRoute implements Route {

    private static final Logger LOG = LoggerFactory.getLogger(PostPasswordResetRoute.class);

    @Override
    public Object handle(Request request, Response response) throws Exception {
        String auth0ClientId = request.queryParams(QueryParam.AUTH0_CLIENT_ID);
        String email = request.queryParams(QueryParam.EMAIL);
        String auth0Success = request.queryParams(QueryParam.SUCCESS);

        if (StringUtils.isBlank(auth0ClientId) || StringUtils.isBlank(email)) {
            String errMsg = "auth0ClientId and email query string parameters are mandatory";
            LOG.warn(errMsg);
            ResponseUtil.haltError(response, HttpStatus.SC_BAD_REQUEST, new ApiError(ErrorCodes.REQUIRED_PARAMETER_MISSING, errMsg));
        }

        HttpUrl clientPwdResetUrl = getWebClientPasswordResetRedirectUrl(auth0ClientId, response);

        HttpUrl.Builder urlBuilder = clientPwdResetUrl.newBuilder();
        urlBuilder.addQueryParameter(QueryParam.EMAIL, email);

        if (!Boolean.valueOf(auth0Success)) {
            String errMsg = "success parameter is FALSE, which means that the Auth0 link has expired";
            LOG.warn(errMsg);
            urlBuilder.addQueryParameter(QueryParam.ERROR_CODE, ErrorCodes.PASSWORD_RESET_LINK_EXPIRED);
        }

        response.header(
                HttpHeaders.LOCATION,
                urlBuilder.build().toString()
        );
        response.status(HttpStatus.SC_MOVED_TEMPORARILY);
        return "";
    }

    private HttpUrl getWebClientPasswordResetRedirectUrl(String auth0ClientId, Response response) {
        return TransactionWrapper.withTxn(
                handle -> {
                    JdbiClient jdbiClient = handle.attach(JdbiClient.class);
                    Optional<ClientDto> clientDto = jdbiClient.findByAuth0ClientId(auth0ClientId);
                    if (!clientDto.isPresent()) {
                        String errMsg = "Client with Auth0 client id " + auth0ClientId + " does not exist";
                        LOG.warn(errMsg);
                        throw ResponseUtil.haltError(response, HttpStatus.SC_NOT_FOUND, new ApiError(ErrorCodes.NOT_FOUND, errMsg));
                    }
                    boolean isRevoked = clientDto.get().isRevoked();
                    if (isRevoked) {
                        String errMsg = "The client is revoked";
                        LOG.warn(errMsg);
                        throw ResponseUtil.haltError(
                                response,
                                HttpStatus.SC_UNPROCESSABLE_ENTITY,
                                new ApiError(ErrorCodes.OPERATION_NOT_ALLOWED, errMsg)
                        );
                    }
                    String redirUrl = clientDto.get().getWebPasswordRedirectUrl();
                    if (redirUrl == null) {
                        String errMsg = "Post-password redirect URL for the auth0 client with id " + auth0ClientId + " is not defined";
                        LOG.warn(errMsg);
                        throw ResponseUtil.haltError(
                                response,
                                HttpStatus.SC_UNPROCESSABLE_ENTITY,
                                new ApiError(ErrorCodes.OPERATION_NOT_ALLOWED, errMsg)
                        );
                    }

                    HttpUrl parsedUrl = HttpUrl.parse(redirUrl);
                    if (parsedUrl == null) {
                        String errMsg = "Post password reset URL " + redirUrl + " is malformed";
                        LOG.warn(errMsg);
                        ResponseUtil.haltError(
                                response,
                                HttpStatus.SC_INTERNAL_SERVER_ERROR,
                                new ApiError(ErrorCodes.MALFORMED_REDIRECT_URL, errMsg)
                        );
                    }

                    return parsedUrl;
                }
        );
    }


}
