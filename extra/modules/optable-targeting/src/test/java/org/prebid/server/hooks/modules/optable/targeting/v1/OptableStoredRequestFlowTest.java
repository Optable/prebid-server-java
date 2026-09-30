package org.prebid.server.hooks.modules.optable.targeting.v1;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Imp;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.prebid.server.activity.infrastructure.ActivityInfrastructure;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.auction.privacy.enforcement.mask.UserFpdActivityMask;
import org.prebid.server.auction.requestfactory.Ortb2ImplicitParametersResolver;
import org.prebid.server.bidder.BidderCatalog;
import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.execution.timeout.TimeoutFactory;
import org.prebid.server.hooks.execution.v1.auction.AuctionRequestPayloadImpl;
import org.prebid.server.hooks.execution.v1.bidder.BidderRequestPayloadImpl;
import org.prebid.server.hooks.modules.optable.targeting.model.ModuleContext;
import org.prebid.server.hooks.modules.optable.targeting.model.config.OptableTargetingProperties;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.AliasesResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.BidderEnrichmentSampler;
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

import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Walks a request through the raw auction request and the bidder request hooks the way the core does for
 * requests that rely on stored requests: the raw stage sees only stored request ids, the later stages see
 * the merged request.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OptableStoredRequestFlowTest extends BaseOptableTest {

    @Mock
    private OptableTargeting optableTargeting;
    @Mock
    private UserFpdActivityMask userFpdActivityMask;
    @Mock
    private ActivityInfrastructure activityInfrastructure;
    @Mock
    private AuctionRequestPayload rawPayload;
    @Mock
    private AuctionInvocationContext rawInvocationContext;
    @Mock
    private BidderInvocationContext bidderInvocationContext;
    @Mock
    private Timeout timeout;
    @Mock
    private TimeoutFactory timeoutFactory;
    @Mock
    private Ortb2ImplicitParametersResolver implicitParametersResolver;
    @Mock
    private BidderCatalog bidderCatalog;
    @Mock
    private IntSupplier randomSupplier;

    private OptableTargetingProperties properties;

    private OptableRawAuctionRequestHook rawHook;
    private OptableBidderRequestHook bidderHook;
    private InvocationResult<AuctionRequestPayload> rawResult;

    @BeforeEach
    public void setUp() {
        when(userFpdActivityMask.maskUser(any(), anyBoolean(), anyBoolean()))
                .thenAnswer(answer -> answer.getArgument(0));
        when(userFpdActivityMask.maskDevice(any(), anyBoolean(), anyBoolean()))
                .thenAnswer(answer -> answer.getArgument(0));
        when(activityInfrastructure.isAllowed(any(), any())).thenReturn(true);
        when(timeout.remaining()).thenReturn(1000L);
        when(rawInvocationContext.timeout()).thenReturn(timeout);
        when(rawInvocationContext.accountConfig()).thenAnswer(invocation -> mapper.valueToTree(properties));
        when(bidderInvocationContext.timeout()).thenReturn(timeout);
        when(optableTargeting.getTargeting(any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(givenTargetingResult()));

        properties = givenOptableTargetingProperties(false);
        properties.setEnrichApp(true);

        final OptableTargetingFlowResolver flowResolver = new OptableTargetingFlowResolver(
                BidderEnrichmentSampler.of(AliasesResolver.of(bidderCatalog), randomSupplier),
                new TargetingRequestExecutor(
                        optableTargeting, userFpdActivityMask, implicitParametersResolver, timeoutFactory, 0.01),
                0.01);
        rawHook = new OptableRawAuctionRequestHook(
                new ConfigResolver(mapper, jsonMerger, properties), flowResolver, 0.01);
        bidderHook = new OptableBidderRequestHook(flowResolver);
    }

    @Test
    public void shouldEnrichBiddersThatComeFromStoredImps() {
        // given
        final BidRequest rawRequest = givenBidRequest().toBuilder().imp(List.of(givenStoredImp())).build();

        // when
        final ModuleContext moduleContext = callRawHook(rawRequest);

        // then
        assertThat(moduleContext.isEnrichmentDeferred()).isTrue();
        verifyNoInteractions(optableTargeting);

        // when
        final BidRequest mergedRequest = givenCleaned(rawRequest).toBuilder()
                .imp(List.of(givenImp("bidderA", "bidderB")))
                .build();
        final InvocationResult<BidderRequestPayload> bidderAResult =
                callBidderHook("bidderA", mergedRequest, moduleContext);
        final InvocationResult<BidderRequestPayload> bidderBResult =
                callBidderHook("bidderB", mergedRequest, moduleContext);

        // then
        assertThat(bidderAResult.action()).isEqualTo(InvocationAction.update);
        assertThat(bidderBResult.action()).isEqualTo(InvocationAction.update);
        assertThat(moduleContext.getBiddersToEnrich()).containsExactlyInAnyOrder("bidderA", "bidderB");

        final ArgumentCaptor<BidRequest> captor = ArgumentCaptor.forClass(BidRequest.class);
        verify(optableTargeting, times(1)).getTargeting(any(), captor.capture(), any(), any());
        final ObjectNode optable = (ObjectNode) captor.getValue().getUser().getExt().getProperty("optable");
        assertThat(optable.get("email").asText()).isEqualTo("email");
        assertThat(mergedRequest.getUser().getExt().getProperty("optable")).isNull();

        final BidRequest enriched = bidderAResult.payloadUpdate()
                .apply(BidderRequestPayloadImpl.of(mergedRequest))
                .bidRequest();
        assertThat(enriched.getUser().getEids()).isNotEmpty();
    }

    @Test
    public void shouldEnrichWhenAppComesFromStoredRequest() {
        // given
        final BidRequest rawRequest = givenBidRequest().toBuilder()
                .site(null)
                .imp(List.of(givenImp("bidderA")))
                .build();

        // when
        final ModuleContext moduleContext = callRawHook(rawRequest);

        // then
        assertThat(moduleContext.isEnrichmentDeferred()).isTrue();
        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();

        // when
        final BidRequest mergedRequest = givenCleaned(rawRequest).toBuilder()
                .app(App.builder().bundle("bundle").build())
                .build();
        final InvocationResult<BidderRequestPayload> result =
                callBidderHook("bidderA", mergedRequest, moduleContext);

        // then
        assertThat(result.action()).isEqualTo(InvocationAction.update);
        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();
        verify(optableTargeting).getTargeting(any(), any(), any(), any());
    }

    @Test
    public void shouldHonourDisabledTrafficSourceOnMergedRequest() {
        // given
        properties.setEnrichApp(false);
        final BidRequest rawRequest = givenBidRequest().toBuilder()
                .site(null)
                .imp(List.of(givenImp("bidderA")))
                .build();

        // when
        final ModuleContext moduleContext = callRawHook(rawRequest);
        final BidRequest mergedRequest = givenCleaned(rawRequest).toBuilder()
                .app(App.builder().bundle("bundle").build())
                .build();
        final InvocationResult<BidderRequestPayload> result =
                callBidderHook("bidderA", mergedRequest, moduleContext);

        // then
        assertThat(result.action()).isEqualTo(InvocationAction.no_action);
        assertThat(moduleContext.isShouldSkipEnrichment()).isTrue();
        verifyNoInteractions(optableTargeting);
    }

    @Test
    public void shouldNotSampleAgainWhenSamplingRejectedAllBiddersAtRawStage() {
        // given
        properties.setEnrichmentPercentage(10);
        when(randomSupplier.getAsInt()).thenReturn(50, 0);
        final BidRequest rawRequest = givenBidRequest().toBuilder().imp(List.of(givenImp("bidderA"))).build();

        // when
        final ModuleContext moduleContext = callRawHook(rawRequest);
        final InvocationResult<BidderRequestPayload> result =
                callBidderHook("bidderA", givenCleaned(rawRequest), moduleContext);

        // then
        assertThat(moduleContext.isEnrichmentDeferred()).isFalse();
        assertThat(result.action()).isEqualTo(InvocationAction.no_action);
        verify(randomSupplier, times(1)).getAsInt();
        verifyNoInteractions(optableTargeting);
    }

    private ModuleContext callRawHook(BidRequest bidRequest) {
        when(rawPayload.bidRequest()).thenReturn(bidRequest);
        when(rawInvocationContext.auctionContext()).thenReturn(givenAuctionContext(bidRequest));

        rawResult = rawHook.call(rawPayload, rawInvocationContext).result();
        return (ModuleContext) rawResult.moduleContext();
    }

    private InvocationResult<BidderRequestPayload> callBidderHook(String bidder,
                                                                  BidRequest mergedRequest,
                                                                  ModuleContext moduleContext) {

        when(bidderInvocationContext.bidder()).thenReturn(bidder);
        when(bidderInvocationContext.moduleContext()).thenReturn(moduleContext);
        when(bidderInvocationContext.auctionContext()).thenReturn(givenAuctionContext(mergedRequest));

        return bidderHook.call(BidderRequestPayloadImpl.of(mergedRequest), bidderInvocationContext).result();
    }

    private AuctionContext givenAuctionContext(BidRequest bidRequest) {
        return givenAuctionContext(activityInfrastructure, timeout).toBuilder().bidRequest(bidRequest).build();
    }

    private BidRequest givenCleaned(BidRequest bidRequest) {
        return rawResult.payloadUpdate().apply(AuctionRequestPayloadImpl.of(bidRequest)).bidRequest();
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
}
