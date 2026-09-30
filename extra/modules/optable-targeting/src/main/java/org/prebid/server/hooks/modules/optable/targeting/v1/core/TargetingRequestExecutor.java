package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.User;
import io.vertx.core.Future;
import org.apache.commons.lang3.ObjectUtils;
import org.prebid.server.activity.Activity;
import org.prebid.server.activity.ComponentType;
import org.prebid.server.activity.infrastructure.ActivityInfrastructure;
import org.prebid.server.activity.infrastructure.payload.ActivityInvocationPayload;
import org.prebid.server.activity.infrastructure.payload.impl.ActivityInvocationPayloadImpl;
import org.prebid.server.activity.infrastructure.payload.impl.BidRequestActivityInvocationPayload;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.model.IpAddress;
import org.prebid.server.auction.model.TimeoutContext;
import org.prebid.server.auction.privacy.enforcement.mask.UserFpdActivityMask;
import org.prebid.server.auction.requestfactory.Ortb2ImplicitParametersResolver;
import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.execution.timeout.TimeoutFactory;
import org.prebid.server.hooks.modules.optable.targeting.model.OptableAttributes;
import org.prebid.server.hooks.modules.optable.targeting.model.config.OptableTargetingProperties;
import org.prebid.server.hooks.modules.optable.targeting.model.openrtb.TargetingResult;
import org.prebid.server.hooks.modules.optable.targeting.v1.OptableTargetingModule;
import org.prebid.server.hooks.v1.auction.AuctionInvocationContext;
import org.prebid.server.model.HttpRequestContext;

import java.util.Objects;

public class TargetingRequestExecutor {

    private final OptableTargeting optableTargeting;
    private final UserFpdActivityMask userFpdActivityMask;
    private final Ortb2ImplicitParametersResolver implicitParametersResolver;
    private final TimeoutFactory timeoutFactory;
    private final double logSamplingRate;

    public TargetingRequestExecutor(OptableTargeting optableTargeting,
                                    UserFpdActivityMask userFpdActivityMask,
                                    Ortb2ImplicitParametersResolver implicitParametersResolver,
                                    TimeoutFactory timeoutFactory,
                                    double logSamplingRate) {

        this.optableTargeting = Objects.requireNonNull(optableTargeting);
        this.userFpdActivityMask = Objects.requireNonNull(userFpdActivityMask);
        this.implicitParametersResolver = Objects.requireNonNull(implicitParametersResolver);
        this.timeoutFactory = ObjectUtils.requireNonEmpty(timeoutFactory);
        this.logSamplingRate = logSamplingRate;
    }

    public Future<TargetingResult> makeRequest(BidRequest originalBidRequest,
                                               AuctionInvocationContext invocationContext,
                                               OptableTargetingProperties properties,
                                               boolean outlivesHook) {

        final BidRequest bidRequest = applyActivityRestrictions(originalBidRequest, invocationContext);

        final Timeout timeout = outlivesHook
                ? resolveCrossHookTimeout(invocationContext, properties)
                : invocationContext.timeout();
        final OptableAttributes attributes = OptableAttributesResolver.resolveAttributes(
                invocationContext.auctionContext(),
                originalBidRequest,
                resolveRequestIp(invocationContext.auctionContext()),
                properties.getTimeout(),
                logSamplingRate);

        return optableTargeting.getTargeting(properties, bidRequest, attributes, timeout);
    }

    /**
     * A call that is awaited by a later hook can't be bound by the timeout of the hook that starts it.
     */
    private Timeout resolveCrossHookTimeout(AuctionInvocationContext invocationContext,
                                            OptableTargetingProperties properties) {

        final Long apiTimeout = properties.getApiTimeout();
        if (apiTimeout != null && apiTimeout > 0) {
            return timeoutFactory.create(apiTimeout);
        }

        final TimeoutContext timeoutContext = invocationContext.auctionContext().getTimeoutContext();
        final Timeout auctionTimeout = timeoutContext != null ? timeoutContext.getTimeout() : null;
        return auctionTimeout != null ? auctionTimeout : invocationContext.timeout();
    }

    /**
     * The core fills device.ip from the HTTP request only after the raw auction request stage, so the same
     * resolution is applied here for the requests that come without it.
     */
    private String resolveRequestIp(AuctionContext auctionContext) {
        final HttpRequestContext httpRequest = auctionContext.getHttpRequest();
        final IpAddress ipAddress = httpRequest != null && httpRequest.getHeaders() != null
                ? implicitParametersResolver.findIpFromRequest(httpRequest)
                : null;

        return ipAddress != null ? ipAddress.getIp() : null;
    }

    private BidRequest applyActivityRestrictions(BidRequest bidRequest,
                                                 AuctionInvocationContext auctionInvocationContext) {

        final AuctionContext auctionContext = auctionInvocationContext.auctionContext();
        final ActivityInvocationPayload activityInvocationPayload = BidRequestActivityInvocationPayload.of(
                ActivityInvocationPayloadImpl.of(ComponentType.GENERAL_MODULE, OptableTargetingModule.CODE),
                bidRequest);
        final ActivityInfrastructure activityInfrastructure = auctionContext.getActivityInfrastructure();

        final boolean disallowTransmitUfpd = !activityInfrastructure.isAllowed(
                Activity.TRANSMIT_UFPD, activityInvocationPayload);
        final boolean disallowTransmitEids = !activityInfrastructure.isAllowed(
                Activity.TRANSMIT_EIDS, activityInvocationPayload);
        final boolean disallowTransmitGeo = !activityInfrastructure.isAllowed(
                Activity.TRANSMIT_GEO, activityInvocationPayload);

        return maskUserPersonalInfo(bidRequest, disallowTransmitUfpd, disallowTransmitEids, disallowTransmitGeo);
    }

    private BidRequest maskUserPersonalInfo(BidRequest bidRequest,
                                            boolean disallowTransmitUfpd,
                                            boolean disallowTransmitEids,
                                            boolean disallowTransmitGeo) {

        final User maskedUser = userFpdActivityMask.maskUser(
                bidRequest.getUser(), disallowTransmitUfpd, disallowTransmitEids);
        final Device maskedDevice = userFpdActivityMask.maskDevice(
                bidRequest.getDevice(), disallowTransmitUfpd, disallowTransmitGeo);

        return bidRequest.toBuilder()
                .user(maskedUser)
                .device(maskedDevice)
                .build();
    }
}
