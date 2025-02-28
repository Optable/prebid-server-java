package org.prebid.server.hooks.modules.optable.targeting.v1.core;

import org.prebid.server.auction.model.AuctionContext;
import org.prebid.server.privacy.model.PrivacyContext;

import java.util.Optional;

public class IpResolver {

    public String resolveIp(AuctionContext auctionContext) {
        return Optional.ofNullable(auctionContext)
                .map(AuctionContext::getPrivacyContext)
                .map(PrivacyContext::getIpAddress)
                .orElse(null);
    }
}
