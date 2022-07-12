package org.broadinstitute.dsm.model.elastic.export.tabular.renderer;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.dsm.model.elastic.export.tabular.FilterExportConfig;

public class AmbulationValueProvider extends TextValueProvider {
    private final Map<String, String> valueMappings = Map.of(
            "INDEPENDENTLY", "1",
            "MOST_OF_THE_TIME", "2",
            "WITH_ASSISTANCE", "3",
            "USES_WALKER", "4",
            "WHEELCHAIR_WITHOUT_ASSISTANCE", "5",
            "WHEELCHAIR_WITH_ASSISTANCE", "6");

    @Override
    /**
     * Return the numeric code, rather than the stableId
     */
    public Collection<String> formatRawValues(Collection<?> rawValues, FilterExportConfig filterConfig, Map<String, Object> formMap) {
        return rawValues.stream().map(value -> valueMappings.getOrDefault(value, StringUtils.EMPTY))
                .collect(Collectors.toList());
    }
}