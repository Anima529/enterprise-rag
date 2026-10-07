package com.example.rag.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisXMLMapperBuilder;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/** 防止后续业务持久化绕开 MyBatis-Plus 或重新将 SQL 塞入 Java Mapper。 */
class PersistenceMappingTest {

    /** 使用实际 MyBatis-Plus 解析全部 XML，检查每个 Mapper 方法都有可解析的语句。 */
    @Test
    void shouldBindEveryMapperMethodToXmlStatement() throws Exception {
        MybatisConfiguration configuration = configuration();
        for (Class<?> mapper : configuration.getMapperRegistry().getMappers()) {
            for (Method method : mapper.getDeclaredMethods()) {
                assertThat(configuration.hasStatement(mapper.getName() + "." + method.getName())).isTrue();
            }
        }
    }

    /** Mapper 只声明绑定契约，不允许 SQL 注解及 Provider 绕过 XML 管理。 */
    @Test
    void shouldKeepSqlAnnotationsOutOfMapperInterfaces() throws Exception {
        MybatisConfiguration configuration = configuration();
        for (Class<?> mapper : configuration.getMapperRegistry().getMappers()) {
            for (Method method : mapper.getDeclaredMethods()) {
                assertThat(method.getDeclaredAnnotations()).isEmpty();
            }
        }
    }

    /** 生产持久化不能重新引入 JDBC Template；测试准备与数据库断言可以独立使用。 */
    @Test
    void shouldKeepJdbcTemplatesOutOfProductionSources() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            sourceRoot = Path.of("backend/src/main/java");
        }
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            for (Path source : sources.filter(file -> file.toString().endsWith(".java")).toList()) {
                assertThat(Files.readString(source))
                    .as(source.toString())
                    .doesNotContain("JdbcTemplate", "NamedParameterJdbcTemplate", "DriverManager");
            }
        }
    }

    /** 解析顺序无需依赖文件系统，共用 SQL 片段在最终语句解析时统一解决。 */
    private MybatisConfiguration configuration() throws Exception {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setCallSettersOnNulls(true);
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:/mapper/**/*.xml");
        assertThat(resources).isNotEmpty();
        Arrays.sort(resources, Comparator.comparing(Resource::getDescription));
        for (Resource resource : resources) {
            try (InputStream input = resource.getInputStream()) {
                new MybatisXMLMapperBuilder(
                    input,
                    configuration,
                    resource.getDescription(),
                    configuration.getSqlFragments()
                ).parse();
            }
        }
        configuration.getMappedStatementNames();
        return configuration;
    }
}
