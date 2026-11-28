/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.enginehost;

import com.ash.drishti.enginehost.HostedEngine;
import com.ash.drishti.kernel.api.services.EngineAnswer;
import com.ash.drishti.kernel.api.services.EngineTable;
import com.ash.engine.Answer;
import com.ash.engine.ColumnSchema;
import com.ash.engine.Missing;
import com.ash.engine.Table;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * An engine's answer as the kernel API reads it ({@link EngineAnswer}): the gated answer's tables read in place, and what a module shapes
 * from it kept with the answer's entry in the host's one answer cache ({@link HostedEngine.Answered#keep}). Made per ask; the shaped values
 * and the indexes of {@link EngineTable#find} live in the cache entry, so a later ask of the same answer (same stamp: same question, rows
 * and data) reads them without building them again.
 */
final class HostedAnswer implements EngineAnswer {

    /** What a shaped value that does not say its size is counted as. */
    static final long UNSIZED_BYTES = 256;

    private final HostedEngine.Answered answered;
    private final Answer answer;
    /** Shaped values of an answer that is not kept (the cache off): this answer's own, for the life of the ask. */
    private final Map<String, Object> own = new java.util.concurrent.ConcurrentHashMap<>();

    HostedAnswer(HostedEngine.Answered answered) {
        this.answered = answered;
        this.answer = answered.answer();
    }

    @Override
    public String status() {
        return answer.status().name();
    }

    @Override
    public List<String> missing() {
        return answer.missing().stream().map(Missing::words).toList();
    }

    @Override
    public List<String> notes() {
        return answer.notes();
    }

    @Override
    public Map<String, String> inputStamps() {
        return answer.stamp() == null ? Map.of() : answer.stamp().inputs();
    }

    @Override
    public String key() {
        return answer.stamp() == null ? "" : answer.stamp().text();
    }

    @Override
    public EngineTable table(String output) {
        for (Table t : answer.tables()) {
            if (t.schema().name().equals(output)) {
                return new Columns(this, t);
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T shaped(String name, Function<EngineAnswer, T> shape) {
        Object have = answered.kept() != null ? answered.kept(name) : own.get(name);
        if (have != null) {
            return (T) have;
        }
        T made = shape.apply(this);
        if (made == null) {
            return null;
        }
        if (answered.kept() == null) {
            Object had = own.putIfAbsent(name, made);
            return had != null ? (T) had : made;
        }
        return (T) answered.keep(name, made, made instanceof Sized s ? s.bytes() : UNSIZED_BYTES);
    }

    /** One table read in place: each column's array fetched once per reader; {@link #find} indexes kept with the answer. */
    private static final class Columns implements EngineTable {

        private final HostedAnswer owner;
        private final Table t;
        private final Map<String, Object> arrays = new HashMap<>();

        Columns(HostedAnswer owner, Table t) {
            this.owner = owner;
            this.t = t;
        }

        @Override
        public String name() {
            return t.schema().name();
        }

        @Override
        public List<String> columns() {
            return t.schema().columns().stream().map(ColumnSchema::name).toList();
        }

        @Override
        public int rows() {
            return t.rows();
        }

        private Object array(String column) {
            Object a = arrays.get(column);
            if (a == null) {
                ColumnSchema c = t.schema().column(column);
                if (c == null) {
                    throw new IllegalArgumentException(name() + " has no column " + column + "; its columns are " + columns());
                }
                a = switch (c.carrier()) {
                    case TEXT -> t.texts(column);
                    case INTEGER -> t.integers(column);
                    case NUMBER -> t.numbers(column);
                    case FLAG -> t.flags(column);
                    case LIST -> t.lists(column);
                };
                arrays.put(column, a);
            }
            return a;
        }

        @Override
        public String text(String column, int row) {
            return array(column) instanceof String[] s ? s[row] : t.isNull(column, row) ? null : String.valueOf(value(column, row));
        }

        @Override
        public double number(String column, int row) {
            Object a = array(column);
            return a instanceof double[] d ? d[row] : a instanceof long[] l ? (t.isNull(column, row) ? Double.NaN : l[row]) : Double.NaN;
        }

        @Override
        public long integer(String column, int row) {
            Object a = array(column);
            return a instanceof long[] l ? l[row] : a instanceof double[] d ? (long) d[row] : 0;
        }

        @Override
        public boolean flag(String column, int row) {
            return array(column) instanceof boolean[] f && f[row];
        }

        @Override
        public double[] list(String column, int row) {
            return array(column) instanceof double[][] l ? l[row] : null;
        }

        @Override
        public boolean isNull(String column, int row) {
            return t.isNull(column, row);
        }

        @Override
        public Object value(String column, int row) {
            if (t.isNull(column, row)) {
                return null;
            }
            Object a = array(column);
            if (a instanceof String[] s) {
                return s[row];
            }
            if (a instanceof long[] l) {
                return l[row];
            }
            if (a instanceof double[] d) {
                return d[row];
            }
            if (a instanceof boolean[] f) {
                return f[row];
            }
            return ((double[][]) a)[row];
        }

        @Override
        public int find(String keyColumn, String key) {
            Index index = owner.shaped("find|" + name() + "|" + keyColumn, x -> Index.of(this, keyColumn));
            Integer r = index.rows().get(key);
            return r == null ? -1 : r;
        }
    }

    /** One column's rows by value (the first row of each), counted in bytes with the answer. */
    private record Index(Map<String, Integer> rows, long bytes) implements Sized {

        static Index of(Columns table, String column) {
            Map<String, Integer> rows = new HashMap<>(Math.max(16, table.rows() * 2));
            long bytes = 64;
            for (int r = 0; r < table.rows(); r++) {
                String v = table.text(column, r);
                if (v != null && rows.putIfAbsent(v, r) == null) {
                    bytes += 72 + 2L * v.length();
                }
            }
            return new Index(rows, bytes);
        }
    }
}
