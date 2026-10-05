package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import com.iab.openrtb.request.BidRequest;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.prebid.server.hooks.execution.model.ExecutionPlan;
import org.prebid.server.hooks.modules.optable.targeting.model.ModuleContext;
import org.prebid.server.hooks.modules.optable.targeting.model.openrtb.TargetingResult;
import org.prebid.server.hooks.modules.optable.targeting.v1.BaseOptableTest;
import org.prebid.server.hooks.v1.InvocationAction;
import org.prebid.server.hooks.v1.InvocationResult;
import org.prebid.server.hooks.v1.InvocationStatus;
import org.prebid.server.hooks.v1.auction.AuctionInvocationContext;
import org.prebid.server.hooks.v1.auction.AuctionRequestPayload;
import org.prebid.server.proto.openrtb.ext.request.ExtRequest;
import org.prebid.server.proto.openrtb.ext.request.ExtRequestPrebid;
import org.prebid.server.proto.openrtb.ext.request.ExtStoredRequest;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class OptableTargetingFlowResolverTest extends BaseOptableTest {

    @Mock
    private BidderEnrichmentSampler bidderEnrichmentSampler;

    @Mock
    private TargetingRequestExecutor targetingRequestExecutor;

    @Mock
    private AuctionRequestPayload auctionRequestPayload;

    @Mock
    private AuctionInvocationContext invocationContext;

    private OptableTargetingFlowResolver target;

    @BeforeEach
    public void setUp() {
        target = new OptableTargetingFlowResolver(
                bidderEnrichmentSampler,
                targetingRequestExecutor,
                CompositeHookExecutionPlan.of(ExecutionPlan.empty(), ExecutionPlan.empty()),
                0.01);
    }

    @Test
    public void resolveAsyncOptableTargetingFlowShouldProceedWhenBidRequestHasNoStoredRequestAndNoStoredImps() {
        // given
        final BidRequest bidRequest = givenBidRequest(request -> request.imp(List.of(
                givenImp(imp -> imp.ext(givenPrebidBidderExt("bidderA"))))));
        givenPayload(bidRequest);
        final Future<TargetingResult> targetingResultFuture = Future.succeededFuture(givenTargetingResult());
        when(bidderEnrichmentSampler.hasBidders(any())).thenReturn(true);
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of("bidder"));
        when(targetingRequestExecutor.makeRequest(any(), any(), any(), anyBoolean())).thenReturn(targetingResultFuture);
        final ModuleContext moduleContext = new ModuleContext();

        // when
        final Future<InvocationResult<AuctionRequestPayload>> future = target.resolveAsyncOptableTargetingFlow(
                moduleContext, auctionRequestPayload, invocationContext, givenOptableTargetingProperties(false));

        // then
        assertThat(future.succeeded()).isTrue();

        final InvocationResult<AuctionRequestPayload> result = future.result();
        assertThat(result).isNotNull()
                .returns(InvocationStatus.success, InvocationResult::status)
                .returns(InvocationAction.update, InvocationResult::action)
                .extracting(InvocationResult::errors).isNull();
        assertThat(result.payloadUpdate()).isInstanceOf(AuctionRequestCleaner.class);
        assertThat(moduleContext.getBiddersToEnrich()).containsExactly("bidder");
        assertThat(moduleContext.getOptableTargetingCall()).isSameAs(targetingResultFuture);
        assertThat(moduleContext.isEarlyCallInitializationCompleted()).isTrue();
    }

    @Test
    public void resolveAsyncOptableTargetingFlowShouldDeferWhenBidRequestReferencesStoredRequest() {
        // given
        final BidRequest bidRequest = givenBidRequest(request -> request
                .imp(List.of(givenImp(imp -> imp.ext(givenPrebidBidderExt("bidderA")))))
                .ext(givenStoredRequestExt()));
        givenPayload(bidRequest);
        final ModuleContext moduleContext = new ModuleContext();

        // when
        final Future<InvocationResult<AuctionRequestPayload>> future = target.resolveAsyncOptableTargetingFlow(
                moduleContext, auctionRequestPayload, invocationContext, givenOptableTargetingProperties(false));

        // then
        assertThat(future.succeeded()).isTrue();

        final InvocationResult<AuctionRequestPayload> result = future.result();
        assertThat(result).isNotNull()
                .returns(InvocationStatus.success, InvocationResult::status)
                .returns(InvocationAction.update, InvocationResult::action)
                .extracting(InvocationResult::errors).isNull();
        assertThat(result.payloadUpdate()).isInstanceOf(AuctionRequestCleaner.class);
        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();
        assertThat(moduleContext.getBiddersToEnrich()).isNull();
        assertThat(moduleContext.getOptableTargetingCall()).isNull();
        assertThat(moduleContext.isEarlyCallInitializationCompleted()).isFalse();
    }

    @Test
    public void resolveAsyncOptableTargetingFlowShouldDeferWhenAnyImpReferencesStoredImp() {
        // given
        final BidRequest bidRequest = givenBidRequest(request -> request.imp(List.of(
                givenImp(imp -> imp.ext(givenPrebidBidderExt("bidderA"))),
                givenImp(imp -> imp.ext(givenStoredImpExt())))));
        givenPayload(bidRequest);
        final ModuleContext moduleContext = new ModuleContext();

        // when
        final Future<InvocationResult<AuctionRequestPayload>> future = target.resolveAsyncOptableTargetingFlow(
                moduleContext, auctionRequestPayload, invocationContext, givenOptableTargetingProperties(false));

        // then
        assertThat(future.succeeded()).isTrue();

        final InvocationResult<AuctionRequestPayload> result = future.result();
        assertThat(result).isNotNull()
                .returns(InvocationStatus.success, InvocationResult::status)
                .returns(InvocationAction.update, InvocationResult::action)
                .extracting(InvocationResult::errors).isNull();
        assertThat(result.payloadUpdate()).isInstanceOf(AuctionRequestCleaner.class);
        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();
        assertThat(moduleContext.getBiddersToEnrich()).isNull();
        assertThat(moduleContext.getOptableTargetingCall()).isNull();
        assertThat(moduleContext.isEarlyCallInitializationCompleted()).isFalse();
    }

    @Test
    public void resolveAsyncOptableTargetingFlowShouldCleanAndNotDeferWhenNoBiddersToEnrich() {
        // given
        final BidRequest bidRequest = givenBidRequest(request -> request.imp(List.of(
                givenImp(imp -> imp.ext(givenPrebidBidderExt("bidderA"))))));
        givenPayload(bidRequest);
        when(bidderEnrichmentSampler.hasBidders(any())).thenReturn(true);
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of());
        final ModuleContext moduleContext = new ModuleContext();

        // when
        final Future<InvocationResult<AuctionRequestPayload>> future = target.resolveAsyncOptableTargetingFlow(
                moduleContext, auctionRequestPayload, invocationContext, givenOptableTargetingProperties(false));

        // then
        assertThat(future.succeeded()).isTrue();

        final InvocationResult<AuctionRequestPayload> result = future.result();
        assertThat(result).isNotNull()
                .returns(InvocationStatus.success, InvocationResult::status)
                .returns(InvocationAction.update, InvocationResult::action)
                .extracting(InvocationResult::errors).isNull();
        assertThat(result.payloadUpdate()).isInstanceOf(AuctionRequestCleaner.class);
        assertThat(moduleContext.getBiddersToEnrich()).isNull();
        assertThat(moduleContext.getOptableTargetingCall()).isNull();
        assertThat(moduleContext.isEarlyCallInitializationCompleted()).isTrue();
    }

    private void givenPayload(BidRequest bidRequest) {
        when(auctionRequestPayload.bidRequest()).thenReturn(bidRequest);
    }

    private ExtRequest givenStoredRequestExt() {
        return ExtRequest.of(ExtRequestPrebid.builder()
                .storedrequest(ExtStoredRequest.of("storedRequestId"))
                .build());
    }
}
