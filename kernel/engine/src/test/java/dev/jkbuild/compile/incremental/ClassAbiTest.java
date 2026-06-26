// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.compile.incremental;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassAbiTest {

    @Test
    void body_only_change_keeps_the_abi_stable(@TempDir Path dir) throws IOException {
        byte[] v1 = JavacFixture.compileOne(
                dir.resolve("v1"), "a.Foo", "package a; public class Foo { public int f() { return 1; } }");
        byte[] v2 = JavacFixture.compileOne(
                dir.resolve("v2"), "a.Foo", "package a; public class Foo { public int f() { return 2 + 40; } }");
        assertThat(ClassAbi.hash(v2)).isEqualTo(ClassAbi.hash(v1));
    }

    @Test
    void adding_a_public_method_changes_the_abi(@TempDir Path dir) throws IOException {
        byte[] v1 = JavacFixture.compileOne(
                dir.resolve("v1"), "a.Foo", "package a; public class Foo { public int f() { return 1; } }");
        byte[] v2 = JavacFixture.compileOne(
                dir.resolve("v2"),
                "a.Foo",
                "package a; public class Foo { public int f() { return 1; } public int g() { return 2; } }");
        assertThat(ClassAbi.hash(v2)).isNotEqualTo(ClassAbi.hash(v1));
    }

    @Test
    void private_member_changes_do_not_affect_the_abi(@TempDir Path dir) throws IOException {
        byte[] v1 = JavacFixture.compileOne(
                dir.resolve("v1"),
                "a.Foo",
                "package a; public class Foo { private int secret() { return 1; } public int f() { return secret(); } }");
        byte[] v2 = JavacFixture.compileOne(
                dir.resolve("v2"),
                "a.Foo",
                "package a; public class Foo { private long secret() { return 9; } public int f() { return (int) secret(); } }");
        assertThat(ClassAbi.hash(v2)).isEqualTo(ClassAbi.hash(v1));
    }

    @Test
    void changing_a_public_constant_value_changes_the_abi(@TempDir Path dir) throws IOException {
        // static final constants are inlined into dependents, so their value is ABI.
        byte[] v1 = JavacFixture.compileOne(
                dir.resolve("v1"), "a.Foo", "package a; public class Foo { public static final int X = 1; }");
        byte[] v2 = JavacFixture.compileOne(
                dir.resolve("v2"), "a.Foo", "package a; public class Foo { public static final int X = 2; }");
        assertThat(ClassAbi.hash(v2)).isNotEqualTo(ClassAbi.hash(v1));
    }

    @Test
    void changing_a_method_return_type_changes_the_abi(@TempDir Path dir) throws IOException {
        byte[] v1 = JavacFixture.compileOne(
                dir.resolve("v1"), "a.Foo", "package a; public class Foo { public int f() { return 1; } }");
        byte[] v2 = JavacFixture.compileOne(
                dir.resolve("v2"), "a.Foo", "package a; public class Foo { public long f() { return 1; } }");
        assertThat(ClassAbi.hash(v2)).isNotEqualTo(ClassAbi.hash(v1));
    }
}
