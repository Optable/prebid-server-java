package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.hooks.execution.model.EndpointExecutionPlan;
import org.prebid.server.hooks.execution.model.ExecutionGroup;
import org.prebid.server.hooks.execution.model.ExecutionPlan;
import org.prebid.server.hooks.execution.model.HookExecutionContext;
import org.prebid.server.hooks.execution.model.HookHttpEndpoint;
import org.prebid.server.hooks.execution.model.Stage;
import org.prebid.server.hooks.execution.model.StageExecutionPlan;
import org.prebid.server.hooks.modules.optable.targeting.v1.OptableBidderRequestHook;
import org.prebid.server.settings.model.Account;
import org.prebid.server.settings.model.AccountHooksConfiguration;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the hooks configured for a request the way the core does: the host execution plan combined with the
 * account execution plan, or with the default account execution plan when the account has none, for the endpoint
 * of the request.
 */
public class CompositeHookExecutionPlan {

    private final ExecutionPlan hostExecutionPlan;
    private final ExecutionPlan defaultAccountExecutionPlan;

    private CompositeHookExecutionPlan(ExecutionPlan hostExecutionPlan, ExecutionPlan defaultAccountExecutionPlan) {
        this.hostExecutionPlan = Objects.requireNonNull(hostExecutionPlan);
        this.defaultAccountExecutionPlan = Objects.requireNonNull(defaultAccountExecutionPlan);
    }

    public static CompositeHookExecutionPlan of(ExecutionPlan hostExecutionPlan,
                                                ExecutionPlan defaultAccountExecutionPlan) {

        return new CompositeHookExecutionPlan(
                Objects.requireNonNullElse(hostExecutionPlan, ExecutionPlan.empty()),
                Objects.requireNonNullElse(defaultAccountExecutionPlan, ExecutionPlan.empty()));
    }

    public boolean hasBidderRequestHook(AuctionContext auctionContext) {
        final HookHttpEndpoint endpoint = Optional.ofNullable(auctionContext)
                .map(AuctionContext::getHookExecutionContext)
                .map(HookExecutionContext::getEndpoint)
                .orElse(HookHttpEndpoint.POST_AUCTION);

        return hasHook(hostExecutionPlan, endpoint)
                || hasHook(accountExecutionPlan(auctionContext != null ? auctionContext.getAccount() : null), endpoint);
    }

    private ExecutionPlan accountExecutionPlan(Account account) {
        return Optional.ofNullable(account)
                .map(Account::getHooks)
                .map(AccountHooksConfiguration::getExecutionPlan)
                .orElse(defaultAccountExecutionPlan);
    }

    private static boolean hasHook(ExecutionPlan executionPlan, HookHttpEndpoint endpoint) {
        return Optional.ofNullable(executionPlan.getEndpoints())
                .map(endpoints -> endpoints.get(endpoint))
                .map(EndpointExecutionPlan::getStages)
                .map(stages -> stages.get(Stage.bidder_request))
                .map(StageExecutionPlan::getGroups)
                .stream()
                .flatMap(Collection::stream)
                .map(ExecutionGroup::getHookSequence)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .anyMatch(hookId -> OptableBidderRequestHook.CODE.equals(hookId.getHookImplCode()));
    }
}
