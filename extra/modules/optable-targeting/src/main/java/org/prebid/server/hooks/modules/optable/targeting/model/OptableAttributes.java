package org.prebid.server.hooks.modules.optable.targeting.model;

import lombok.Builder;
import lombok.Value;

import java.util.Set;

@Value
@Builder(toBuilder = true)
public class OptableAttributes {

    String reg;

    String gpp;

    Set<Integer> gppSid;

    String tcf;

    String ip;

    Long timeout;

    public static OptableAttributes of(String reg) {
        return OptableAttributes.builder().reg(reg).build();
    }
}
