package com.tencent.supersonic.headless.server.rest;

import com.google.common.collect.Lists;
import com.tencent.supersonic.auth.api.authentication.utils.UserHolder;
import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.enums.AuthType;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.ModelSchema;
import com.tencent.supersonic.headless.api.pojo.request.FieldRemovedReq;
import com.tencent.supersonic.headless.api.pojo.request.MetaBatchReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.ModelResp;
import com.tencent.supersonic.headless.api.pojo.response.UnAvailableItemResp;
import com.tencent.supersonic.headless.server.pojo.ModelFilter;
import com.tencent.supersonic.headless.server.service.ModelService;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/semantic/model")
public class ModelController {

    private ModelService modelService;

    public ModelController(ModelService modelService) {
        this.modelService = modelService;
    }

    @PostMapping("/createModel")
    public Boolean createModel(@RequestBody ModelReq modelReq, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        modelService.createModel(modelReq, user);
        return true;
    }

    @PostMapping("/createModelBatch")
    public Boolean createModelBatch(@RequestBody ModelBuildReq modelBuildReq,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        modelService.createModel(modelBuildReq, user);
        return true;
    }

    @PostMapping("/updateModel")
    public Boolean updateModel(@RequestBody ModelReq modelReq, HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        User user = UserHolder.findUser(request, response);
        modelService.updateModel(modelReq, user);
        return true;
    }

    @DeleteMapping("/deleteModel/{modelId}")
    public Boolean deleteModel(@PathVariable("modelId") Long modelId, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        modelService.deleteModel(modelId, user);
        return true;
    }

    @GetMapping("/getModelList/{domainId}")
    public List<ModelResp> getModelList(@PathVariable("domainId") Long domainId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        return modelService.getModelListWithAuth(user, domainId, AuthType.ADMIN);
    }

    @GetMapping("/getModel/{id}")
    public ModelResp getModel(@PathVariable("id") Long id, HttpServletRequest request,
            HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        requireModelAccess(user, id, AuthType.ADMIN);
        return modelService.getModel(id);
    }

    @GetMapping("/getModelListByIds/{modelIds}")
    public List<ModelResp> getModelListByIds(@PathVariable("modelIds") String modelIds,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        Set<Long> accessibleIds = accessibleModelIds(user, AuthType.VIEWER);
        List<Long> ids = Arrays.stream(modelIds.split(",")).map(Long::parseLong)
                .collect(Collectors.toList());
        if (!accessibleIds.containsAll(ids)) {
            throw new InvalidPermissionException("No permission to access one or more models");
        }
        ModelFilter modelFilter = new ModelFilter();
        modelFilter.setIds(ids);
        return modelService.getModelList(modelFilter);
    }

    @GetMapping("/getAllModelByDomainId")
    public List<ModelResp> getAllModelByDomainId(@RequestParam("domainId") Long domainId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        Set<Long> accessibleIds = accessibleModelIds(user, AuthType.VIEWER);
        return modelService.getAllModelByDomainIds(Lists.newArrayList(domainId)).stream()
                .filter(model -> accessibleIds.contains(model.getId()))
                .collect(Collectors.toList());
    }

    @GetMapping("/getModelDatabase/{modelId}")
    public DatabaseResp getModelDatabase(@PathVariable("modelId") Long modelId,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        requireModelAccess(user, modelId, AuthType.ADMIN);
        DatabaseResp database = modelService.getDatabaseByModelId(modelId);
        if (database != null) {
            database.setPassword(null);
        }
        return database;
    }

    @PostMapping("/batchUpdateStatus")
    public Boolean batchUpdateStatus(@RequestBody MetaBatchReq metaBatchReq,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        modelService.batchUpdateStatus(metaBatchReq, user);
        return true;
    }

    @PostMapping("/getUnAvailableItem")
    public UnAvailableItemResp getUnAvailableItem(@RequestBody FieldRemovedReq fieldRemovedReq,
            HttpServletRequest request, HttpServletResponse response) {
        User user = UserHolder.findUser(request, response);
        requireModelAccess(user, fieldRemovedReq == null ? null : fieldRemovedReq.getModelId(),
                AuthType.ADMIN);
        return modelService.getUnAvailableItem(fieldRemovedReq);
    }

    @PostMapping("/buildModelSchema")
    public Map<String, ModelSchema> buildModelSchema(@RequestBody ModelBuildReq modelBuildReq,
            HttpServletRequest request, HttpServletResponse response) throws SQLException {
        User user = UserHolder.findUser(request, response);
        if (user == null || !user.isSuperAdmin()) {
            throw new InvalidPermissionException(
                    "Only super administrators can build model schemas");
        }
        return modelService.buildModelSchema(modelBuildReq);
    }

    private void requireModelAccess(User user, Long modelId, AuthType authType) {
        if (modelId == null || !accessibleModelIds(user, authType).contains(modelId)) {
            throw new InvalidPermissionException("No permission to access model");
        }
    }

    private Set<Long> accessibleModelIds(User user, AuthType authType) {
        if (user == null) {
            throw new InvalidPermissionException("User identity is required");
        }
        List<ModelResp> visible = modelService.getModelListWithAuth(user, null, authType);
        if (visible == null) {
            return Collections.emptySet();
        }
        return visible.stream().map(ModelResp::getId)
                .collect(Collectors.toCollection(HashSet::new));
    }
}
