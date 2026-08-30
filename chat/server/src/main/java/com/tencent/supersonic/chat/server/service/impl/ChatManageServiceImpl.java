package com.tencent.supersonic.chat.server.service.impl;

import com.alibaba.fastjson.JSONObject;
import com.github.pagehelper.PageInfo;
import com.tencent.supersonic.chat.api.pojo.enums.MemoryReviewResult;
import com.tencent.supersonic.chat.api.pojo.enums.MemoryStatus;
import com.tencent.supersonic.chat.api.pojo.request.*;
import com.tencent.supersonic.chat.api.pojo.response.ChatParseResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResp;
import com.tencent.supersonic.chat.api.pojo.response.QueryResult;
import com.tencent.supersonic.chat.api.pojo.response.ShowCaseResp;
import com.tencent.supersonic.chat.server.agent.Agent;
import com.tencent.supersonic.chat.server.config.BankPlanSessionWarmupCoordinator;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatParseDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.ChatQueryDO;
import com.tencent.supersonic.chat.server.persistence.dataobject.QueryDO;
import com.tencent.supersonic.chat.server.persistence.repository.ChatQueryRepository;
import com.tencent.supersonic.chat.server.persistence.repository.ChatRepository;
import com.tencent.supersonic.chat.server.pojo.ChatMemory;
import com.tencent.supersonic.chat.server.security.ChatObjectAccessPolicy;
import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.chat.server.service.ChatManageService;
import com.tencent.supersonic.chat.server.service.MemoryService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.common.util.JsonUtil;
import com.tencent.supersonic.headless.api.pojo.SemanticParseInfo;
import com.tencent.supersonic.headless.server.security.audit.AuditEventPublisher;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEvent;
import com.tencent.supersonic.headless.server.security.audit.model.AuditEventType;
import com.tencent.supersonic.headless.server.security.audit.model.AuditOutcome;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatManageServiceImpl implements ChatManageService {

    private static final Set<String> SUPPORTED_CHART_TYPES =
            Set.of("KPI_CARD", "TABLE", "LINE", "BAR", "PIE", "COMBO");
    private static final Set<String> CHART_FEEDBACK_SOURCES =
            Set.of("CHART_SELECTOR", "DATA_VIEW_TOGGLE");
    private static final int ONLINE_AGENT_STATUS = 1;
    private static final String LEGACY_CHAT_NAME = "新问答对话";

    @Autowired
    private ChatRepository chatRepository;
    @Autowired
    private ChatQueryRepository chatQueryRepository;
    @Autowired
    private AgentService agentService;
    @Autowired
    private MemoryService memoryService;
    @Autowired
    private AuditEventPublisher auditEventPublisher;
    @Autowired
    private BankPlanSessionWarmupCoordinator bankPlanSessionWarmupCoordinator;
    private final ChatObjectAccessPolicy objectAccessPolicy = new ChatObjectAccessPolicy();

    @Override
    public Long addChat(User user, String chatName, Integer agentId) {
        Agent agent = getAuthorizedOnlineAgent(user, agentId);
        ChatDO chatDO = new ChatDO();
        chatDO.setChatName(normalizeChatName(chatName, agent.getName()));
        chatDO.setCreator(user.getName());
        chatDO.setCreateTime(getCurrentTime());
        chatDO.setIsDelete(0);
        chatDO.setLastTime(getCurrentTime());
        chatDO.setLastQuestion("Hello, welcome to using supersonic");
        chatDO.setIsTop(0);
        chatDO.setAgentId(agent.getId());
        Long chatId = chatRepository.createChat(chatDO);
        if (chatId != null && bankPlanSessionWarmupCoordinator != null) {
            bankPlanSessionWarmupCoordinator.warmAsync(chatId, agent);
        }
        return chatId;
    }

    private Agent getAuthorizedOnlineAgent(User user, Integer agentId) {
        if (user == null || StringUtils.isBlank(user.getName()) || agentId == null) {
            throw new InvalidPermissionException(
                    "Creating a chat requires an authenticated user and an agent");
        }
        Agent agent = agentService.getAgents(user, AuthType.VIEWER).stream()
                .filter(candidate -> Objects.equals(agentId, candidate.getId())).findFirst()
                .orElseThrow(() -> new InvalidPermissionException(
                        "No permission to access agent " + agentId));
        if (!Objects.equals(ONLINE_AGENT_STATUS, agent.getStatus())) {
            throw new InvalidPermissionException("Agent is offline: " + agentId);
        }
        return agent;
    }

    private String normalizeChatName(String chatName, String agentName) {
        return StringUtils.isBlank(chatName) || LEGACY_CHAT_NAME.equals(chatName) ? agentName
                : chatName;
    }

    @Override
    public List<ChatDO> getAll(String userName, Integer agentId) {
        return chatRepository.getAll(userName, agentId);
    }

    @Override
    public boolean updateChatName(Long chatId, String chatName, String userName) {
        return chatRepository.updateChatName(chatId, chatName, getCurrentTime(), userName);
    }

    @Override
    public boolean updateFeedback(Long id, Integer score, String feedback, User user) {
        checkQueryAccess(id, user);
        QueryDO intelligentQueryDO = new QueryDO();
        intelligentQueryDO.setId(id);
        intelligentQueryDO.setQuestionId(id);
        intelligentQueryDO.setScore(score);
        intelligentQueryDO.setFeedback(feedback);

        // enable or disable memory based on user feedback
        if (score >= 5 || score <= 1) {
            ChatMemoryFilter memoryFilter = ChatMemoryFilter.builder().queryId(id).build();
            List<ChatMemory> memories = memoryService.getMemories(memoryFilter);
            memories.forEach(m -> {
                MemoryStatus status = score >= 5 ? MemoryStatus.ENABLED : MemoryStatus.DISABLED;
                MemoryReviewResult reviewResult =
                        score >= 5 ? MemoryReviewResult.POSITIVE : MemoryReviewResult.NEGATIVE;
                ChatMemoryUpdateReq memoryUpdateReq = ChatMemoryUpdateReq.builder().id(m.getId())
                        .status(status).humanReviewRet(reviewResult)
                        .humanReviewCmt("Reviewed as per user feedback").build();
                memoryService.updateMemory(memoryUpdateReq, User.getDefaultUser());
            });
        }

        return chatRepository.updateFeedback(intelligentQueryDO);
    }

    @Override
    public void recordChartFeedback(ChartFeedbackReq feedback, User user) {
        String recommended = normalizeChartType(feedback.getRecommendedChart());
        String selected = normalizeChartType(feedback.getSelectedChart());
        String source = StringUtils.upperCase(StringUtils.trim(feedback.getSource()));
        if (!SUPPORTED_CHART_TYPES.contains(recommended)
                || !SUPPORTED_CHART_TYPES.contains(selected)) {
            throw new IllegalArgumentException("Unsupported chart type");
        }
        if (!CHART_FEEDBACK_SOURCES.contains(source)) {
            throw new IllegalArgumentException("Unsupported chart feedback source");
        }
        checkQueryAccess(feedback.getQueryId(), user);
        auditEventPublisher.publishRequired(AuditEvent.builder()
                .eventType(AuditEventType.CHART_VISUALIZATION_CHANGED).outcome(AuditOutcome.SUCCESS)
                .reasonCode("USER_CHART_SELECTION").queryId(feedback.getQueryId())
                .resourceType("CHAT_QUERY").resourceId(String.valueOf(feedback.getQueryId()))
                .metadata(Map.of("recommendedChart", recommended, "selectedChart", selected,
                        "feedbackSource", source))
                .build(), user);
    }

    private String normalizeChartType(String chartType) {
        String normalized = StringUtils.upperCase(StringUtils.trim(chartType));
        return "METRIC_CARD".equals(normalized) ? "KPI_CARD" : normalized;
    }

    @Override
    public boolean updateChatIsTop(Long chatId, int isTop, User user) {
        checkChatAccess(chatId, user);
        return chatRepository.updateConversionIsTop(chatId, isTop);
    }

    @Override
    public Boolean deleteChat(Long chatId, String userName) {
        return chatRepository.deleteChat(chatId, userName);
    }

    @Override
    public PageInfo<QueryResp> queryInfo(PageQueryInfoReq pageQueryInfoReq, long chatId) {
        PageInfo<QueryResp> queryRespPageInfo =
                chatQueryRepository.getChatQuery(pageQueryInfoReq, chatId);
        if (CollectionUtils.isEmpty(queryRespPageInfo.getList())) {
            return queryRespPageInfo;
        }
        fillParseInfo(queryRespPageInfo.getList());
        return queryRespPageInfo;
    }

    @Override
    public Long createChatQuery(ChatParseReq chatParseReq) {
        Integer requestChatId = chatParseReq.getChatId();
        Long chatId = requestChatId == null ? null : requestChatId.longValue();
        if (chatId != null && chatId > 0) {
            ChatDO chat = getAuthorizedChat(chatId, chatParseReq.getUser());
            if (chat.getAgentId() == null) {
                throw new InvalidPermissionException("Chat is not bound to an agent: " + chatId);
            }
            if (!Objects.equals(chat.getAgentId(), chatParseReq.getAgentId())) {
                throw new InvalidPermissionException(
                        "Query agent does not match chat agent: " + chatId);
            }
            chatParseReq.setAgentId(chat.getAgentId());
        } else {
            checkChatAccess(chatId, chatParseReq.getUser());
        }
        return chatQueryRepository.createChatQuery(chatParseReq);
    }

    @Override
    public QueryResp getChatQuery(Long queryId, User user) {
        checkQueryAccess(queryId, user);
        return chatQueryRepository.getChatQuery(queryId);
    }

    @Override
    public ChatQueryDO getChatQueryDO(Long queryId) {
        return chatQueryRepository.getChatQueryDO(queryId);
    }

    @Override
    public List<QueryResp> getChatQueries(Integer chatId, User user) {
        checkChatAccess(chatId == null ? null : chatId.longValue(), user);
        List<QueryResp> queries = chatQueryRepository.getChatQueries(chatId);
        fillParseInfo(queries);
        return queries;
    }

    @Override
    public ShowCaseResp queryShowCase(PageQueryInfoReq pageQueryInfoReq, int agentId, User user) {
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw new InvalidPermissionException("User identity is required");
        }
        pageQueryInfoReq.setUserName(user.getName());
        ShowCaseResp showCaseResp = new ShowCaseResp();
        showCaseResp.setCurrent(pageQueryInfoReq.getCurrent());
        showCaseResp.setPageSize(pageQueryInfoReq.getPageSize());
        List<QueryResp> queryResps = chatQueryRepository.queryShowCase(pageQueryInfoReq, agentId);
        if (CollectionUtils.isEmpty(queryResps)) {
            return showCaseResp;
        }
        queryResps.removeIf(queryResp -> {
            if (queryResp.getQueryResult() == null) {
                return true;
            }
            if (queryResp.getQueryResult().getResponse() != null) {
                return false;
            }
            if (CollectionUtils.isEmpty(queryResp.getQueryResult().getQueryResults())) {
                return true;
            }
            Map<String, Object> data = queryResp.getQueryResult().getQueryResults().get(0);
            return CollectionUtils.isEmpty(data);
        });
        queryResps = new ArrayList<>(queryResps.stream()
                .collect(Collectors.toMap(QueryResp::getQueryText, Function.identity(),
                        (existing, replacement) -> existing, LinkedHashMap::new))
                .values());
        fillParseInfo(queryResps);
        Map<Long, List<QueryResp>> showCaseMap =
                queryResps.stream().collect(Collectors.groupingBy(QueryResp::getChatId));
        showCaseResp.setShowCaseMap(showCaseMap);
        return showCaseResp;
    }

    private void fillParseInfo(List<QueryResp> queryResps) {
        List<Long> queryIds =
                queryResps.stream().map(QueryResp::getQuestionId).collect(Collectors.toList());
        List<ChatParseDO> chatParseDOs = chatQueryRepository.getParseInfoList(queryIds);
        if (CollectionUtils.isEmpty(chatParseDOs)) {
            return;
        }
        Map<Long, List<ChatParseDO>> chatParseMap =
                chatParseDOs.stream().collect(Collectors.groupingBy(ChatParseDO::getQuestionId));
        for (QueryResp queryResp : queryResps) {
            List<ChatParseDO> chatParseDOList = chatParseMap.get(queryResp.getQuestionId());
            if (CollectionUtils.isEmpty(chatParseDOList)) {
                continue;
            }
            List<SemanticParseInfo> parseInfos = chatParseDOList.stream()
                    .map(chatParseDO -> JsonUtil.toObject(chatParseDO.getParseInfo(),
                            SemanticParseInfo.class))
                    .sorted(Comparator.comparingDouble(SemanticParseInfo::getScore).reversed())
                    .collect(Collectors.toList());
            queryResp.setParseInfos(parseInfos);
        }
    }

    @Override
    public ChatQueryDO saveQueryResult(ChatExecuteReq chatExecuteReq, QueryResult queryResult) {
        checkQueryAccess(chatExecuteReq.getQueryId(), chatExecuteReq.getUser());
        ChatQueryDO chatQueryDO = chatQueryRepository.getChatQueryDO(chatExecuteReq.getQueryId());
        Long persistedChatId = chatQueryDO.getChatId();
        checkChatAccess(persistedChatId, chatExecuteReq.getUser());
        chatQueryDO.setQuestionId(chatExecuteReq.getQueryId());
        chatQueryDO.setQueryResult(JsonUtil.toString(queryResult));
        chatQueryDO.setQueryState(1);
        updateQuery(chatQueryDO);
        if (persistedChatId > 0) {
            chatRepository.updateLastQuestion(persistedChatId, chatQueryDO.getQueryText(),
                    getCurrentTime());
        }
        return chatQueryDO;
    }

    @Override
    public int updateQuery(ChatQueryDO chatQueryDO) {
        return chatQueryRepository.updateChatQuery(chatQueryDO);
    }

    @Override
    public void deleteQuery(Long queryId, User user) {
        checkQueryAccess(queryId, user);
        ChatQueryDO chatQuery = chatQueryRepository.getChatQueryDO(queryId);
        if (Objects.nonNull(chatQuery)) {
            chatQuery.setQueryState(0);
            chatQueryRepository.updateChatQuery(chatQuery);
        }
    }

    @Override
    public void checkQueryAccess(Long queryId, User user) {
        ChatQueryDO query = chatQueryRepository.getChatQueryDO(queryId);
        if (query == null) {
            IllegalArgumentException failure =
                    new IllegalArgumentException("Query does not exist: " + queryId);
            publishObjectDecision(queryId, user, AuditEventType.OBJECT_ACCESS_DENIED,
                    "QUERY_NOT_FOUND", failure);
            throw failure;
        }
        try {
            objectAccessPolicy.checkQueryAccess(queryId, query.getUserName(), user);
        } catch (RuntimeException failure) {
            publishObjectDecision(queryId, user, AuditEventType.OBJECT_ACCESS_DENIED,
                    "QUERY_OWNERSHIP_DENIED", failure);
            throw failure;
        }
        auditEventPublisher.publishBestEffort(objectAccessEvent(queryId,
                AuditEventType.OBJECT_ACCESS_ALLOWED, AuditOutcome.SUCCESS, "QUERY_ACCESS_ALLOWED"),
                user);
    }

    private void publishObjectDecision(Long queryId, User user, AuditEventType eventType,
            String reasonCode, RuntimeException originalFailure) {
        try {
            auditEventPublisher.publishRequired(
                    objectAccessEvent(queryId, eventType, AuditOutcome.DENIED, reasonCode), user);
        } catch (RuntimeException auditFailure) {
            originalFailure.addSuppressed(auditFailure);
        }
    }

    private AuditEvent objectAccessEvent(Long queryId, AuditEventType eventType,
            AuditOutcome outcome, String reasonCode) {
        return AuditEvent.builder().eventType(eventType).outcome(outcome).reasonCode(reasonCode)
                .queryId(queryId).resourceType("CHAT_QUERY")
                .resourceId(queryId == null ? null : String.valueOf(queryId)).build();
    }

    @Override
    public void checkChatAccess(Long chatId, User user) {
        if (chatId != null && chatId > 0) {
            getAuthorizedChat(chatId, user);
            return;
        }
        if (chatId == null) {
            IllegalArgumentException failure = new IllegalArgumentException("Chat id is required");
            publishChatDecision(chatId, user, AuditEventType.OBJECT_ACCESS_DENIED,
                    AuditOutcome.DENIED, "CHAT_ID_REQUIRED", failure);
            throw failure;
        }
        if (user == null || !user.isSuperAdmin()) {
            InvalidPermissionException failure = new InvalidPermissionException(
                    "System chat access requires a super administrator");
            publishChatDecision(chatId, user, AuditEventType.OBJECT_ACCESS_DENIED,
                    AuditOutcome.DENIED, "SYSTEM_CHAT_ACCESS_DENIED", failure);
            throw failure;
        }
        auditEventPublisher
                .publishBestEffort(chatAccessEvent(chatId, AuditEventType.OBJECT_ACCESS_ALLOWED,
                        AuditOutcome.SUCCESS, "SYSTEM_CHAT_ACCESS_ALLOWED"), user);
    }

    @Override
    public ChatDO getAuthorizedChat(Long chatId, User user) {
        if (chatId == null || chatId <= 0) {
            checkChatAccess(chatId, user);
            return null;
        }
        ChatDO chat = chatRepository.getChat(chatId);
        if (chat == null) {
            IllegalArgumentException failure =
                    new IllegalArgumentException("Chat does not exist: " + chatId);
            publishChatDecision(chatId, user, AuditEventType.OBJECT_ACCESS_DENIED,
                    AuditOutcome.DENIED, "CHAT_NOT_FOUND", failure);
            throw failure;
        }
        try {
            objectAccessPolicy.checkChatAccess(chatId, chat.getCreator(), user);
        } catch (RuntimeException failure) {
            publishChatDecision(chatId, user, AuditEventType.OBJECT_ACCESS_DENIED,
                    AuditOutcome.DENIED, "CHAT_OWNERSHIP_DENIED", failure);
            throw failure;
        }
        auditEventPublisher.publishBestEffort(chatAccessEvent(chatId,
                AuditEventType.OBJECT_ACCESS_ALLOWED, AuditOutcome.SUCCESS, "CHAT_ACCESS_ALLOWED"),
                user);
        return chat;
    }

    private void publishChatDecision(Long chatId, User user, AuditEventType eventType,
            AuditOutcome outcome, String reasonCode, RuntimeException originalFailure) {
        try {
            auditEventPublisher
                    .publishRequired(chatAccessEvent(chatId, eventType, outcome, reasonCode), user);
        } catch (RuntimeException auditFailure) {
            originalFailure.addSuppressed(auditFailure);
        }
    }

    private AuditEvent chatAccessEvent(Long chatId, AuditEventType eventType, AuditOutcome outcome,
            String reasonCode) {
        return AuditEvent.builder().eventType(eventType).outcome(outcome).reasonCode(reasonCode)
                .chatId(chatId).resourceType("CHAT")
                .resourceId(chatId == null ? null : String.valueOf(chatId)).build();
    }

    @Override
    public void updateParseCostTime(ChatParseResp chatParseResp) {
        ChatQueryDO chatQueryDO = chatQueryRepository.getChatQueryDO(chatParseResp.getQueryId());
        chatQueryDO.setParseTimeCost(JsonUtil.toString(chatParseResp.getParseTimeCost()));
        updateQuery(chatQueryDO);
    }

    @Override
    public List<ChatParseDO> batchAddParse(ChatParseReq chatParseReq, ChatParseResp chatParseResp) {
        List<SemanticParseInfo> candidateParses = chatParseResp.getSelectedParses();
        return chatQueryRepository.batchSaveParseInfo(chatParseReq, chatParseResp, candidateParses);
    }

    private String getCurrentTime() {
        SimpleDateFormat tempDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return tempDate.format(new java.util.Date());
    }

    @Override
    public SemanticParseInfo getParseInfo(Long questionId, int parseId) {
        ChatParseDO chatParseDO = chatQueryRepository.getParseInfo(questionId, parseId);
        if (chatParseDO == null) {
            return null;
        } else {
            return JSONObject.parseObject(chatParseDO.getParseInfo(), SemanticParseInfo.class);
        }
    }

    @Override
    public List<SemanticParseInfo> getParseInfos(Long questionId) {
        List<ChatParseDO> chatParseDOs =
                chatQueryRepository.getParseInfoList(Collections.singletonList(questionId));
        return chatParseDOs.stream().map(chatParseDO -> JSONObject
                .parseObject(chatParseDO.getParseInfo(), SemanticParseInfo.class))
                .collect(Collectors.toList());
    }
}
