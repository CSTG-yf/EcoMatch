package com.hankcs.hanlp;

import com.tencent.supersonic.common.pojo.enums.DictWordType;
import com.tencent.supersonic.common.util.SensitiveLogUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Data
@Slf4j
public class LoadRemoveService {

    public List removeNatures(List value, Set<Long> modelIdOrDataSetIds) {
        if (CollectionUtils.isEmpty(value)) {
            return value;
        }
        List<String> resultList = new ArrayList<>(value);
        if (!CollectionUtils.isEmpty(modelIdOrDataSetIds)) {
            resultList.removeIf(nature -> {
                if (Objects.isNull(nature) || !nature.startsWith("_")) { // 系统的字典是以 _ 开头的，
                                                                         // 过滤因引用外部字典导致的异常
                    return false;
                }
                Long id = getId(nature);
                if (Objects.nonNull(id)) {
                    return !modelIdOrDataSetIds.contains(id);
                }
                return false;
            });
        }
        return resultList;
    }

    public Long getId(String nature) {
        try {
            String[] split = nature.split(DictWordType.NATURE_SPILT);
            if (split.length <= 1) {
                return null;
            }
            return Long.valueOf(split[1]);
        } catch (NumberFormatException e) {
            log.error("Dictionary nature id parsing failed: nature=[{}], type={}, error=[{}]",
                    SensitiveLogUtils.summarize(nature), e.getClass().getSimpleName(),
                    SensitiveLogUtils.summarize(e));
        }
        return null;
    }
}
