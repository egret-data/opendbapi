package io.opendbapi.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * springdoc names schemas by simple class name, so two DTOs called {@code TestResult} in
 * different controllers silently become one schema and the generated client gets the wrong
 * type. Every public record and enum of the management API must have a unique simple name.
 */
class ApiSchemaNamesTest {

    @Test
    void dtoSimpleNamesAreUnique() throws Exception {
        // Arrange
        List<Class<?>> types = new ArrayList<>();
        for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:io/opendbapi/admin/**/*.class")) {
            String path = r.getURL().getPath();
            String name = path.substring(path.indexOf("io/opendbapi/admin/")).replace('/', '.').replace(".class", "");
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            if ((type.isRecord() || type.isEnum()) && java.lang.reflect.Modifier.isPublic(type.getModifiers())) {
                types.add(type);
            }
        }

        // Act
        Map<String, List<String>> duplicates = types.stream()
                .collect(Collectors.groupingBy(Class::getSimpleName, Collectors.mapping(Class::getName, Collectors.toList())))
                .entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        // Assert
        assertThat(types).isNotEmpty();
        assertThat(duplicates).as("rename one of each pair").isEmpty();
    }
}
