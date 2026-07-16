// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.util.MinimalXml.Element;
import org.junit.jupiter.api.Test;

class MinimalXmlTest {

    @Test
    void parses_elements_attributes_and_text() {
        Element root = MinimalXml.parse(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <settings>
                  <servers>
                    <server>
                      <id>corp-releases</id>
                      <username>alice</username>
                      <password>s3cret</password>
                    </server>
                  </servers>
                </settings>
                """);
        assertThat(root.name()).isEqualTo("settings");
        Element server = root.descendants("server").getFirst();
        assertThat(server.element("id").orElseThrow().text()).isEqualTo("corp-releases");
        assertThat(server.element("username").orElseThrow().text()).isEqualTo("alice");
        assertThat(server.element("missing")).isEmpty();
    }

    @Test
    void decodes_entities_in_text_and_attributes() {
        Element root = MinimalXml.parse(
                "<a name=\"q&quot;&amp;&apos;\"><b>1 &lt; 2 &gt; 0 &amp; &#65;&#x42;</b></a>");
        assertThat(root.attr("name")).isEqualTo("q\"&'");
        assertThat(root.element("b").orElseThrow().text()).isEqualTo("1 < 2 > 0 & AB");
    }

    @Test
    void handles_self_closing_single_quotes_cdata_and_comments() {
        Element root = MinimalXml.parse(
                "<r><homePath value='$USER_HOME$/.jdks/x' /><!-- note --><t><![CDATA[a<b&c]]></t></r>");
        assertThat(root.elements("homePath").getFirst().attr("value")).isEqualTo("$USER_HOME$/.jdks/x");
        assertThat(root.element("t").orElseThrow().text()).isEqualTo("a<b&c");
        assertThat(root.children()).hasSize(3); // comment preserved between the two elements
    }

    @Test
    void rejects_doctype_and_malformed_documents() {
        assertThatThrownBy(() -> MinimalXml.parse("<!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><a/>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinimalXml.parse("<a><b></a>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinimalXml.parse("<a>")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MinimalXml.parse("plain text")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void round_trips_an_intellij_style_jdk_table() {
        String table =
                """
                <application>
                  <component name="ProjectJdkTable">
                    <jdk version="2">
                      <name value="jk-temurin-21" />
                      <type value="JavaSDK" />
                      <homePath value="$USER_HOME$/.jk/jdks/current" />
                      <roots>
                        <classPath>
                          <root type="composite">
                            <root url="jrt://home!/java.base" type="simple" />
                          </root>
                        </classPath>
                      </roots>
                      <additional />
                    </jdk>
                  </component>
                </application>
                """;
        Element parsed = MinimalXml.parse(table);
        String written = MinimalXml.write(parsed);
        // Round-trip is stable: parse(write(x)) produces the identical serialization.
        assertThat(MinimalXml.write(MinimalXml.parse(written))).isEqualTo(written);
        // And the content survives: same jdk entry, same attribute ordering.
        Element again = MinimalXml.parse(written);
        Element jdk = again.descendants("jdk").getFirst();
        assertThat(jdk.attr("version")).isEqualTo("2");
        assertThat(jdk.elements("name").getFirst().attr("value")).isEqualTo("jk-temurin-21");
        assertThat(jdk.descendants("root").getFirst().attr("type")).isEqualTo("composite");
        assertThat(written).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<application>");
    }

    @Test
    void writer_escapes_and_inlines_text_only_elements() {
        Element root = Element.of("a");
        root.append(Element.of("b").setAttr("v", "x\"<y>&z"));
        Element c = Element.of("c");
        c.append(new MinimalXml.Text("1 < 2 & 3"));
        root.append(c);
        assertThat(MinimalXml.write(root))
                .contains("<b v=\"x&quot;&lt;y&gt;&amp;z\" />")
                .contains("<c>1 &lt; 2 &amp; 3</c>");
    }
}
