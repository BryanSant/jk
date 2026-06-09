// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.args;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.Command;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArgParserTest {

    /** A command exposing one of each option/param shape the parser must handle. */
    private static Command cmd(List<Opt> opts, List<Param> params) {
        return new Command() {
            @Override public String name() { return "demo"; }
            @Override public String description() { return "demo"; }
            @Override public List<Opt> options() { return opts; }
            @Override public List<Param> parameters() { return params; }
        };
    }

    private static Invocation parse(Command c, String... args) throws ParseException {
        return ArgParser.parse(c, List.of(args));
    }

    @Test
    void longValueOption_spaceAndEquals() throws Exception {
        Command c = cmd(List.of(Opt.value("<name>", "profile", "--profile")), List.of());
        assertThat(parse(c, "--profile", "ci").value("profile")).contains("ci");
        assertThat(parse(c, "--profile=ci").value("profile")).contains("ci");
    }

    @Test
    void booleanFlag_longAndShortAndBundled() throws Exception {
        Command c = cmd(List.of(
                Opt.flag("quiet", "-q", "--quiet"),
                Opt.flag("verbose", "-v", "--verbose")), List.of());
        assertThat(parse(c, "--quiet").isSet("quiet")).isTrue();
        assertThat(parse(c, "-q").isSet("quiet")).isTrue();
        Invocation bundled = parse(c, "-qv");
        assertThat(bundled.isSet("quiet")).isTrue();
        assertThat(bundled.isSet("verbose")).isTrue();
        assertThat(parse(c).isSet("quiet")).isFalse();
    }

    @Test
    void shortValueOption_attachedOrSeparate() throws Exception {
        Command c = cmd(List.of(Opt.value("<N>", "workers", "-w", "--workers")), List.of());
        assertThat(parse(c, "-w", "4").value("workers")).contains("4");
        assertThat(parse(c, "-w4").value("workers")).contains("4");
        assertThat(parse(c, "--workers=8").value("workers")).contains("8");
    }

    @Test
    void negatableFlag() throws Exception {
        Command c = cmd(List.of(Opt.flag("executable", "--executable").negate()), List.of());
        assertThat(parse(c, "--executable").flag("executable")).contains(true);
        assertThat(parse(c, "--no-executable").flag("executable")).contains(false);
        assertThat(parse(c).flag("executable")).isEmpty();
    }

    @Test
    void splitValuesIntoList() throws Exception {
        Command c = cmd(List.of(Opt.value("<csv>", "features", "--features").splitOn(",")), List.of());
        assertThat(parse(c, "--features", "a,b,c").values("features")).containsExactly("a", "b", "c");
    }

    @Test
    void repeatableOption() throws Exception {
        Command c = cmd(List.of(Opt.value("<dep>", "dep", "--dep").repeat()), List.of());
        assertThat(parse(c, "--dep", "x", "--dep", "y").values("dep")).containsExactly("x", "y");
    }

    @Test
    void singleValueOption_lastWins() throws Exception {
        Command c = cmd(List.of(Opt.value("<name>", "profile", "--profile")), List.of());
        assertThat(parse(c, "--profile", "a", "--profile", "b").value("profile")).contains("b");
    }

    @Test
    void optionalArgOption_usesFallbackWhenPresentWithoutValue() throws Exception {
        Command c = cmd(List.of(Opt.value("<path>", "tarball", "--tarball").withFallback("")), List.of());
        assertThat(parse(c, "--tarball").value("tarball")).contains("");
        assertThat(parse(c, "--tarball", "out.tar").value("tarball")).contains("out.tar");
    }

    @Test
    void doubleDashEndsOptionParsing() throws Exception {
        Command c = cmd(List.of(Opt.flag("quiet", "--quiet")),
                List.of(Param.of("args", Arity.ZERO_OR_MORE, "passthrough")));
        Invocation in = parse(c, "--", "--quiet", "-x", "foo");
        assertThat(in.isSet("quiet")).isFalse();
        assertThat(in.positionals()).containsExactly("--quiet", "-x", "foo");
    }

    @Test
    void positionals_arityValidation() {
        Command oneRequired = cmd(List.of(), List.of(Param.of("coord", Arity.ONE, "the coord")));
        assertThatThrownBy(() -> parse(oneRequired))
                .isInstanceOf(ParseException.class);
        ParseException tooMany = catchThrowableOfType(
                () -> parse(oneRequired, "a", "b"), ParseException.class);
        assertThat(tooMany.kind()).isEqualTo(ParseException.Kind.TOO_MANY_ARGS);
    }

    @Test
    void unknownOption_throws() {
        Command c = cmd(List.of(Opt.flag("quiet", "--quiet")), List.of());
        ParseException ex = catchThrowableOfType(() -> parse(c, "--bogus"), ParseException.class);
        assertThat(ex.kind()).isEqualTo(ParseException.Kind.UNKNOWN_OPTION);
        assertThat(ex.token()).isEqualTo("--bogus");
    }

    @Test
    void missingValue_throws() {
        Command c = cmd(List.of(Opt.value("<name>", "profile", "--profile")), List.of());
        ParseException ex = catchThrowableOfType(() -> parse(c, "--profile"), ParseException.class);
        assertThat(ex.kind()).isEqualTo(ParseException.Kind.MISSING_VALUE);
    }

    @Test
    void requiredOption_throwsWhenAbsent() {
        Command c = cmd(List.of(Opt.value("<url>", "repo", "--repo-url").require()), List.of());
        ParseException ex = catchThrowableOfType(() -> parse(c), ParseException.class);
        assertThat(ex.kind()).isEqualTo(ParseException.Kind.MISSING_REQUIRED);
    }

    @Test
    void mixedOptionsAndPositionals() throws Exception {
        Command c = cmd(
                List.of(Opt.flag("quiet", "-q", "--quiet"), Opt.value("<name>", "profile", "--profile")),
                List.of(Param.of("target", Arity.ZERO_OR_MORE, "targets")));
        Invocation in = parse(c, "-q", "build", "--profile", "ci", "test");
        assertThat(in.isSet("quiet")).isTrue();
        assertThat(in.value("profile")).contains("ci");
        assertThat(in.positionals()).containsExactly("build", "test");
    }
}
