package org.prebid.server.hooks.modules.optable.targeting.v1;

import io.vertx.core.Future;
import org.prebid.server.hooks.modules.optable.targeting.model.ModuleContext;
import org.prebid.server.hooks.modules.optable.targeting.model.config.OptableTargetingProperties;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.ConfigResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.OptableTargetingFlowResolver;
import org.prebid.server.hooks.v1.InvocationResult;
import org.prebid.server.hooks.v1.auction.AuctionInvocationContext;
import org.prebid.server.hooks.v1.auction.AuctionRequestPayload;
import org.prebid.server.hooks.v1.auction.ProcessedAuctionRequestHook;
import org.prebid.server.log.ConditionalLogger;
import org.prebid.server.log.LoggerFactory;

import java.util.Objects;

public class OptableTargetingProcessedAuctionRequestHook implements ProcessedAuctionRequestHook {

    private static final ConditionalLogger conditionalLogger = new ConditionalLogger(
            LoggerFactory.getLogger(OptableTargetingProcessedAuctionRequestHook.class));

    public static final String CODE = "optable-targeting-processed-auction-request-hook";

    private final ConfigResolver configResolver;

    private final OptableTargetingFlowResolver flowResolver;
    private final double logSamplingRate;

    public OptableTargetingProcessedAuctionRequestHook(ConfigResolver configResolver,
                                                       OptableTargetingFlowResolver flowResolver,
                                                       double logSamplingRate) {

        this.configResolver = Objects.requireNonNull(configResolver);
        this.flowResolver = Objects.requireNonNull(flowResolver);
        this.logSamplingRate = logSamplingRate;
    }

    @Override
    public Future<InvocationResult<AuctionRequestPayload>> call(AuctionRequestPayload auctionRequestPayload,
                                                                AuctionInvocationContext invocationContext) {

        final ModuleContext moduleContext = ModuleContext.of(invocationContext);

        // whatever goes wrong here, the cleaner has to be applied, or user.ext.optable ids reach the bidders
        try {
            final OptableTargetingProperties properties = configResolver.resolve(invocationContext.accountConfig());

            if (moduleContext.isEnrichmentDeferred()) {
                flowResolver.startDeferredTargetingCall(
                        moduleContext, auctionRequestPayload.bidRequest(), invocationContext, properties, true);
            }

            return flowResolver.resolveOptableTargetingFlow(
                    auctionRequestPayload, invocationContext, moduleContext, properties);
        } catch (RuntimeException e) {
            conditionalLogger.error(
                    "Failed to initiate Optable targeting call: " + e.getMessage(), logSamplingRate);

            moduleContext.setEnrichmentDeferred(false);
            return flowResolver.failed(moduleContext);
        }
    }

    @Override
    public String code() {
        return CODE;
    }
}
