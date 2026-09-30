package org.prebid.server.hooks.modules.optable.targeting.v1;

import com.iab.openrtb.request.BidRequest;
import io.vertx.core.Future;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.prebid.server.activity.infrastructure.ActivityInfrastructure;
import org.prebid.server.auction.privacy.enforcement.mask.UserFpdActivityMask;
import org.prebid.server.execution.timeout.Timeout;
import org.prebid.server.execution.timeout.TimeoutFactory;
import org.prebid.server.hooks.modules.optable.targeting.model.ModuleContext;
import org.prebid.server.hooks.modules.optable.targeting.model.config.OptableTargetingProperties;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.BidderEnrichmentSampler;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.ConfigResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.OptableTargetingFlowResolver;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.TargetingRequestExecutor;
import org.prebid.server.hooks.modules.optable.targeting.v1.core.OptableTargeting;
import org.prebid.server.hooks.v1.InvocationResult;
import org.prebid.server.hooks.v1.auction.AuctionInvocationContext;
import org.prebid.server.hooks.v1.auction.AuctionRequestPayload;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(VertxExtension.class)
public class OptableRawAuctionRequestHookTest extends BaseOptableTest {

    @Mock
    private OptableTargeting optableTargeting;
    @Mock
    private UserFpdActivityMask userFpdActivityMask;
    @Mock
    private AuctionRequestPayload auctionRequestPayload;
    @Mock
    private ActivityInfrastructure activityInfrastructure;
    @Mock
    private AuctionInvocationContext invocationContext;
    @Mock
    private Timeout timeout;
    @Mock
    private Timeout apiTimeout;
    @Mock
    private Timeout auctionTimeout;
    @Mock
    private TimeoutFactory timeoutFactory;
    @Mock
    private BidderEnrichmentSampler bidderEnrichmentSampler;

    private ConfigResolver configResolver;
    private TargetingRequestExecutor targetingRequestExecutor;
    private OptableRawAuctionRequestHook target;

    @BeforeEach
    public void setUp() {
        when(userFpdActivityMask.maskDevice(any(), anyBoolean(), anyBoolean()))
                .thenAnswer(answer -> answer.getArgument(0));
        configResolver = new ConfigResolver(mapper, jsonMerger, givenOptableTargetingProperties(false));
        targetingRequestExecutor = new TargetingRequestExecutor(
                optableTargeting, userFpdActivityMask, timeoutFactory, 0.01);
        target = new OptableRawAuctionRequestHook(
                configResolver, givenEarlyOptableCallResolver(), 0.01);
        when(invocationContext.auctionContext()).thenReturn(givenAuctionContext(activityInfrastructure, timeout));
        when(invocationContext.timeout()).thenReturn(timeout);
        when(activityInfrastructure.isAllowed(any(), any())).thenReturn(true);
        when(timeout.remaining()).thenReturn(1000L);
        when(bidderEnrichmentSampler.hasBidders(any())).thenReturn(true);
    }

    private OptableTargetingFlowResolver givenEarlyOptableCallResolver() {
        return new OptableTargetingFlowResolver(
                bidderEnrichmentSampler,
                targetingRequestExecutor,
                0.01);
    }

    @Test
    public void shouldHaveRightCode() {
        // when and then
        assertThat(target.code()).isEqualTo("optable-targeting-raw-auction-request-hook");
    }

    @SneakyThrows
    @Test
    public void shouldInjectEarlyNetworkCallToModuleContext(VertxTestContext vertxTestContext) {
        // given
        when(invocationContext.accountConfig())
                .thenReturn(givenAccountConfig("key", "tenant", "origin", true));
        when(auctionRequestPayload.bidRequest()).thenReturn(givenBidRequest());
        when(optableTargeting.getTargeting(any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(givenTargetingResult()));
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of("bidder"));

        // when
        final Future<InvocationResult<AuctionRequestPayload>> result =
                target.call(auctionRequestPayload, invocationContext);

        // then
        assertThat(result).isNotNull();
        result.map(res -> (ModuleContext) res.moduleContext())
                .onComplete(cxt -> {
                    vertxTestContext.verify(() -> {
                        final ModuleContext moduleContext = cxt.result();
                        assertThat(moduleContext.getOptableTargetingCall()).isNotNull();
                        assertThat(moduleContext.getOptableTargetingCall().result()).isNotNull();
                        assertThat(moduleContext.isEnrichmentDeferred()).isFalse();
                    });
                    vertxTestContext.completeNow();
                });
    }

    @SneakyThrows
    @Test
    public void shouldNotInjectEarlyNetworkCallToModuleContextWhenOriginIsAbsentInAccountConfiguration(
            VertxTestContext vertxTestContext) {

        // given
        when(invocationContext.accountConfig())
                .thenReturn(givenAccountConfig("key", "tenant", null, true));
        when(auctionRequestPayload.bidRequest()).thenReturn(givenBidRequest());
        when(optableTargeting.getTargeting(any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(givenTargetingResult()));

        configResolver = new ConfigResolver(
                mapper, jsonMerger, givenOptableTargetingProperties("key", "tenant", null, true));
        target = new OptableRawAuctionRequestHook(
                configResolver, givenEarlyOptableCallResolver(), 0.01);

        // when
        final Future<InvocationResult<AuctionRequestPayload>> result =
                target.call(auctionRequestPayload, invocationContext);

        // then
        assertThat(result).isNotNull();
        result.map(res -> (ModuleContext) res.moduleContext())
                .onComplete(cxt -> {
                    vertxTestContext.verify(() -> {
                        final ModuleContext moduleContext = cxt.result();
                        assertThat(moduleContext.getOptableTargetingCall()).isNull();
                        assertThat(moduleContext.isEarlyNetworkCallEnabled()).isTrue();
                        assertThat(moduleContext.isEnrichmentDeferred()).isFalse();
                    });
                    vertxTestContext.completeNow();
                });
    }

    @SneakyThrows
    @Test
    public void shouldDeferTargetingCallWhenRequestHasNeitherSiteNorApp(VertxTestContext vertxTestContext) {
        // given
        when(invocationContext.accountConfig())
                .thenReturn(givenAccountConfig("key", "tenant", "origin", true));
        final BidRequest bidRequestWithoutTrafficSource = givenBidRequest(bidRequestCustomizer ->
                bidRequestCustomizer.site(null).app(null));
        when(auctionRequestPayload.bidRequest()).thenReturn(bidRequestWithoutTrafficSource);
        when(invocationContext.auctionContext()).thenReturn(
                givenAuctionContext(activityInfrastructure, timeout)
                        .toBuilder()
                        .bidRequest(bidRequestWithoutTrafficSource)
                        .build());

        configResolver = new ConfigResolver(mapper, jsonMerger, givenOptableTargetingProperties(false));
        target = new OptableRawAuctionRequestHook(
                configResolver, givenEarlyOptableCallResolver(), 0.01);

        // when
        final Future<InvocationResult<AuctionRequestPayload>> result =
                target.call(auctionRequestPayload, invocationContext);

        // then
        assertThat(result).isNotNull();
        result.map(res -> (ModuleContext) res.moduleContext())
                .onComplete(cxt -> {
                    vertxTestContext.verify(() -> {
                        final ModuleContext moduleContext = cxt.result();
                        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();
                        assertThat(moduleContext.getOptableTargetingCall()).isNull();
                        assertThat(moduleContext.isEnrichmentDeferred()).isTrue();
                    });
                    vertxTestContext.completeNow();
                });
    }

    @SneakyThrows
    @Test
    public void shouldNotInjectEarlyNetworkCallToModuleContextWhenNoBiddersToEnrich(
            VertxTestContext vertxTestContext) {

        // given
        when(invocationContext.accountConfig())
                .thenReturn(givenAccountConfig("key", "tenant", "origin", true));
        when(auctionRequestPayload.bidRequest()).thenReturn(givenBidRequest());
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of());

        // when
        final Future<InvocationResult<AuctionRequestPayload>> result =
                target.call(auctionRequestPayload, invocationContext);

        // then
        assertThat(result).isNotNull();
        result.map(res -> (ModuleContext) res.moduleContext())
                .onComplete(cxt -> {
                    vertxTestContext.verify(() -> {
                        final ModuleContext moduleContext = cxt.result();
                        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();
                        assertThat(moduleContext.getOptableTargetingCall()).isNull();
                        assertThat(moduleContext.isEnrichmentDeferred()).isFalse();
                    });
                    vertxTestContext.completeNow();
                });
    }

    @Test
    public void shouldSkipEnrichmentWhenTrafficSourceIsDisabled() {
        // given
        final OptableTargetingProperties properties = givenOptableTargetingProperties(false);
        properties.setEnrichWeb(false);
        target = new OptableRawAuctionRequestHook(
                new ConfigResolver(mapper, jsonMerger, properties), givenEarlyOptableCallResolver(), 0.01);
        when(invocationContext.accountConfig()).thenReturn(mapper.valueToTree(properties));
        when(auctionRequestPayload.bidRequest()).thenReturn(givenBidRequest());
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of("bidder"));

        // when
        final Future<InvocationResult<AuctionRequestPayload>> result =
                target.call(auctionRequestPayload, invocationContext);

        // then
        final ModuleContext moduleContext = (ModuleContext) result.result().moduleContext();
        assertThat(moduleContext.isShouldSkipEnrichment()).isTrue();
        assertThat(moduleContext.isEnrichmentDeferred()).isFalse();
        assertThat(moduleContext.getOptableTargetingCall()).isNull();
    }

    @Test
    public void shouldDeferTargetingCallAndKeepOptableIdsWhenRequestHasNoBidders() {
        // given
        when(invocationContext.accountConfig())
                .thenReturn(givenAccountConfig("key", "tenant", "origin", true));
        final BidRequest bidRequest = givenBidRequest();
        when(auctionRequestPayload.bidRequest()).thenReturn(bidRequest);
        when(bidderEnrichmentSampler.hasBidders(any())).thenReturn(false);

        // when
        final Future<InvocationResult<AuctionRequestPayload>> result =
                target.call(auctionRequestPayload, invocationContext);

        // then
        final ModuleContext moduleContext = (ModuleContext) result.result().moduleContext();
        assertThat(moduleContext.isEnrichmentDeferred()).isTrue();
        assertThat(moduleContext.isShouldSkipEnrichment()).isFalse();
        assertThat(moduleContext.getOptableTargetingCall()).isNull();
        assertThat(moduleContext.getExtUserOptable().get("email").asText()).isEqualTo("email");

        final BidRequest cleaned = result.result().payloadUpdate().apply(auctionRequestPayload).bidRequest();
        assertThat(cleaned.getUser().getExt().getProperty("optable")).isNull();
    }

    @Test
    public void shouldBoundEarlyCallByApiTimeoutWhenConfigured() {
        // given
        final OptableTargetingProperties properties = givenOptableTargetingProperties(false);
        properties.setApiTimeout(300L);
        when(invocationContext.accountConfig()).thenReturn(mapper.valueToTree(properties));
        when(auctionRequestPayload.bidRequest()).thenReturn(givenBidRequest());
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of("bidder"));
        when(timeoutFactory.create(300L)).thenReturn(apiTimeout);

        // when
        target.call(auctionRequestPayload, invocationContext);

        // then
        verify(optableTargeting).getTargeting(any(), any(), any(), same(apiTimeout));
    }

    @Test
    public void shouldBoundEarlyCallByAuctionTimeoutWhenApiTimeoutIsNotConfigured() {
        // given
        when(invocationContext.accountConfig())
                .thenReturn(givenAccountConfig("key", "tenant", "origin", true));
        when(invocationContext.auctionContext()).thenReturn(
                givenAuctionContext(activityInfrastructure, auctionTimeout));
        when(auctionRequestPayload.bidRequest()).thenReturn(givenBidRequest());
        when(bidderEnrichmentSampler.sample(any(), any())).thenReturn(Set.of("bidder"));

        // when
        target.call(auctionRequestPayload, invocationContext);

        // then
        verify(optableTargeting).getTargeting(any(), any(), any(), same(auctionTimeout));
    }
}
