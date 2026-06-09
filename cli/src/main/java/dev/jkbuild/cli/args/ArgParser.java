// SPDX-License-Identifier: Apache-2.0
package dev.jkbuild.cli.args;

import dev.jkbuild.model.command.Arity;
import dev.jkbuild.model.command.Command;
import dev.jkbuild.model.command.Invocation;
import dev.jkbuild.model.command.Opt;
import dev.jkbuild.model.command.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent parser of an argument vector against a {@link Command}'s
 * declared {@link Opt}s and {@link Param}s — jk's replacement for picocli's
 * reflective parsing. Supports the surface jk's commands actually use:
 *
 * <ul>
 *   <li>{@code --name value}, {@code --name=value}, and boolean {@code --flag}</li>
 *   <li>short options {@code -n value}, {@code -nvalue}, and bundled boolean shorts {@code -qv}</li>
 *   <li>negatable flags ({@code --no-name})</li>
 *   <li>repeatable options and comma-{@code split} values (→ multiple values)</li>
 *   <li>optional-argument options (a {@code fallbackValue} when present without a value)</li>
 *   <li>{@code --} to end option parsing; everything after is positional</li>
 *   <li>positional arity validation ({@code 0..1}, {@code 1}, {@code 1..*}, {@code 0..*})</li>
 * </ul>
 *
 * Subcommand dispatch happens before parsing (the caller selects the leaf
 * command and hands us only its arguments).
 */
public final class ArgParser {

    private ArgParser() {}

    public static Invocation parse(Command command, List<String> args) throws ParseException {
        return parse(command, args, false);
    }

    /**
     * @param passthroughUnknown when true, unrecognized options are treated as
     *     positional arguments rather than errors — needed for passthrough commands
     *     like {@code jk mvn} / {@code jk gradle} that forward unknown flags to
     *     a child process.
     */
    public static Invocation parse(Command command, List<String> args, boolean passthroughUnknown)
            throws ParseException {
        Map<String, Opt> byName = new HashMap<>();
        for (Opt opt : command.options()) {
            for (String n : opt.names()) byName.put(n, opt);
        }

        Invocation.Builder out = Invocation.builder();
        boolean positionalsOnly = false;

        for (int i = 0; i < args.size(); i++) {
            String tok = args.get(i);

            if (positionalsOnly || tok.equals("-") || !tok.startsWith("-")) {
                out.addPositional(tok);
                continue;
            }
            if (tok.equals("--")) {
                positionalsOnly = true;
                continue;
            }

            if (tok.startsWith("--")) {
                i = parseLong(command, byName, out, args, i, tok, passthroughUnknown);
            } else {
                i = parseShort(byName, out, args, i, tok, passthroughUnknown);
            }
        }

        validate(command, out);
        return out.build();
    }

    // --- long options: --name, --name=value, --no-name -----------------------

    private static int parseLong(Command command, Map<String, Opt> byName, Invocation.Builder out,
                                 List<String> args, int i, String tok,
                                 boolean passthroughUnknown) throws ParseException {
        String name = tok;
        String inline = null;
        int eq = tok.indexOf('=');
        if (eq >= 0) {
            name = tok.substring(0, eq);
            inline = tok.substring(eq + 1);
        }

        Opt opt = byName.get(name);
        if (opt != null) {
            return consume(out, opt, args, i, inline);
        }
        // Negatable: --no-foo turns off a negatable flag --foo.
        if (name.startsWith("--no-")) {
            Opt neg = byName.get("--" + name.substring("--no-".length()));
            if (neg != null && neg.negatable() && !neg.takesValue()) {
                out.flag(neg.canonicalName(), false);
                return i;
            }
        }
        if (passthroughUnknown) {
            out.addPositional(tok);   // treat unknown option as passthrough arg
            return i;
        }
        throw new ParseException(ParseException.Kind.UNKNOWN_OPTION, tok,
                "unrecognized option " + name);
    }

    // --- short options: -n value, -nvalue, -qv (bundled flags) ---------------

    private static int parseShort(Map<String, Opt> byName, Invocation.Builder out,
                                  List<String> args, int i, String tok,
                                  boolean passthroughUnknown) throws ParseException {
        // Walk the cluster char by char; a value-taking short consumes the rest
        // of the token (or the next arg) and ends the cluster.
        for (int c = 1; c < tok.length(); c++) {
            String shortName = "-" + tok.charAt(c);
            Opt opt = byName.get(shortName);
            if (opt == null) {
                if (passthroughUnknown) {
                    out.addPositional(tok);   // treat whole cluster as passthrough
                    return i;
                }
                throw new ParseException(ParseException.Kind.UNKNOWN_OPTION, shortName,
                        "unrecognized option " + shortName);
            }
            if (opt.takesValue()) {
                String rest = tok.substring(c + 1);
                String inline = rest.isEmpty() ? null : rest;
                return consume(out, opt, args, i, inline);
            }
            out.flag(opt.canonicalName(), true);   // boolean short; continue the cluster
        }
        return i;
    }

    // --- shared value/flag consumption ---------------------------------------

    private static int consume(Invocation.Builder out, Opt opt, List<String> args, int i, String inline)
            throws ParseException {
        if (!opt.takesValue()) {
            out.flag(opt.canonicalName(), true);
            return i;
        }
        String value = inline;
        int consumed = i;
        if (value == null) {
            // Take the next token as the value, unless this is an optional-arg
            // option (then a missing/at-end value yields the fallback).
            if (i + 1 < args.size() && !looksLikeOption(args.get(i + 1))) {
                value = args.get(i + 1);
                consumed = i + 1;
            } else if (opt.fallbackValue() != null) {
                value = opt.fallbackValue();
            } else {
                throw new ParseException(ParseException.Kind.MISSING_VALUE, opt.canonicalName(),
                        "option " + opt.names().get(opt.names().size() - 1) + " requires a value");
            }
        }
        if (opt.split() != null && !opt.split().isEmpty()) {
            for (String part : value.split(java.util.regex.Pattern.quote(opt.split()), -1)) {
                out.addValue(opt.canonicalName(), part);
            }
        } else if (opt.repeatable()) {
            out.addValue(opt.canonicalName(), value);
        } else {
            out.putValue(opt.canonicalName(), value);
        }
        return consumed;
    }

    private static boolean looksLikeOption(String tok) {
        // "-" alone is a value (stdin convention); "-x"/"--x" are options.
        return tok.length() > 1 && tok.charAt(0) == '-';
    }

    // --- validation ----------------------------------------------------------

    private static void validate(Command command, Invocation.Builder builder) throws ParseException {
        Invocation snapshot = builder.build();
        for (Opt opt : command.options()) {
            if (opt.required() && !snapshot.has(opt.canonicalName())) {
                throw new ParseException(ParseException.Kind.MISSING_REQUIRED,
                        opt.names().get(opt.names().size() - 1),
                        "missing required option " + opt.names().get(opt.names().size() - 1));
            }
        }

        int given = snapshot.positionals().size();
        int min = 0;
        long max = 0;
        for (Param p : command.parameters()) {
            min += p.arity().min();
            max += p.arity().max();
            if (max < 0) max = Integer.MAX_VALUE;   // overflow guard
        }
        if (given < min) {
            Param firstUnsatisfied = command.parameters().stream()
                    .filter(p -> p.arity().required()).findFirst().orElse(null);
            String name = firstUnsatisfied != null ? firstUnsatisfied.name() : "argument";
            throw new ParseException(ParseException.Kind.MISSING_REQUIRED, name,
                    "missing required argument <" + name + ">");
        }
        if (given > max) {
            throw new ParseException(ParseException.Kind.TOO_MANY_ARGS,
                    snapshot.positionals().get((int) max),
                    "unexpected extra argument: " + snapshot.positionals().get((int) max));
        }
    }
}
