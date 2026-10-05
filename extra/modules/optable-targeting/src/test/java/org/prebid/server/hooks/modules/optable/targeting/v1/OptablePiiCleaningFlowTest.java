package org.prebid.server.hooks.modules.optable.targeting.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Site;
import io.vertx.core.Future;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.prebid.server.activity.infrastructure.ActivityInfrastructure;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.privacy.enforcement.mask.UserFpdActivityMask;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.execution.timeout.TimeoutFactory;
import org.prebid.server.hooks.execution.model.ExecutionPlan;
import org.prebid.server.hooks.execution.v1.auction.AuctionRequestPayloadImpl;
import org.prebid.server.hooks.execution.v1.bidder.BidderRequestPayloadImpl;
import org.prebid.server.hooks.modules.optable.targeting.model.ModuleContext;
import org.prebid.server.hooks.modules.optable.targeting.model.config.OptableTargetingProperties;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.AliasesResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.BidderEnrichmentSampler;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.CompositeHookExecutionPlan;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.ConfigResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.OptableTargeting;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.OptableTargetingFlowResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.TargetingRequestExecutor;
import org.prebid.server.hooks.v1.InvocationAction;
import org.prebid.server.hooks.v1.InvocationResult;
import org.prebid.server.hooks.v1.auction.AuctionInvocationContext;
import org.prebid.server.hooks.v1.auction.AuctionRequestPayload;
import org.prebid.server.hooks.v1.bidder.BidderInvocationContext;
import org.prebid.server.hooks.v1.bidder.BidderRequestPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Walks a request carrying user.ext.optable ids through every hook setup, request shape and outcome the way the
 * core does, and checks that no id survives in the auction-level request or in any bidder request. A hook result
 * is applied only when its future succeeded, as the core drops the payload update of a failed hook.
 */
public class OptablePiiCleaningFlowTest extends BaseOptableTest {

    private static final List<String> PII_FIELDS = List.of("email", "phone", "zip", "vid", "id5_signature");

    private enum HookSetup {
        RAW_PROCESSED_BIDDER(true, true),
        PROCESSED_BIDDER(false, true),
        PROCESSED_ONLY(false, false);

        private final boolean hasRawHook;
        private final boolean hasBidderHook;

        HookSetup(boolean hasRawHook, boolean hasBidderHook) {
            this.hasRawHook = hasRawHook;
            this.hasBidderHook = hasBidderHook;
        }
    }

    private enum RequestShape {
        INLINE_SITE,
        INLINE_APP,
        STORED_IMP,
        APP_FROM_STORED_REQUEST,
        DISABLED_TRAFFIC_SOURCE
    }

    private enum Outcome {
        ENRICHED,
        SAMPLED_OUT,
        NO_DATA,
        API_ERROR,
        HOOK_EXCEPTION
    }

    @TestFactory
    public Stream<DynamicTest> shouldNeverPassUserExtOptableIdsOn() {
        return Stream.of(HookSetup.values())
                .flatMap(setup -> Stream.of(RequestShape.values())
                        .flatMap(shape -> Stream.of(Outcome.values())
                                .map(outcome -> DynamicTest.dynamicTest(
                                        setup + " / " + shape + " / " + outcome,
                                        () -> assertNoPiiPassedOn(setup, shape, outcome)))));
    }

    private void assertNoPiiPassedOn(HookSetup setup, RequestShape shape, Outcome outcome) {
        final Flow flow = new Flow(setup, outcome);

        // raw auction request stage: the core sees the request as it arrived
        final BidRequest rawRequest = givenRawRequest(shape);
        BidRequest auctionRequest = rawRequest;
        ModuleContext moduleContext = null;
        if (setup.hasRawHook) {
            final InvocationResult<AuctionRequestPayload> rawResult = flow.callRawHook(rawRequest);
            moduleContext = (ModuleContext) rawResult.moduleContext();
            auctionRequest = applied(rawResult, auctionRequest);
        }

        // stored request and stored imps are merged into a new request between the raw and processed stages
        final BidRequest mergedRequest = merged(copyOf(auctionRequest), shape);

        final InvocationResult<AuctionRequestPayload> processedResult =
                flow.callProcessedHook(mergedRequest, moduleContext);
        moduleContext = processedResult != null ? (ModuleContext) processedResult.moduleContext() : moduleContext;
        final BidRequest processedRequest = applied(processedResult, mergedRequest);

        assertNoPii(processedRequest, "auction-level request");

        final List<BidRequest> bidderRequests = new ArrayList<>();
        for (String bidder : List.of("bidderA", "bidderB")) {
            final BidRequest bidderRequest = copyOf(processedRequest);
            bidderRequests.add(setup.hasBidderHook
                    ? appliedToBidder(flow.callBidderHook(bidder, bidderRequest, moduleContext), bidderRequest)
                    : bidderRequest);
        }

        bidderRequests.forEach(bidderRequest -> assertNoPii(bidderRequest, "bidder request"));
    }

    private BidRequest givenRawRequest(RequestShape shape) {
        final BidRequest.BidRequestBuilder builder = BidRequest.builder()
                .id("requestId")
                .user(givenUserWithAllIds());

        return switch (shape) {
            case INLINE_SITE -> builder.site(Site.builder().build()).imp(List.of(givenImp("bidderA", "bidderB")))
                    .build();
            case INLINE_APP, DISABLED_TRAFFIC_SOURCE -> builder.app(App.builder().bundle("bundle").build())
                    .imp(List.of(givenImp("bidderA", "bidderB"))).build();
            case STORED_IMP -> builder.site(Site.builder().build()).imp(List.of(givenStoredImp())).build();
            case APP_FROM_STORED_REQUEST -> builder.imp(List.of(givenImp("bidderA", "bidderB"))).build();
        };
    }

    private BidRequest merged(BidRequest bidRequest, RequestShape shape) {
        return switch (shape) {
            case STORED_IMP -> bidRequest.toBuilder().imp(List.of(givenImp("bidderA", "bidderB"))).build();
            case APP_FROM_STORED_REQUEST -> bidRequest.toBuilder().app(App.builder().bundle("bundle").build())
                    .build();
            default -> bidRequest;
        };
    }

    private com.iab.openrtb.request.User givenUserWithAllIds() {
        final com.iab.openrtb.request.User user = givenUser();
        final ObjectNode optable = (ObjectNode) user.getExt().getProperty("optable");
        optable.set("id5_signature", TextNode.valueOf("id5_signature"));
        return user;
    }

    private BidRequest copyOf(BidRequest bidRequest) {
        return mapper.convertValue(mapper.valueToTree(bidRequest), BidRequest.class);
    }

    private static BidRequest applied(InvocationResult<AuctionRequestPayload> result, BidRequest bidRequest) {
        return result != null && result.action() == InvocationAction.update
                ? result.payloadUpdate().apply(AuctionRequestPayloadImpl.of(bidRequest)).bidRequest()
                : bidRequest;
    }

    private static BidRequest appliedToBidder(InvocationResult<BidderRequestPayload> result,
                                              BidRequest bidRequest) {

        return result != null && result.action() == InvocationAction.update
                ? result.payloadUpdate().apply(BidderRequestPayloadImpl.of(bidRequest)).bidRequest()
                : bidRequest;
    }

    private static void assertNoPii(BidRequest bidRequest, String description) {
        final JsonNode optable = bidRequest.getUser() != null && bidRequest.getUser().getExt() != null
                ? bidRequest.getUser().getExt().getProperty("optable")
                : null;

        if (optable != null) {
            assertThat(PII_FIELDS).as(description + " user.ext.optable: " + optable)
                    .noneMatch(optable::has);
        }
    }

    private Imp givenStoredImp() {
        final ObjectNode ext = mapper.createObjectNode();
        ext.putObject("prebid").putObject("storedrequest").put("id", "storedImpId");
        return Imp.builder().id("impId").ext(ext).build();
    }

    private Imp givenImp(String... bidders) {
        final ObjectNode ext = mapper.createObjectNode();
        final ObjectNode bidderNode = ext.putObject("prebid").putObject("bidder");
        for (String bidder : bidders) {
            bidderNode.set(bidder, mapper.valueToTree(Map.of("param", "value")));
        }
        return Imp.builder().id("impId").ext(ext).build();
    }

    /**
     * One request's worth of hooks and mocks, so that no state is shared between the cases.
     */
    private class Flow {

        private final OptableRawAuctionRequestHook rawHook;
        private final OptableTargetingProcessedAuctionRequestHook processedHook;
        private final OptableBidderRequestHook bidderHook = new OptableBidderRequestHook();
        private final ActivityInfrastructure activityInfrastructure = mock(ActivityInfrastructure.class);
        private final Timeout timeout = mock(Timeout.class);
        private final OptableTargetingProperties properties;

        Flow(HookSetup setup, Outcome outcome) {
            properties = givenOptableTargetingProperties(false);
            properties.setEnrichApp(true);
            properties.setEnrichmentPercentage(10);

            final OptableTargeting optableTargeting = mock(OptableTargeting.class);
            switch (outcome) {
                case ENRICHED, SAMPLED_OUT -> when(optableTargeting.getTargeting(any(), any(), any(), any()))
                        .thenReturn(Future.succeededFuture(givenTargetingResult()));
                case NO_DATA -> when(optableTargeting.getTargeting(any(), any(), any(), any()))
                        .thenReturn(Future.succeededFuture(givenEmptyTargetingResult()));
                case API_ERROR -> when(optableTargeting.getTargeting(any(), any(), any(), any()))
                        .thenReturn(Future.failedFuture(new RuntimeException("api error")));
                case HOOK_EXCEPTION -> when(optableTargeting.getTargeting(any(), any(), any(), any()))
                        .thenThrow(new IllegalStateException("hook exception"));
            }

            final IntSupplier randomSupplier = mock(IntSupplier.class);
            when(randomSupplier.getAsInt()).thenReturn(outcome == Outcome.SAMPLED_OUT ? 99 : 0);

            final UserFpdActivityMask userFpdActivityMask = mock(UserFpdActivityMask.class);
            when(userFpdActivityMask.maskUser(any(), anyBoolean(), anyBoolean()))
                    .thenAnswer(answer -> answer.getArgument(0));
            when(userFpdActivityMask.maskDevice(any(), anyBoolean(), anyBoolean()))
                    .thenAnswer(answer -> answer.getArgument(0));
            when(activityInfrastructure.isAllowed(any(), any())).thenReturn(true);
            when(timeout.remaining()).thenReturn(1000L);

            final OptableTargetingFlowResolver flowResolver = new OptableTargetingFlowResolver(
                    BidderEnrichmentSampler.of(AliasesResolver.of(mock(BidderCatalog.class)), randomSupplier),
                    new TargetingRequestExecutor(
                            optableTargeting, userFpdActivityMask, mock(TimeoutFactory.class), 0.01),
                    CompositeHookExecutionPlan.of(
                            setup.hasBidderHook ? givenBidderRequestHookPlan() : ExecutionPlan.empty(),
                            ExecutionPlan.empty()),
                    0.01);
            final ConfigResolver configResolver = new ConfigResolver(mapper, jsonMerger, properties);
            rawHook = new OptableRawAuctionRequestHook(configResolver, flowResolver, 0.01);
            processedHook = new OptableTargetingProcessedAuctionRequestHook(configResolver, flowResolver, 0.01);
        }

        InvocationResult<AuctionRequestPayload> callRawHook(BidRequest bidRequest) {
            final AuctionInvocationContext invocationContext = givenAuctionInvocationContext(bidRequest, null);
            return resultOf(() -> rawHook.call(AuctionRequestPayloadImpl.of(bidRequest), invocationContext));
        }

        InvocationResult<AuctionRequestPayload> callProcessedHook(BidRequest bidRequest,
                                                                  ModuleContext moduleContext) {

            final AuctionInvocationContext invocationContext =
                    givenAuctionInvocationContext(bidRequest, moduleContext);
            return resultOf(() -> processedHook.call(AuctionRequestPayloadImpl.of(bidRequest), invocationContext));
        }

        InvocationResult<BidderRequestPayload> callBidderHook(String bidder,
                                                              BidRequest bidRequest,
                                                              ModuleContext moduleContext) {

            final BidderInvocationContext invocationContext = mock(BidderInvocationContext.class);
            when(invocationContext.bidder()).thenReturn(bidder);
            when(invocationContext.moduleContext()).thenReturn(moduleContext);
            when(invocationContext.timeout()).thenReturn(timeout);
            when(invocationContext.auctionContext()).thenReturn(givenAuctionContext(bidRequest));

            return resultOf(() -> bidderHook.call(BidderRequestPayloadImpl.of(bidRequest), invocationContext));
        }

        private AuctionInvocationContext givenAuctionInvocationContext(BidRequest bidRequest,
                                                                       ModuleContext moduleContext) {

            final AuctionInvocationContext invocationContext = mock(AuctionInvocationContext.class);
            when(invocationContext.timeout()).thenReturn(timeout);
            when(invocationContext.accountConfig()).thenAnswer(invocation -> mapper.valueToTree(properties));
            when(invocationContext.auctionContext()).thenReturn(givenAuctionContext(bidRequest));
            when(invocationContext.moduleContext()).thenReturn(moduleContext);
            return invocationContext;
        }

        private AuctionContext givenAuctionContext(BidRequest bidRequest) {
            return OptablePiiCleaningFlowTest.this.givenAuctionContext(activityInfrastructure, timeout).toBuilder()
                    .bidRequest(bidRequest)
                    .build();
        }

        // the core drops the update of a hook whose future failed or that threw
        private static <T> InvocationResult<T> resultOf(Supplier<Future<InvocationResult<T>>> hookCall) {
            try {
                final Future<InvocationResult<T>> future = hookCall.get();
                return future.succeeded() ? future.result() : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
