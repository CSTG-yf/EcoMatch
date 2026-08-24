package com.tencent.supersonic;

import static org.assertj.core.api.Assertions.assertThat;

import com.tencent.supersonic.chat.server.service.AgentService;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.demo.S2CompanyDemo;
import com.tencent.supersonic.demo.S2SingerDemo;
import com.tencent.supersonic.demo.S2VisitsDemo;
import com.tencent.supersonic.headless.api.pojo.SchemaElementMatch;
import com.tencent.supersonic.headless.api.pojo.SchemaElementType;
import com.tencent.supersonic.headless.api.pojo.enums.MapModeEnum;
import com.tencent.supersonic.headless.api.pojo.request.QueryNLReq;
import com.tencent.supersonic.headless.api.pojo.response.MapResp;
import com.tencent.supersonic.headless.api.pojo.response.S2Term;
import com.tencent.supersonic.headless.chat.knowledge.KnowledgeBaseService;
import com.tencent.supersonic.headless.chat.knowledge.helper.NatureHelper;
import com.tencent.supersonic.headless.server.facade.service.ChatLayerService;
import com.tencent.supersonic.headless.server.service.DataSetService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StandaloneTestEnvironmentTest extends BaseApplication {

    @Autowired private DataSource dataSource;
    @Autowired private AgentService agentService;
    @Autowired private DataSetService dataSetService;
    @Autowired private KnowledgeBaseService knowledgeBaseService;
    @Autowired private ChatLayerService chatLayerService;

    @Test
    void usesIsolatedInMemoryDatabaseWithRequiredDemoAgents() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo("jdbc:h2:mem:semantic-test");
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SELECT COUNT(*) FROM s2_pv_uv_statis")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getLong(1)).isPositive();
            }
        }

        List<com.tencent.supersonic.chat.server.agent.Agent> agents = agentService.getAgents();
        Set<String> agentNames =
                agents.stream().map(agent -> agent.getName()).collect(Collectors.toSet());
        assertThat(agentNames).contains(S2VisitsDemo.AGENT_NAME, S2CompanyDemo.AGENT_NAME,
                S2SingerDemo.AGENT_NAME);

        List<S2Term> terms = knowledgeBaseService.getTerms("alice的访问次数 周杰伦流派和代表作 国风歌手",
                dataSetService.getModelIdToDataSetIds());
        Set<String> dictionaryWords = terms.stream().map(term -> term.getWord())
                .collect(Collectors.toSet());
        assertThat(dictionaryWords).contains("alice", "周杰伦", "国风");
        long visitsDataSetId = agents.stream()
                .filter(agent -> S2VisitsDemo.AGENT_NAME.equals(agent.getName())).findFirst()
                .orElseThrow().getDataSetIds().iterator().next();
        long singerDataSetId = agents.stream()
                .filter(agent -> S2SingerDemo.AGENT_NAME.equals(agent.getName())).findFirst()
                .orElseThrow().getDataSetIds().iterator().next();
        assertThat(terms.stream().filter(term -> "alice".equals(term.getWord()))
                .map(term -> NatureHelper.getDataSetId(term.getNature().toString())))
                .contains(visitsDataSetId);
        assertThat(terms.stream().filter(term -> "周杰伦".equals(term.getWord())
                || "国风".equals(term.getWord()))
                .map(term -> NatureHelper.getDataSetId(term.getNature().toString())))
                .contains(singerDataSetId);

        assertMappedValue("alice的访问次数", visitsDataSetId, "alice");
        assertMappedValue("周杰伦流派和代表作", singerDataSetId, "周杰伦");
        assertMappedValue("国风歌手", singerDataSetId, "国风");
    }

    private void assertMappedValue(String queryText, long dataSetId, String expectedValue) {
        QueryNLReq request = new QueryNLReq();
        request.setQueryText(queryText);
        request.setUser(User.getDefaultUser());
        request.setDataSetIds(Set.of(dataSetId));
        request.setMapModeEnum(MapModeEnum.STRICT);

        MapResp response = chatLayerService.map(request);
        Map<Long, List<SchemaElementMatch>> matches =
                response.getMapInfo().getDataSetElementMatches();
        assertThat(matches).containsKey(dataSetId);
        assertThat(matches.get(dataSetId)).anySatisfy(match -> {
            assertThat(match.getElement().getType()).isEqualTo(SchemaElementType.VALUE);
            assertThat(match.getWord()).isEqualTo(expectedValue);
        });
    }
}
