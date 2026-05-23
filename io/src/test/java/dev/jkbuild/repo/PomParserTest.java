// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PomParserTest {

    @Test
    void parses_minimal_pom() {
        Pom pom = PomParser.parse("""
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """);

        assertThat(pom.groupId()).isEqualTo("com.example");
        assertThat(pom.artifactId()).isEqualTo("widget");
        assertThat(pom.version()).isEqualTo("1.0");
        assertThat(pom.packaging()).isEqualTo("jar");
        assertThat(pom.parent()).isNull();
        assertThat(pom.dependencies()).isEmpty();
        assertThat(pom.managedDependencies()).isEmpty();
    }

    @Test
    void inherits_group_and_version_from_parent() {
        Pom pom = PomParser.parse("""
                <project>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>3.2.0</version>
                  </parent>
                  <artifactId>my-service</artifactId>
                </project>
                """);

        assertThat(pom.groupId()).isEqualTo("org.springframework.boot");
        assertThat(pom.version()).isEqualTo("3.2.0");
        assertThat(pom.parent().artifactId()).isEqualTo("spring-boot-starter-parent");
    }

    @Test
    void substitutes_project_and_named_properties() {
        Pom pom = PomParser.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.2.3</version>
                  <properties>
                    <spring.version>6.1.0</spring.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework</groupId>
                      <artifactId>spring-core</artifactId>
                      <version>${spring.version}</version>
                    </dependency>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>util</artifactId>
                      <version>${project.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThat(pom.dependencies()).hasSize(2);
        assertThat(pom.dependencies().getFirst().version()).isEqualTo("6.1.0");
        assertThat(pom.dependencies().getLast().version()).isEqualTo("1.2.3");
    }

    @Test
    void unresolved_property_left_intact_for_resolver_diagnostics() {
        Pom pom = PomParser.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.external</groupId>
                      <artifactId>thing</artifactId>
                      <version>${external.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        assertThat(pom.dependencies().getFirst().version()).isEqualTo("${external.version}");
    }

    @Test
    void parses_dependency_management() {
        Pom pom = PomParser.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework</groupId>
                        <artifactId>spring-core</artifactId>
                        <version>6.1.0</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        assertThat(pom.managedDependencies()).hasSize(1);
        assertThat(pom.managedDependencies().getFirst().module()).isEqualTo("org.springframework:spring-core");
    }

    @Test
    void parses_scope_optional_classifier_type_exclusions() {
        Pom pom = PomParser.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>io.netty</groupId>
                      <artifactId>netty-transport-native-epoll</artifactId>
                      <version>4.1.115</version>
                      <classifier>linux-x86_64</classifier>
                      <type>jar</type>
                      <scope>runtime</scope>
                      <optional>true</optional>
                      <exclusions>
                        <exclusion>
                          <groupId>org.junk</groupId>
                          <artifactId>nope</artifactId>
                        </exclusion>
                      </exclusions>
                    </dependency>
                  </dependencies>
                </project>
                """);

        Pom.Dep dep = pom.dependencies().getFirst();
        assertThat(dep.scope()).isEqualTo("runtime");
        assertThat(dep.optional()).isTrue();
        assertThat(dep.classifier()).isEqualTo("linux-x86_64");
        assertThat(dep.type()).isEqualTo("jar");
        assertThat(dep.exclusions()).singleElement().satisfies(e -> {
            assertThat(e.groupId()).isEqualTo("org.junk");
            assertThat(e.artifactId()).isEqualTo("nope");
        });
    }

    @Test
    void rejects_doctype_declarations() {
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE project [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <project>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                </project>
                """;
        assertThatThrownBy(() -> PomParser.parse(malicious))
                .isInstanceOf(PomParseException.class);
    }

    @Test
    void rejects_missing_artifact_id() {
        assertThatThrownBy(() -> PomParser.parse("""
                <project>
                  <groupId>com.example</groupId>
                  <version>1.0</version>
                </project>
                """))
                .isInstanceOf(PomParseException.class)
                .hasMessageContaining("artifactId");
    }
}
