package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import org.junit.jupiter.api.Test;
import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.hooks.execution.model.EndpointExecutionPlan;
import org.prebid.server.hooks.execution.model.ExecutionGroup;
import org.prebid.server.hooks.execution.model.ExecutionPlan;
import org.prebid.server.hooks.execution.model.HookExecutionContext;
import org.prebid.server.hooks.execution.model.HookHttpEndpoint;
import org.prebid.server.hooks.execution.model.HookId;
import org.prebid.server.hooks.execution.model.Stage;
import org.prebid.server.hooks.execution.model.StageExecutionPlan;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountHooksConfiguration;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeHookExecutionPlanTest {

    private static final HookId BIDDER_REQUEST_HOOK =
            HookId.of("optable-targeting", "optable-targeting-bidder-request-hook");

    @Test
    void hasBidderRequestHookShouldFindHookInHostPlan() {
        // given
        final CompositeHookExecutionPlan target = CompositeHookExecutionPlan.of(
                givenPlan(HookHttpEndpoint.POST_AUCTION, BIDDER_REQUEST_HOOK), null);

        // when and then
        assertThat(target.hasBidderRequestHook(givenAuctionContext(HookHttpEndpoint.POST_AUCTION, null))).isTrue();
    }

    @Test
    void hasBidderRequestHookShouldFindHookInAccountPlan() {
        // given
        final CompositeHookExecutionPlan target = CompositeHookExecutionPlan.of(null, null);
        final ExecutionPlan accountPlan = givenPlan(HookHttpEndpoint.POST_AUCTION, BIDDER_REQUEST_HOOK);

        // when and then
        assertThat(target.hasBidderRequestHook(givenAuctionContext(HookHttpEndpoint.POST_AUCTION, accountPlan)))
                .isTrue();
    }

    @Test
    void hasBidderRequestHookShouldFallBackToDefaultAccountPlanWhenAccountHasNone() {
        // given
        final CompositeHookExecutionPlan target = CompositeHookExecutionPlan.of(
                null, givenPlan(HookHttpEndpoint.POST_AUCTION, BIDDER_REQUEST_HOOK));

        // when and then
        assertThat(target.hasBidderRequestHook(givenAuctionContext(HookHttpEndpoint.POST_AUCTION, null))).isTrue();
    }

    @Test
    void hasBidderRequestHookShouldIgnoreDefaultAccountPlanWhenAccountHasOne() {
        // given
        final CompositeHookExecutionPlan target = CompositeHookExecutionPlan.of(
                null, givenPlan(HookHttpEndpoint.POST_AUCTION, BIDDER_REQUEST_HOOK));

        // when and then
        assertThat(target.hasBidderRequestHook(
                givenAuctionContext(HookHttpEndpoint.POST_AUCTION, ExecutionPlan.empty()))).isFalse();
    }

    @Test
    void hasBidderRequestHookShouldLookAtEndpointOfRequest() {
        // given
        final CompositeHookExecutionPlan target = CompositeHookExecutionPlan.of(
                givenPlan(HookHttpEndpoint.POST_AUCTION, BIDDER_REQUEST_HOOK), null);

        // when and then
        assertThat(target.hasBidderRequestHook(givenAuctionContext(HookHttpEndpoint.AMP, null))).isFalse();
    }

    @Test
    void hasBidderRequestHookShouldFindHookInAnyGroupOfStage() {
        // given
        final StageExecutionPlan stage = StageExecutionPlan.of(List.of(
                ExecutionGroup.of(10L, List.of(HookId.of("other", "other-hook"))),
                ExecutionGroup.of(20L, List.of(BIDDER_REQUEST_HOOK))));
        final ExecutionPlan plan = ExecutionPlan.of(null, Map.of(
                HookHttpEndpoint.AMP, EndpointExecutionPlan.of(Map.of(Stage.bidder_request, stage))));
        final CompositeHookExecutionPlan target = CompositeHookExecutionPlan.of(plan, null);

        // when and then
        assertThat(target.hasBidderRequestHook(givenAuctionContext(HookHttpEndpoint.AMP, null))).isTrue();
    }

    private static ExecutionPlan givenPlan(HookHttpEndpoint endpoint, HookId hookId) {
        final StageExecutionPlan stage = StageExecutionPlan.of(List.of(ExecutionGroup.of(10L, List.of(hookId))));
        return ExecutionPlan.of(null, Map.of(endpoint, EndpointExecutionPlan.of(Map.of(Stage.bidder_request, stage))));
    }

    private static AuctionContext givenAuctionContext(HookHttpEndpoint endpoint, ExecutionPlan accountPlan) {
        return AuctionContext.builder()
                .hookExecutionContext(HookExecutionContext.of(endpoint))
                .account(Account.builder()
                        .id("accountId")
                        .hooks(AccountHooksConfiguration.of(accountPlan, null, null))
                        .build())
                .build();
    }
}
