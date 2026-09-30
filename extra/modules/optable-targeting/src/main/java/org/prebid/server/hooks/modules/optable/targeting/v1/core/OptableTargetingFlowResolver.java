package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
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
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtUser;
import org.prebid.server.settings.model.Account;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class OptableTargetingFlowResolver {

    private static final ConditionalLogger conditionalLogger = new ConditionalLogger(
            LoggerFactory.getLogger(OptableTargetingProcessedAuctionRequestHook.class));

    private static final String OPTABLE_FIELD = "optable";
    private static final String IMP_STORED_REQUEST_PATH = "/prebid/storedrequest";
    private static final String AUCTION_NOT_PROPERLY_CONFIGURED =
            "Account not properly configured: tenant and/or origin is missing.";

    private final BidderEnrichmentSampler bidderEnrichmentSampler;
    private final TargetingRequestExecutor targetingRequestExecutor;
    private final CompositeHookExecutionPlan hooksExecutionPlan;
    private final double logSamplingRate;

    public OptableTargetingFlowResolver(BidderEnrichmentSampler bidderEnrichmentSampler,
                                        TargetingRequestExecutor targetingRequestExecutor,
                                        CompositeHookExecutionPlan hooksExecutionPlan,
                                        double logSamplingRate) {

        this.bidderEnrichmentSampler = Objects.requireNonNull(bidderEnrichmentSampler);
        this.targetingRequestExecutor = Objects.requireNonNull(targetingRequestExecutor);
        this.hooksExecutionPlan = hooksExecutionPlan;
        this.logSamplingRate = logSamplingRate;
    }

    /**
     * Raw auction request stage. Stored requests and stored imps are merged only after it, so when the request
     * relies on them the call is deferred to the processed auction request hook, which sees the merged request.
     */
    public Future<InvocationResult<AuctionRequestPayload>> resolveAsyncOptableTargetingFlow(
            ModuleContext moduleContext,
            AuctionRequestPayload payload,
            AuctionInvocationContext invocationContext,
            OptableTargetingProperties properties) {

        final BidRequest bidRequest = payload.bidRequest();
        if (shouldDeferTargetingCall(bidRequest)) {
            moduleContext.setEarlyCallInitializationCompleted(false);
            // the cleaner strips user.ext.optable at this stage, while the deferred call still needs its ids
            moduleContext.setExtUserOptable(extUserOptable(bidRequest));
        } else {
            startTargetingCall(moduleContext, bidRequest, invocationContext, properties);
        }

        return update(BidRequestCleaner.instance(), moduleContext);
    }

    /**
     * Processed auction request stage: starts the call deferred by the raw auction request hook.
     */
    public Future<InvocationResult<AuctionRequestPayload>> resolveDeferredOptableTargetingFlow(
            ModuleContext moduleContext,
            AuctionRequestPayload payload,
            AuctionInvocationContext invocationContext,
            OptableTargetingProperties properties) {

        final BidRequest bidRequest = withExtUserOptable(payload.bidRequest(), moduleContext.getExtUserOptable());
        moduleContext.setEarlyCallInitializationCompleted(true);
        moduleContext.setExtUserOptable(null);
        moduleContext.setCallTargetingAPITimestamp(System.currentTimeMillis());

        startTargetingCall(moduleContext, bidRequest, invocationContext, properties);

        return update(BidRequestCleaner.instance(), moduleContext);
    }

    private boolean shouldDeferTargetingCall(BidRequest bidRequest) {
        return (bidRequest.getSite() == null && bidRequest.getApp() == null)
                || !bidderEnrichmentSampler.hasBidders(bidRequest)
                || hasStoredRequest(bidRequest);
    }

    private static boolean hasStoredRequest(BidRequest bidRequest) {
        final ExtRequest ext = bidRequest.getExt();
        final ExtRequestPrebid prebid = ext != null ? ext.getPrebid() : null;
        if (prebid != null && prebid.getStoredrequest() != null) {
            return true;
        }

        return Optional.ofNullable(bidRequest.getImp())
                .stream()
                .flatMap(Collection::stream)
                .map(Imp::getExt)
                .filter(Objects::nonNull)
                .anyMatch(impExt -> !impExt.at(IMP_STORED_REQUEST_PATH).isMissingNode());
    }

    private void startTargetingCall(ModuleContext moduleContext,
                                    BidRequest bidRequest,
                                    AuctionInvocationContext invocationContext,
                                    OptableTargetingProperties properties) {

        if (!PropertiesValidator.isTrafficSourceValid(bidRequest, properties)) {
            moduleContext.setShouldSkipEnrichment(true);
            return;
        }

        final Set<String> biddersToEnrich = bidderEnrichmentSampler.sample(bidRequest, properties);
        if (CollectionUtils.isEmpty(biddersToEnrich)) {
            return;
        }

        moduleContext.setBiddersToEnrich(biddersToEnrich);
        final Account account = invocationContext.auctionContext().getAccount();
        final long crossHookFutureTimeout =
                hooksExecutionPlan.getOptableTargetingBidderRequestTimeout(account);

        moduleContext.setOptableTargetingCall(targetingRequestExecutor.makeRequest(
                bidRequest,
                invocationContext,
                properties,
                crossHookFutureTimeout));
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

        final Account account = invocationContext.auctionContext().getAccount();
        final boolean hasRawAuctionRequestHook = hooksExecutionPlan.hasRawAuctionRequestHook(account);
        final boolean hasBidderRequestHook = hooksExecutionPlan.hasBidderRequestHook(account);

        if (hasRawAuctionRequestHook && hasBidderRequestHook) {
            return updateWithAnalytics(BidRequestCleaner.instance(), moduleContext);
        }

        final Future<TargetingResult> optableTargetingCall = hasRawAuctionRequestHook
                ? resolveEarlyNetworkCall(moduleContext)
                : resolvePreEarlyNetworkCall(auctionRequestPayload, invocationContext, moduleContext, properties);

        if (optableTargetingCall == null) {
            moduleContext.failWithExecutionTime(calcAPICallExecutionTime(moduleContext));
            return updateWithAnalytics(BidRequestCleaner.instance(), moduleContext);
        }

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

    private Future<TargetingResult> resolveEarlyNetworkCall(ModuleContext moduleContext) {
        return moduleContext.getOptableTargetingCall();
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
                null);
    }

    private static Future<InvocationResult<AuctionRequestPayload>> update(
            PayloadUpdate<AuctionRequestPayload> payloadUpdate,
            ModuleContext moduleContext) {

        return Future.succeededFuture(
                InvocationResultImpl.<AuctionRequestPayload>builder()
                        .status(InvocationStatus.success)
                        .action(InvocationAction.update)
                        .payloadUpdate(payloadUpdate)
                        .moduleContext(moduleContext)
                        .build());
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
