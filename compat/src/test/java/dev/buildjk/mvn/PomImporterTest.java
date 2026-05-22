// SPDX-License-Identifier: Apache-2.0
package dev.buildjk.mvn;

import dev.buildjk.model.Dependency;
import dev.buildjk.model.Scope;
import dev.buildjk.model.VersionSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PomImporterTest {

    @Test
    void maps_project_coordinates_and_default_jdk() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.2.3</version>
                </project>
                """);

        var p = result.buildJk().project();
        assertThat(p.group()).isEqualTo("com.example");
        assertThat(p.artifact()).isEqualTo("widget");
        assertThat(p.version()).isEqualTo("1.2.3");
        assertThat(p.jdk()).isEqualTo("25"); // no compiler config → default
        assertThat(result.report().issues()).isEmpty();
    }

    @Test
    void picks_jdk_from_maven_compiler_release_property() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                  </properties>
                </project>
                """);
        assertThat(result.buildJk().project().jdk()).isEqualTo("21");
    }

    @Test
    void picks_jdk_from_compiler_plugin_target() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <build>
                    <plugins>
                      <plugin>
                        <groupId>org.apache.maven.plugins</groupId>
                        <artifactId>maven-compiler-plugin</artifactId>
                        <configuration>
                          <target>17</target>
                          <source>17</source>
                        </configuration>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);
        assertThat(result.buildJk().project().jdk()).isEqualTo("17");
    }

    @Test
    void maps_dependencies_by_scope_with_exact_pins() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                      <version>3.4.0</version>
                    </dependency>
                    <dependency>
                      <groupId>jakarta.servlet</groupId>
                      <artifactId>jakarta.servlet-api</artifactId>
                      <version>6.1.0</version>
                      <scope>provided</scope>
                    </dependency>
                    <dependency>
                      <groupId>org.junit.jupiter</groupId>
                      <artifactId>junit-jupiter</artifactId>
                      <version>5.11.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.mysql</groupId>
                      <artifactId>mysql-connector-j</artifactId>
                      <version>9.0.0</version>
                      <scope>runtime</scope>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var deps = result.buildJk().dependencies();
        assertThat(deps.of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-starter-web");
        assertThat(deps.of(Scope.PROVIDED))
                .extracting(Dependency::module)
                .containsExactly("jakarta.servlet:jakarta.servlet-api");
        assertThat(deps.of(Scope.TEST))
                .extracting(Dependency::module)
                .containsExactly("org.junit.jupiter:junit-jupiter");
        assertThat(deps.of(Scope.RUNTIME))
                .extracting(Dependency::module)
                .containsExactly("com.mysql:mysql-connector-j");

        // Every imported version is an exact pin per PRD §7.3.
        for (Scope s : Scope.values()) {
            for (Dependency d : deps.of(s)) {
                assertThat(d.version()).isInstanceOf(VersionSelector.Exact.class);
            }
        }
        var main = (VersionSelector.Exact) deps.of(Scope.MAIN).getFirst().version();
        assertThat(main.version()).isEqualTo("3.4.0");
    }

    @Test
    void rejects_system_scope_with_tier3_error() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.local</groupId>
                      <artifactId>thing</artifactId>
                      <version>1.0</version>
                      <scope>system</scope>
                      <systemPath>/opt/thing.jar</systemPath>
                    </dependency>
                  </dependencies>
                </project>
                """);

        // The dep is NOT included.
        for (Scope s : Scope.values()) {
            assertThat(result.buildJk().dependencies().of(s)).isEmpty();
        }
        assertThat(result.report().hasErrors()).isTrue();
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("system"));
    }

    @Test
    void dependency_management_bom_import_becomes_platform() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-dependencies</artifactId>
                        <version>3.4.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        assertThat(result.buildJk().dependencies().of(Scope.PLATFORM))
                .extracting(Dependency::module)
                .containsExactly("org.springframework.boot:spring-boot-dependencies");
        assertThat(result.report().issues()).isEmpty();
    }

    @Test
    void dependency_management_non_bom_warns() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>com.fasterxml.jackson.core</groupId>
                        <artifactId>jackson-databind</artifactId>
                        <version>2.18.2</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);

        assertThat(result.buildJk().dependencies().of(Scope.PLATFORM)).isEmpty();
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("jackson-databind"));
    }

    @Test
    void parent_pom_is_warned_but_coords_inherit() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>parent-pom</artifactId>
                    <version>2.0</version>
                  </parent>
                  <artifactId>widget</artifactId>
                </project>
                """);

        var p = result.buildJk().project();
        assertThat(p.group()).isEqualTo("com.example");
        assertThat(p.version()).isEqualTo("2.0");
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("parent"));
    }

    @Test
    void maps_repositories_skipping_implicit_central() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <repositories>
                    <repository>
                      <id>central</id>
                      <url>https://repo.maven.apache.org/maven2/</url>
                    </repository>
                    <repository>
                      <id>internal</id>
                      <url>https://nexus.example/repository/maven-public/</url>
                    </repository>
                  </repositories>
                </project>
                """);

        assertThat(result.buildJk().repositories())
                .extracting(r -> r.name())
                .containsExactly("internal");
    }

    @Test
    void warns_on_profiles_modules_and_unknown_plugins() {
        var result = importPom("""
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                  <profiles>
                    <profile><id>dev</id></profile>
                  </profiles>
                  <build>
                    <plugins>
                      <plugin>
                        <artifactId>jacoco-maven-plugin</artifactId>
                      </plugin>
                    </plugins>
                  </build>
                </project>
                """);

        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("profiles"))
                .anyMatch(i -> i.message().contains("modules"))
                .anyMatch(i -> i.message().contains("jacoco-maven-plugin"));
    }

    @Test
    void workspace_import_materialises_root_and_child_build_jks(@TempDir Path tempDir) throws Exception {
        writePom(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>widget-parent</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>core</module>
                    <module>app</module>
                  </modules>
                </project>
                """);
        writePom(tempDir.resolve("core/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>widget-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>widget-core</artifactId>
                </project>
                """);
        writePom(tempDir.resolve("app/pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>com.example</groupId>
                    <artifactId>widget-parent</artifactId>
                    <version>1.0.0</version>
                  </parent>
                  <artifactId>widget-app</artifactId>
                  <dependencies>
                    <dependency>
                      <groupId>com.example</groupId>
                      <artifactId>widget-core</artifactId>
                      <version>1.0.0</version>
                    </dependency>
                  </dependencies>
                </project>
                """);

        var result = PomImporter.importWorkspace(tempDir.resolve("pom.xml"));

        // Root carries the workspace block.
        assertThat(result.root().isWorkspaceRoot()).isTrue();
        assertThat(result.root().workspace().members()).containsExactly("core", "app");
        assertThat(result.root().project().artifact()).isEqualTo("widget-parent");

        // Each member is present with its own coords inherited from the parent.
        assertThat(result.members()).containsKeys("core", "app");
        assertThat(result.members().get("core").project().group()).isEqualTo("com.example");
        assertThat(result.members().get("core").project().version()).isEqualTo("1.0.0");
        assertThat(result.members().get("app").dependencies().of(Scope.MAIN))
                .extracting(Dependency::module)
                .containsExactly("com.example:widget-core");

        // Parent-flatten warnings are suppressed when the parent IS the workspace root.
        assertThat(result.report().issues())
                .noneMatch(i -> i.message().contains("widget-parent")
                        && i.message().contains("did not flatten"));
    }

    @Test
    void workspace_import_falls_back_to_single_pom_when_no_modules(@TempDir Path tempDir) throws Exception {
        writePom(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>solo</artifactId>
                  <version>1.0</version>
                </project>
                """);

        var result = PomImporter.importWorkspace(tempDir.resolve("pom.xml"));
        assertThat(result.root().isWorkspaceRoot()).isFalse();
        assertThat(result.members()).isEmpty();
    }

    @Test
    void workspace_import_reports_missing_module(@TempDir Path tempDir) throws Exception {
        writePom(tempDir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>parent</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>missing</module>
                  </modules>
                </project>
                """);

        var result = PomImporter.importWorkspace(tempDir.resolve("pom.xml"));
        assertThat(result.report().hasErrors()).isTrue();
        assertThat(result.report().issues())
                .anyMatch(i -> i.message().contains("missing"));
    }

    private static PomImporter.Result importPom(String xml) {
        return PomImporter.importFromBytes(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void writePom(Path file, String xml) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, xml, StandardCharsets.UTF_8);
    }
}
