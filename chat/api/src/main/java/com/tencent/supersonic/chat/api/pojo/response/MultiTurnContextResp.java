package com.tencent.supersonic.chat.api.pojo.response;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Structured multi-turn context exposed to the chat UI. */
@Data
public class MultiTurnContextResp {
    private int maxRounds = 10;
    private int usedRounds;
    private String operation = "NONE";
    private boolean expired;
    private boolean truncated;
    private String rewrittenQuery;
    private List<Long> sourceQueryIds = new ArrayList<>();
    private List<Turn> turns = new ArrayList<>();

    @Data
    @Builder
    public static class Turn {
        private Long queryId;
        private String question;
        private String s2sql;
        private List<String> metrics;
        private List<String> dimensions;
        private List<String> filters;
        private String dateInfo;
        private List<String> orders;
        private String granularity;
    }
}
