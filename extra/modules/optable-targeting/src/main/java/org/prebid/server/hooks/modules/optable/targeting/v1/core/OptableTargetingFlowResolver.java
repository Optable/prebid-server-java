package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.User;
import io.vertx.core.Future;
import org.apache.commons.collections4.CollectionUtils;
import org.prebid.server.hooks.execution.v1.InvocationResultImpl;
import org.prebid.server.hooks.modules.optable.targeting.model.EnrichmentStatus;
import org.prebid.server.hooks.modules.optable.targeting.model.ModuleContext;
import org.prebid.server.hooks.modules.optable.targeting.model.config.OptableTargetingProperties;
import org.prebid.server.hooks.modules.optable.targeting.model.openrtb.TargetingResult;
import org.prebid.server.hooks.modules.optable.targeting.v1.OptableTargetingProcessedAuctionRequestHook;
import org.prebid.server.hooks.v1.InvocationAction;
import org.prebid.server.hooks.v1.InvocationResult;
import org.prebid.server.hooks.v1.InvocationStatus;
import org.prebid.server.hooks.v1.PayloadUpdate;
import org.prebid.server.hooks.v1.auction.AuctionInvocationContext;
import org.prebid.server.hooks.v1.auction.AuctionRequestPayload;
import org.prebid.server.log.ConditionalLogger;
import org.prebid.server.log.LoggerFactory;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;

import java.util.Objects;
import java.util.Set;

public class OptableTargetingFlowResolver {

    private static final ConditionalLogger conditionalLogger = new ConditionalLogger(
            LoggerFactory.getLogger(OptableTargetingProcessedAuctionRequestHook.class));

    private static final String OPTABLE_FIELD = "optable";
    private static final String AUCTION_NOT_PROPERLY_CONFIGURED =
            "Account not properly configured: tenant and/or origin is missing.";

    private final BidderEnrichmentSampler bidderEnrichmentSampler;
    private final TargetingRequestExecutor targetingRequestExecutor;
    private final double logSamplingRate;

    public OptableTargetingFlowResolver(BidderEnrichmentSampler bidderEnrichmentSampler,
                                        TargetingRequestExecutor targetingRequestExecutor,
                                        double logSamplingRate) {

        this.bidderEnrichmentSampler = Objects.requireNonNull(bidderEnrichmentSampler);
        this.targetingRequestExecutor = Objects.requireNonNull(targetingRequestExecutor);
        this.logSamplingRate = logSamplingRate;
    }

    /**
     * Stored requests and stored imps are merged after the raw auction request stage, so a request that relies on
     * them may carry neither bidders nor site/app yet. The decision has to wait for the merged request then.
     */
    public boolean shouldDeferTargetingCall(BidRequest bidRequest) {
        return (bidRequest.getSite() == null && bidRequest.getApp() == null)
                || !bidderEnrichmentSampler.hasBidders(bidRequest);
    }

    public void deferTargetingCall(ModuleContext moduleContext, BidRequest bidRequest) {
        moduleContext.setEnrichmentDeferred(true);
        // the cleaner strips user.ext.optable at this stage, while the deferred call still needs its ids
        moduleContext.setExtUserOptable(extUserOptable(bidRequest));
    }

    public void startTargetingCall(ModuleContext moduleContext,
                                   BidRequest bidRequest,
                                   AuctionInvocationContext invocationContext,
                                   OptableTargetingProperties properties,
                                   boolean outlivesHook) {

        if (!PropertiesValidator.isTrafficSourceValid(bidRequest, properties)) {
            moduleContext.setShouldSkipEnrichment(true);
            return;
        }

        final Set<String> biddersToEnrich = bidderEnrichmentSampler.sample(bidRequest, properties);
        if (CollectionUtils.isEmpty(biddersToEnrich)) {
            return;
        }

        moduleContext.setBiddersToEnrich(biddersToEnrich);
        moduleContext.setOptableTargetingCall(targetingRequestExecutor.makeRequest(
                bidRequest,
                invocationContext,
                properties,
                outlivesHook));
    }

    public void startDeferredTargetingCall(ModuleContext moduleContext,
                                           BidRequest mergedBidRequest,
                                           AuctionInvocationContext invocationContext,
                                           OptableTargetingProperties properties,
                                           boolean outlivesHook) {

        final BidRequest bidRequest = withExtUserOptable(mergedBidRequest, moduleContext.getExtUserOptable());
        moduleContext.setEnrichmentDeferred(false);
        moduleContext.setExtUserOptable(null);
        moduleContext.setCallTargetingAPITimestamp(System.currentTimeMillis());

        startTargetingCall(moduleContext, bidRequest, invocationContext, properties, outlivesHook);
    }

    private static JsonNode extUserOptable(BidRequest bidRequest) {
        final User user = bidRequest.getUser();
        final ExtUser extUser = user != null ? user.getExt() : null;
        return extUser != null ? extUser.getProperty(OPTABLE_FIELD) : null;
    }

    private static BidRequest withExtUserOptable(BidRequest bidRequest, JsonNode optable) {
        if (optable == null) {
            return bidRequest;
        }

        final User user = bidRequest.getUser();
        final ExtUser extUser = user != null ? user.getExt() : null;
        final ExtUser restoredExtUser = extUser != null ? extUser.toBuilder().build() : ExtUser.builder().build();
        if (extUser != null) {
            restoredExtUser.addProperties(extUser.getProperties());
        }
        restoredExtUser.addProperty(OPTABLE_FIELD, optable);

        final User restoredUser = (user != null ? user.toBuilder() : User.builder()).ext(restoredExtUser).build();
        return bidRequest.toBuilder().user(restoredUser).build();
    }

    /**
     * @deprecated This call is deprecated and will be removed in a future release.
     */
    @Deprecated
    public Future<InvocationResult<AuctionRequestPayload>> resolveOptableTargetingFlow(
            AuctionRequestPayload auctionRequestPayload,
            AuctionInvocationContext invocationContext,
            ModuleContext moduleContext,
            OptableTargetingProperties properties) {

        if (moduleContext.isShouldSkipEnrichment()) {
            moduleContext.setOptableTargetingExecutionTime(calcAPICallExecutionTime(moduleContext));
            return updateWithAnalytics(BidRequestCleaner.instance(), moduleContext);
        }

        // once the raw auction request hook has run, enrichment belongs to the bidder request hook
        if (moduleContext.isEarlyNetworkCallEnabled()) {
            return updateWithAnalytics(BidRequestCleaner.instance(), moduleContext);
        }

        final Future<TargetingResult> optableTargetingCall =
                resolvePreEarlyNetworkCall(auctionRequestPayload, invocationContext, moduleContext, properties);

        return optableTargetingCall
                .compose(targetingResult -> {
                    moduleContext.setOptableTargetingExecutionTime(calcAPICallExecutionTime(moduleContext));
                    return enrichPayload(targetingResult, moduleContext, properties);
                })
                .recover(throwable -> {
                    moduleContext.failWithExecutionTime(calcAPICallExecutionTime(moduleContext));
                    return updateWithAnalytics(BidRequestCleaner.instance(), moduleContext);
                });
    }

    private Future<InvocationResult<AuctionRequestPayload>> enrichPayload(
            TargetingResult targetingResult,
            ModuleContext moduleContext,
            OptableTargetingProperties properties) {

        moduleContext.setTargeting(targetingResult.getAudience());
        moduleContext.setId5Signature(Id5Resolver.resolveId5Signature(targetingResult));
        moduleContext.setEnrichRequestStatus(EnrichmentStatus.success());

        final PayloadUpdate<AuctionRequestPayload> payloadUpdate =
                BidRequestCleaner.instance().andThen(BidRequestEnricher.of(targetingResult, properties))::apply;

        return updateWithAnalytics(payloadUpdate, moduleContext);
    }

    private static long calcAPICallExecutionTime(ModuleContext moduleContext) {
        return System.currentTimeMillis() - moduleContext.getCallTargetingAPITimestamp();
    }

    private Future<TargetingResult> resolvePreEarlyNetworkCall(
            AuctionRequestPayload payload,
            AuctionInvocationContext invocationContext,
            ModuleContext moduleContext,
            OptableTargetingProperties properties) {

        moduleContext.setCallTargetingAPITimestamp(System.currentTimeMillis());
        if (!PropertiesValidator.isValid(properties)) {
            conditionalLogger.error(AUCTION_NOT_PROPERLY_CONFIGURED, logSamplingRate);

            moduleContext.failWithExecutionTime(
                    System.currentTimeMillis() - moduleContext.getCallTargetingAPITimestamp());
            return Future.failedFuture(AUCTION_NOT_PROPERLY_CONFIGURED);
        }

        return targetingRequestExecutor.makeRequest(
                payload.bidRequest(),
                invocationContext,
                properties,
                false);
    }

    private static Future<InvocationResult<AuctionRequestPayload>> updateWithAnalytics(
            PayloadUpdate<AuctionRequestPayload> payloadUpdate,
            ModuleContext moduleContext) {

        return Future.succeededFuture(
                InvocationResultImpl.<AuctionRequestPayload>builder()
                        .status(InvocationStatus.success)
                        .action(InvocationAction.update)
                        .analyticsTags(AnalyticTagsResolver.toEnrichRequestAnalyticTags(moduleContext))
                        .payloadUpdate(payloadUpdate)
                        .moduleContext(moduleContext)
                        .build());
    }
}
