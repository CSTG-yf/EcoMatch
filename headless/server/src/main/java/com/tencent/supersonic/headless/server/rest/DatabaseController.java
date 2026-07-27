package com.tencent.supersonic.headless.server.rest;

import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.request.DatabaseReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.request.SqlExecuteReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.pojo.DatabaseParameter;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/semantic/database")
public class DatabaseController {

    private static final Pattern SAFE_METADATA_IDENTIFIER =
            Pattern.compile("^[\\p{L}\\p{N}_$.-]+$");

    private DatabaseService databaseService;

    public DatabaseController(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    @PostMapping("/testConnect")
    public boolean testConnect(@RequestBody DatabaseReq databaseReq, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return databaseService.testConnect(databaseReq, user);
    }

    @PostMapping("/createOrUpdateDatabase")
    public DatabaseResp createOrUpdateDatabase(@RequestBody DatabaseReq databaseReq,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return databaseService.createOrUpdateDatabase(databaseReq, user);
    }

    @GetMapping("/{id}")
    public DatabaseResp getDatabase(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return databaseService.getDatabase(id, user);
    }

    @GetMapping("/getDatabaseList")
    public List<DatabaseResp> getDatabaseList(HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return databaseService.getDatabaseList(user);
    }

    @DeleteMapping("/{id}")
    public boolean deleteDatabase(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        databaseService.deleteDatabase(id, user);
        return true;
    }

    @PostMapping("/executeSql")
    public SemanticQueryResp executeSql(@RequestBody SqlExecuteReq sqlExecuteReq,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return databaseService.executeSql(sqlExecuteReq, user);
    }

    @RequestMapping("/getCatalogs")
    public List<String> getCatalogs(@RequestParam("id") Long databaseId, HttpServletRequest request,
            HttpServletResponse response) throws SQLException {
        requireDatabaseAccess(databaseId, request, response);
        return databaseService.getCatalogs(databaseId);
    }

    @RequestMapping("/getDbNames")
    public List<String> getDbNames(@RequestParam("id") Long databaseId,
            @RequestParam(value = "catalog", required = false) String catalog,
            HttpServletRequest request, HttpServletResponse response) throws SQLException {
        requireDatabaseAccess(databaseId, request, response);
        validateMetadataIdentifier(catalog, false);
        return databaseService.getDbNames(databaseId, catalog);
    }

    @RequestMapping("/getTables")
    public List<String> getTables(@RequestParam("databaseId") Long databaseId,
            @RequestParam(value = "catalog", required = false) String catalog,
            @RequestParam("db") String db, HttpServletRequest request, HttpServletResponse response)
            throws SQLException {
        requireDatabaseAccess(databaseId, request, response);
        validateMetadataIdentifier(catalog, false);
        validateMetadataIdentifier(db, true);
        return databaseService.getTables(databaseId, catalog, db);
    }

    @RequestMapping("/getColumnsByName")
    public List<DBColumn> getColumnsByName(@RequestParam("databaseId") Long databaseId,
            @RequestParam(name = "catalog", required = false) String catalog,
            @RequestParam("db") String db, @RequestParam("table") String table,
            HttpServletRequest request, HttpServletResponse response) throws SQLException {
        requireDatabaseAccess(databaseId, request, response);
        validateMetadataIdentifier(catalog, false);
        validateMetadataIdentifier(db, true);
        validateMetadataIdentifier(table, true);
        return databaseService.getColumns(databaseId, catalog, db, table);
    }

    @PostMapping("/listColumnsBySql")
    public List<DBColumn> listColumnsBySql(@RequestBody ModelBuildReq modelBuildReq,
            HttpServletRequest request, HttpServletResponse response) throws SQLException {
        requireDatabaseAccess(modelBuildReq.getDatabaseId(), request, response);
        return databaseService.getColumns(modelBuildReq.getDatabaseId(), modelBuildReq.getSql());
    }

    @GetMapping("/getDatabaseParameters")
    public Map<String, List<DatabaseParameter>> getDatabaseParameters(HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return databaseService.getDatabaseParameters(user);
    }

    private void requireDatabaseAccess(Long databaseId, HttpServletRequest request,
            HttpServletResponse response) {
        if (databaseId == null) {
            throw new InvalidArgumentException("databaseId is required");
        }
        User user = UserHolder.findUser(request, response);
        databaseService.getDatabase(databaseId, user);
    }

    private void validateMetadataIdentifier(String identifier, boolean required) {
        if (identifier == null || identifier.isBlank()) {
            if (required) {
                throw new InvalidArgumentException("Database metadata identifier is required");
            }
            return;
        }
        if (!SAFE_METADATA_IDENTIFIER.matcher(identifier).matches()) {
            throw new InvalidArgumentException("Invalid database metadata identifier");
        }
    }
}
